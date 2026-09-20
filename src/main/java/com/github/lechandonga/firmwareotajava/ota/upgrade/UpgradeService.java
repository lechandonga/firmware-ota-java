package com.github.lechandonga.firmwareotajava.ota.upgrade;

import com.github.lechandonga.firmwareotajava.ota.boot.BootCoordinator;
import com.github.lechandonga.firmwareotajava.ota.config.OtaProperties;
import com.github.lechandonga.firmwareotajava.ota.fault.FaultInjection;
import com.github.lechandonga.firmwareotajava.ota.model.DeviceStatus;
import com.github.lechandonga.firmwareotajava.ota.model.FirmwareManifest;
import com.github.lechandonga.firmwareotajava.ota.model.ImageInfo;
import com.github.lechandonga.firmwareotajava.ota.model.PackageException;
import com.github.lechandonga.firmwareotajava.ota.model.RejectReason;
import com.github.lechandonga.firmwareotajava.ota.model.RollbackReason;
import com.github.lechandonga.firmwareotajava.ota.model.SelfTestSpec;
import com.github.lechandonga.firmwareotajava.ota.model.SlotLayout;
import com.github.lechandonga.firmwareotajava.ota.model.TransferInterruptedException;
import com.github.lechandonga.firmwareotajava.ota.model.UpgradePhase;
import com.github.lechandonga.firmwareotajava.ota.model.UpgradeRecord;
import com.github.lechandonga.firmwareotajava.ota.packageinfo.FirmwarePackageReader;
import com.github.lechandonga.firmwareotajava.ota.packageinfo.FirmwarePackageValidator;
import com.github.lechandonga.firmwareotajava.ota.slots.SlotManager;
import com.github.lechandonga.firmwareotajava.ota.staging.StagingService;
import com.github.lechandonga.firmwareotajava.ota.storage.DeviceStorage;
import com.github.lechandonga.firmwareotajava.ota.storage.HistoryStore;
import com.github.lechandonga.firmwareotajava.ota.storage.JsonFileStore;
import com.github.lechandonga.firmwareotajava.ota.model.StagingState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * 升级编排（单机串行）：
 * synchronized 保证同一时刻只有一个升级流程在执行；
 * 同一 upgradeId 的重复请求幂等恢复（返回同一条记录，不产生重复写入）。
 */
@Service
public class UpgradeService {

    private static final Logger log = LoggerFactory.getLogger(UpgradeService.class);

    private final OtaProperties props;
    private final DeviceStorage storage;
    private final FirmwarePackageReader reader;
    private final FirmwarePackageValidator validator;
    private final StagingService staging;
    private final SlotManager slotManager;
    private final HistoryStore history;
    private final BootCoordinator bootCoordinator;
    private final FaultInjection faults;
    private final JsonFileStore json;

    public UpgradeService(OtaProperties props,
                          DeviceStorage storage,
                          FirmwarePackageReader reader,
                          FirmwarePackageValidator validator,
                          StagingService staging,
                          SlotManager slotManager,
                          HistoryStore history,
                          BootCoordinator bootCoordinator,
                          FaultInjection faults,
                          JsonFileStore json) {
        this.props = props;
        this.storage = storage;
        this.reader = reader;
        this.validator = validator;
        this.staging = staging;
        this.slotManager = slotManager;
        this.history = history;
        this.bootCoordinator = bootCoordinator;
        this.faults = faults;
        this.json = json;
    }

    /**
     * 启动或恢复一次升级。
     *
     * @param upgradeId  调用方指定的幂等键
     * @param packageDir 本地升级包目录
     * @return 升级记录（可能是终态，也可能停在中间态）
     */
    public synchronized UpgradeRecord startUpgrade(String upgradeId, Path packageDir) {
        slotManager.provisionFactoryIfEmpty(
                props.getFactoryVersion(),
                props.getFactoryPayload().getBytes(StandardCharsets.UTF_8));

        UpgradeRecord existing = history.byId(upgradeId);
        if (existing != null) {
            return resumeExisting(existing, packageDir);
        }

        if (hasPendingUpgrade()) {
            throw new IllegalStateException("存在待自检的升级，必须先完成启动自检（重启）或回滚后才能发起新升级");
        }

        UpgradeRecord record = newRecord(upgradeId, packageDir);
        FirmwareManifest manifest;
        try {
            manifest = reader.readManifest(packageDir);
            record.setTargetVersion(manifest.version());
            record.setHardwareModel(manifest.hardwareModel());
            record.setHardwareVersion(manifest.hardwareVersion());
            validator.validate(manifest, slotManager.activeImage(), storage.totalUsedBytes());
        } catch (PackageException e) {
            // 预检拒绝：不写入升级历史、不创建任何临时资源，在位版本不变
            return rejectTransient(record, e.getReason(), e.getMessage());
        } catch (UncheckedIOException e) {
            return rejectTransient(record, RejectReason.MANIFEST_CORRUPT,
                    "读取升级包失败: " + e.getMessage());
        }

        history.append(record);
        return runStagingAndInstall(record, packageDir, manifest);
    }

    private UpgradeRecord resumeExisting(UpgradeRecord record, Path packageDir) {
        switch (record.getPhase()) {
            case REJECTED, COMMITTED, ROLLED_BACK -> {
                log.info("升级 {} 已处于终态 {}，重复请求直接返回既有结果",
                        record.getUpgradeId(), record.getPhase());
                return record;
            }
            case STAGING, STAGED, INSTALLING, PENDING_SELF_TEST -> {
                if (hasPendingUpgrade()) {
                    log.info("升级 {} 已完成切换待自检，重复请求不重复写入", record.getUpgradeId());
                    return record;
                }
                FirmwareManifest manifest = reader.readManifest(packageDir);
                return runStagingAndInstall(record, packageDir, manifest);
            }
            default -> {
                return record;
            }
        }
    }

    private UpgradeRecord runStagingAndInstall(UpgradeRecord record, Path packageDir,
                                               FirmwareManifest manifest) {
        String upgradeId = record.getUpgradeId();
        try {
            // 阶段 1：断点续传
            if (record.getPhase() == UpgradePhase.STAGING
                    || record.getPhase() == null) {
                staging.initOrResume(upgradeId, manifest);
                record.setPhase(UpgradePhase.STAGING);
                save(record);
            }
            StagingState state = staging.state(upgradeId);
            if (!state.isComplete()) {
                staging.transferMissingChunks(upgradeId, packageDir, manifest);
                staging.finalizeStaging(upgradeId, manifest);
            }
            if (record.getPhase() == UpgradePhase.STAGING) {
                record.setPhase(UpgradePhase.STAGED);
                save(record);
            }

            // 阶段 2：安装到非活动槽（不影响在位版本）
            if (record.getPhase() != UpgradePhase.PENDING_SELF_TEST) {
                String targetSlot = record.getTargetSlot();
                if (targetSlot == null) {
                    targetSlot = slotManager.inactiveSlot();
                    record.setTargetSlot(targetSlot);
                }
                record.setPhase(UpgradePhase.INSTALLING);
                save(record);

                ImageInfo info = toImageInfo(manifest);
                slotManager.installToSlot(targetSlot, staging.state(upgradeId), info);

                // 阶段 3：原子切换
                slotManager.activatePending(targetSlot, upgradeId);
                record.setPhase(UpgradePhase.PENDING_SELF_TEST);
                save(record);
                log.info("升级 {} 已切换到槽位 {}，等待重启自检", upgradeId, targetSlot);
            }

            // 阶段 4：模拟重启（启动决策）-> 自检通过转正 / 失败回滚
            bootCoordinator.simulateReboot();
            UpgradeRecord outcome = history.byId(upgradeId);
            if (outcome != null && outcome.getPhase() != UpgradePhase.PENDING_SELF_TEST) {
                staging.cleanup(upgradeId);
                return outcome;
            }
            return record;
        } catch (TransferInterruptedException e) {
            log.warn("升级 {} 传输/写入中断（已处理 {} 字节），状态已保留，可随时续传",
                    upgradeId, e.getBytesProcessed());
            return record;
        } catch (PackageException e) {
            // 例如续传后整包校验失败：拒绝并清理临时资源
            reject(record, e.getReason(), e.getMessage());
            staging.cleanup(upgradeId);
            return record;
        } catch (RuntimeException e) {
            // 安装期掉电等：记录失败原因但保留中间态以便诊断；在位版本未受影响
            record.setFailureReason(e.getMessage());
            save(record);
            log.error("升级 {} 发生未预期错误: {}", upgradeId, e.getMessage(), e);
            throw e;
        }
    }

    private boolean hasPendingUpgrade() {
        return slotManager.layout().getPendingSlot() != null;
    }

    private ImageInfo toImageInfo(FirmwareManifest manifest) {
        ImageInfo info = new ImageInfo();
        info.setVersion(manifest.version());
        info.setPayloadSha256(manifest.payloadSha256());
        info.setPayloadSize(manifest.payloadSize());
        info.setHardwareModel(manifest.hardwareModel());
        info.setHardwareVersion(manifest.hardwareVersion());
        info.setMinCompatibleVersion(manifest.minCompatibleVersion());
        SelfTestSpec spec = manifest.selfTest();
        info.setSelfTest(spec == null ? SelfTestSpec.alwaysOk() : spec);
        return info;
    }

    private UpgradeRecord newRecord(String upgradeId, Path packageDir) {
        UpgradeRecord record = new UpgradeRecord();
        record.setUpgradeId(upgradeId);
        record.setStartedAt(Instant.now());
        record.setPhase(UpgradePhase.STAGING);
        ImageInfo current = slotManager.activeImage();
        record.setFromVersion(current == null ? props.getFactoryVersion() : current.getVersion());
        return record;
    }

    /** 预检拒绝：仅填充并返回记录（含可区分原因），不落历史、不建资源，不影响在位版本。 */
    private UpgradeRecord rejectTransient(UpgradeRecord record, RejectReason reason, String message) {
        record.setPhase(UpgradePhase.REJECTED);
        record.setRejectReason(reason);
        record.setFailureReason(message);
        record.setFinishedAt(Instant.now());
        log.warn("升级 {} 被拒绝 [{}]：{}（预检拒绝：不写入历史、不占用资源、在位版本不变）",
                record.getUpgradeId(), reason, message);
        return record;
    }

    /** 升级中途校验失败：更新历史中的记录。 */
    private void reject(UpgradeRecord record, RejectReason reason, String message) {
        record.setPhase(UpgradePhase.REJECTED);
        record.setRejectReason(reason);
        record.setFailureReason(message);
        record.setFinishedAt(Instant.now());
        history.update(record);
        log.warn("升级 {} 被拒绝 [{}]：{}（在位版本不变，升级历史保留）",
                record.getUpgradeId(), reason, message);
    }

    private void save(UpgradeRecord record) {
        if (history.byId(record.getUpgradeId()) == null) {
            history.append(record);
        } else {
            history.update(record);
        }
    }

    /** 查询设备与当前升级状态。 */
    public synchronized DeviceStatus status() {
        SlotLayout layout = slotManager.layout();
        ImageInfo active = slotManager.imageInfo(layout.getActiveSlot());
        String pendingVersion = null;
        UpgradePhase phase = null;
        if (layout.getPendingSlot() != null) {
            ImageInfo pending = slotManager.imageInfo(layout.getPendingSlot());
            pendingVersion = pending == null ? null : pending.getVersion();
            UpgradeRecord rec = history.byId(layout.getPendingUpgradeId());
            phase = rec == null ? UpgradePhase.PENDING_SELF_TEST : rec.getPhase();
        }
        return new DeviceStatus(
                props.getDeviceModel(),
                props.getHardwareVersion(),
                layout.getActiveSlot(),
                active == null ? null : active.getVersion(),
                layout.getPendingSlot(),
                pendingVersion,
                phase,
                layout.getBootCount(),
                layout.getSelfTestAttempts(),
                storage.totalUsedBytes(),
                props.getMaxStorageBytes());
    }

    /** 历史（含拒绝、回滚，追加式不可删除）。 */
    public synchronized List<UpgradeRecord> history() {
        return history.all();
    }

    public synchronized UpgradeRecord getUpgrade(String upgradeId) {
        return history.byId(upgradeId);
    }

    /**
     * 手动回滚到最近确认版本。
     *
     * @param upgradeId 本次回滚操作的幂等键（可空，自动生成）
     * @throws IllegalStateException 没有可回滚的确认版本
     */
    public synchronized UpgradeRecord rollback(String upgradeId) {
        SlotLayout layout = slotManager.layout();
        ImageInfo active = slotManager.activeImage();
        if (!slotManager.hasRollbackTarget()) {
            throw new IllegalStateException("没有与当前活动版本不同的完整好版本，无可回滚目标");
        }
        String targetSlot = layout.getPreviousCommittedSlot();
        ImageInfo target = slotManager.imageInfo(targetSlot);

        String id = upgradeId == null || upgradeId.isBlank()
                ? "rollback-" + System.currentTimeMillis()
                : upgradeId;
        UpgradeRecord existing = history.byId(id);
        if (existing != null && existing.getPhase() == UpgradePhase.ROLLED_BACK) {
            return existing;
        }
        UpgradeRecord record = new UpgradeRecord();
        record.setUpgradeId(id);
        record.setFromVersion(active == null ? null : active.getVersion());
        record.setTargetVersion(target.getVersion());
        record.setPhase(UpgradePhase.ROLLED_BACK);
        record.setRollbackReason(RollbackReason.MANUAL);
        record.setFailureReason("手动回滚：从版本 " + record.getFromVersion()
                + " 恢复到版本 " + target.getVersion());
        record.setStartedAt(Instant.now());
        record.setFinishedAt(Instant.now());

        slotManager.rollbackToPreviousGood();
        history.append(record);
        log.warn("手动回滚完成：{} -> {}", record.getFromVersion(), record.getTargetVersion());
        return record;
    }
}
