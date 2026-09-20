package com.github.lechandonga.firmwareotajava.ota.boot;

import com.github.lechandonga.firmwareotajava.ota.config.OtaProperties;
import com.github.lechandonga.firmwareotajava.ota.model.ImageInfo;
import com.github.lechandonga.firmwareotajava.ota.model.RollbackReason;
import com.github.lechandonga.firmwareotajava.ota.model.SelfTestVerdict;
import com.github.lechandonga.firmwareotajava.ota.model.SlotLayout;
import com.github.lechandonga.firmwareotajava.ota.model.UpgradePhase;
import com.github.lechandonga.firmwareotajava.ota.model.UpgradeRecord;
import com.github.lechandonga.firmwareotajava.ota.slots.SlotManager;
import com.github.lechandonga.firmwareotajava.ota.storage.DeviceStorage;
import com.github.lechandonga.firmwareotajava.ota.storage.HistoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * 启动协调：进程/设备启动时执行一次。
 * 根据持久化的 slot-layout 决定落在哪个版本：
 * pending 槽完好 -> 运行自检 -> 通过转正 / 失败或超时回滚；
 * pending 槽损坏 -> IMAGE_CORRUPT_ON_BOOT 回滚；无 pending -> 正常启动在位版本。
 * 决策结果写入追加式历史，回滚记录不会被后续升级删除。
 */
@Component
public class BootCoordinator {

    private static final Logger log = LoggerFactory.getLogger(BootCoordinator.class);

    private final OtaProperties props;
    private final DeviceStorage storage;
    private final SlotManager slotManager;
    private final HistoryStore history;
    private final SelfTestRunner selfTestRunner;

    private volatile String lastBootDecision = "NOT_STARTED";

    public BootCoordinator(OtaProperties props,
                           DeviceStorage storage,
                           SlotManager slotManager,
                           HistoryStore history,
                           SelfTestRunner selfTestRunner) {
        this.props = props;
        this.storage = storage;
        this.slotManager = slotManager;
        this.history = history;
        this.selfTestRunner = selfTestRunner;
    }

    /** 应用启动时执行一次。 */
    public synchronized void onStartup() {
        slotManager.provisionFactoryIfEmpty(
                props.getFactoryVersion(),
                props.getFactoryPayload().getBytes(StandardCharsets.UTF_8));

        SlotLayout layout = slotManager.layout();
        layout.setBootCount(layout.getBootCount() + 1);
        slotManager.writeLayout(layout);

        String pending = layout.getPendingSlot();
        if (pending == null) {
            ImageInfo active = slotManager.activeImage();
            String version = active == null ? "?" : active.getVersion();
            decide("NORMAL_BOOT: 活动版本 " + version + " 正常启动（无待确认升级）");
            log.info("第 {} 次启动：在位版本 {} 正常运行", layout.getBootCount(), version);
            return;
        }

        ImageInfo pendingImage = slotManager.imageInfo(pending);
        String pendingVersion = pendingImage == null ? "?" : pendingImage.getVersion();
        if (!slotManager.verifySlot(pending)) {
            log.error("待确认槽位 {} 镜像不完整（可能在切换前掉电），执行回滚", pending);
            performRollback(layout, RollbackReason.IMAGE_CORRUPT_ON_BOOT,
                    "待确认槽位 " + pending + "（版本 " + pendingVersion + "）镜像校验失败");
            return;
        }

        layout.setSelfTestAttempts(layout.getSelfTestAttempts() + 1);
        slotManager.writeLayout(layout);
        log.info("第 {} 次启动：对待确认版本 {} 执行启动自检（第 {} 次尝试），超时 {}ms",
                layout.getBootCount(), pendingVersion, layout.getSelfTestAttempts(),
                pendingImage.getSelfTest().timeoutMs());

        SelfTestVerdict verdict = selfTestRunner.run(
                storage.slotPayload(pending), pendingImage, pendingImage.getSelfTest());

        switch (verdict) {
            case PASS -> {
                slotManager.markCommitted();
                recordPendingOutcome(layout.getPendingUpgradeId(), UpgradePhase.COMMITTED,
                        null, null, "版本 " + pendingVersion + " 启动自检通过并转正");
                decide("SELF_TEST_PASS: 新版本 " + pendingVersion + " 自检通过，已转正");
            }
            case FAILED -> performRollback(slotManager.layout(), RollbackReason.SELF_TEST_FAILED,
                    "版本 " + pendingVersion + " 启动自检判定失败");
            case TIMEOUT -> performRollback(slotManager.layout(), RollbackReason.SELF_TEST_TIMEOUT,
                    "版本 " + pendingVersion + " 启动自检超时");
        }
    }

    private void performRollback(SlotLayout layout, RollbackReason reason, String detail) {
        String upgradeId = layout.getPendingUpgradeId();
        String badVersion = "?";
        ImageInfo bad = slotManager.imageInfo(layout.getActiveSlot());
        if (bad != null) {
            badVersion = bad.getVersion();
        }
        String goodVersion = "?";
        ImageInfo good = slotManager.imageInfo(layout.getCommittedSlot());
        if (good != null) {
            goodVersion = good.getVersion();
        }
        slotManager.rollbackToCommitted();
        recordPendingOutcome(upgradeId, UpgradePhase.ROLLED_BACK, reason, detail,
                detail);
        decide("ROLLBACK(" + reason + "): 放弃版本 " + badVersion
                + "，已恢复到版本 " + goodVersion);
        log.warn("启动决策：{}（{}）", detail, reason);
    }

    private void recordPendingOutcome(String upgradeId, UpgradePhase phase,
                                      RollbackReason rollbackReason,
                                      String failureReason, String summary) {
        if (upgradeId == null) {
            log.warn("待确认升级缺少 upgradeId，无法更新历史；{}", summary);
            return;
        }
        UpgradeRecord record = history.byId(upgradeId);
        if (record == null) {
            log.warn("历史中找不到升级记录 {}，补建终态记录；{}", upgradeId, summary);
            record = new UpgradeRecord();
            record.setUpgradeId(upgradeId);
            record.setStartedAt(Instant.now());
        }
        record.setPhase(phase);
        record.setRollbackReason(rollbackReason);
        if (failureReason != null) {
            record.setFailureReason(failureReason);
        }
        record.setFinishedAt(Instant.now());
        history.update(record);
    }

    /** 供测试模拟“重新上电”：重新执行启动决策。 */
    public void simulateReboot() {
        onStartup();
    }

    /** 记录一次启动决策（供测试断言）。 */
    public String lastBootDecision() {
        return lastBootDecision;
    }

    private void decide(String decision) {
        this.lastBootDecision = decision;
        log.info("启动决策：{}", decision);
    }
}
