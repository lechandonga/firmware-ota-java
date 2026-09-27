package com.github.lechandonga.firmwareotajava.upgrade;

import com.github.lechandonga.firmwareotajava.config.OtaProperties;
import com.github.lechandonga.firmwareotajava.model.DeviceIdentity;
import com.github.lechandonga.firmwareotajava.model.DeviceState;
import com.github.lechandonga.firmwareotajava.model.FailureReason;
import com.github.lechandonga.firmwareotajava.model.FirmwareManifest;
import com.github.lechandonga.firmwareotajava.model.RejectReason;
import com.github.lechandonga.firmwareotajava.model.UpdatePhase;
import com.github.lechandonga.firmwareotajava.model.UpgradeRecord;
import com.github.lechandonga.firmwareotajava.model.UpgradeState;
import com.github.lechandonga.firmwareotajava.model.Version;
import com.github.lechandonga.firmwareotajava.pkg.PackageParser;
import com.github.lechandonga.firmwareotajava.pkg.PackageRejectedException;
import com.github.lechandonga.firmwareotajava.storage.ChunkStagingStore;
import com.github.lechandonga.firmwareotajava.storage.SlotInstaller;
import com.github.lechandonga.firmwareotajava.storage.SlotLayout;
import com.github.lechandonga.firmwareotajava.storage.SlotSwitcher;
import com.github.lechandonga.firmwareotajava.storage.StateStore;
import com.github.lechandonga.firmwareotajava.storage.UpgradeHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 升级编排服务。
 *
 * <p>关键保证：
 * <ul>
 *   <li>单设备串行：进程内一把锁，升级/回滚/状态迁移不会交错出半成品；</li>
 *   <li>同一 upgradeId 重复请求：进行中/中断中都复用既有进度，绝不重复写入；</li>
 *   <li>安装只写非活动槽，current 指针原子切换，掉电必落在完整版本；</li>
 *   <li>新槽首启自检失败/超时自动回滚，回滚原因可区分且历史 append-only；</li>
 *   <li>策略拒绝（降级/最低版本）不改变在位版本，也不写升级历史；</li>
 *   <li>超限/拒绝时释放全部临时资源。</li>
 * </ul>
 */
@Service
public class UpgradeService {

    private static final Logger log = LoggerFactory.getLogger(UpgradeService.class);

    private final OtaProperties properties;
    private final PackageParser packageParser;
    private final StateStore stateStore;
    private final ChunkStagingStore stagingStore;
    private final SlotInstaller slotInstaller;
    private final SlotSwitcher slotSwitcher;
    private final VersionPolicy versionPolicy;
    private final SelftestExecutor selftestExecutor;
    private final UpgradeHistory history;
    private final BootRecovery bootRecovery;
    private final SlotLayout layout;

    /**
     * 同一设备的全部升级操作串行化：同一目标不可能同时进行两个升级，
     * 升级/状态迁移与回滚也不会交错出半成品。
     */
    private final ReentrantLock lock = new ReentrantLock();

    /** 测试钩子：传输完这么多块后模拟中断；<0 表示不启用。 */
    private volatile int interruptAfterChunks = -1;

    public UpgradeService(OtaProperties properties, PackageParser packageParser,
                          StateStore stateStore, ChunkStagingStore stagingStore,
                          SlotInstaller slotInstaller, SlotSwitcher slotSwitcher,
                          VersionPolicy versionPolicy, SelftestExecutor selftestExecutor,
                          UpgradeHistory history, BootRecovery bootRecovery,
                          SlotLayout layout) {
        this.properties = properties;
        this.packageParser = packageParser;
        this.stateStore = stateStore;
        this.stagingStore = stagingStore;
        this.slotInstaller = slotInstaller;
        this.slotSwitcher = slotSwitcher;
        this.versionPolicy = versionPolicy;
        this.selftestExecutor = selftestExecutor;
        this.history = history;
        this.bootRecovery = bootRecovery;
        this.layout = layout;
    }

    public void enableWriteInterruptAfterChunks(int chunks) {
        this.interruptAfterChunks = chunks;
    }

    public void disableWriteInterrupt() {
        this.interruptAfterChunks = -1;
    }

    /**
     * 测试支持：模拟自检进行中进程被强杀（kill -9 / 断电）。
     * 立即中止自检，状态停留在 TESTING（无确认也无回滚结论），
     * 下一次启动恢复负责裁决回滚。
     */
    public void killDuringSelftest() {
        selftestExecutor.kill();
    }

    public UpgradeRecord upgradeFromPackage(String packageName) {
        lock.lock();
        try {
            return doUpgrade(packageName);
        } finally {
            lock.unlock();
        }
    }

    private UpgradeRecord doUpgrade(String packageName) {
        DeviceState state = requireState();
        Path packageDir = Path.of(properties.getPackageSourceDir())
                .resolve(packageName).toAbsolutePath().normalize();

        // 1) 来源与清单/内容/兼容性校验（拒绝原因可区分）
        FirmwareManifest manifest = null;
        try {
            packageParser.verifySourceExists(packageDir);
            manifest = packageParser.parseManifest(packageDir);
            Path payload = packageDir.resolve("payload.bin");
            packageParser.verifyPayloadPresent(payload);
            packageParser.verifyPackageSize(payload, manifest);
            packageParser.verifyPayloadFile(payload, manifest);
            packageParser.verifyChunking(manifest);
            packageParser.verifyCompatibility(manifest,
                    new DeviceIdentity(properties.getDeviceModel(),
                            properties.getHardwareVersion()));
        } catch (PackageRejectedException rejected) {
            return onRejected(state, manifest, rejected.getReason(),
                    rejected.getMessage());
        }

        // 2) 重复请求幂等：
        //    - 该 upgradeId 已成功：直接返回既有记录，绝不重复写入；
        //    - 该 upgradeId 上次中断：断点续传（复用已通过校验的块）。
        //    进行中的并发请求由全局锁串行化，不会出现两个升级同时写同一目标。
        UpgradeRecord existing = latestRecordOf(manifest.upgradeId());
        if (existing != null && "SUCCEEDED".equals(existing.outcome())) {
            log.info("升级 {} 已成功，重复请求幂等返回，不重复写入",
                    manifest.upgradeId());
            return existing;
        }
        boolean resumed = isResumedUpgrade(state, manifest.upgradeId());
        if (resumed) {
            log.info("升级 {} 为中断后续传，复用已通过校验的 {} 个块",
                    manifest.upgradeId(), state.verifiedChunks);
        }

        // 3) 版本策略（拒绝不改变在位版本与历史）
        RejectReason policyReject =
                versionPolicy.evaluate(state.runningVersion, manifest.version());
        if (policyReject != null) {
            // 明确要求：策略拒绝不改变在位版本与升级历史
            state.lastRejectReason = policyReject;
            state.lastRejectedUpgradeId = manifest.upgradeId();
            stateStore.save(state);
            log.warn("升级被版本策略拒绝 reason={} current={} target={}（不写历史、不改在位版本）",
                    policyReject, state.runningVersion, manifest.version());
            return null;
        }

        // 4) 存储占用上限校验（两槽 + 本次暂存不得超过预算）
        if (storageBudgetExceeded(manifest)) {
            return onRejected(state, manifest, RejectReason.STORAGE_LIMIT_EXCEEDED,
                    "存储占用将超过上限 " + properties.getMaxStorageBytes());
        }

        Instant startedAt = Instant.now();
        try {
            // 初始化/续传升级状态
            beginOrResumeUpgrade(state, manifest, resumed);

            // 4.5) 续传前审计：设备存储不可靠（坏块/翻位/半截写入）时，
            //      上次已暂存的数据可能已损坏。剔除不可用块，只重传这些块，
            //      未受影响的部分不重复处理；重处理次数受上限约束。
            if (resumed) {
                java.util.List<Integer> evicted =
                        stagingStore.evictInvalidChunks(manifest.upgradeId(), manifest);
                if (!evicted.isEmpty()) {
                    UpgradeRecord failed =
                            registerRepair(state, manifest, evicted.size(), startedAt);
                    if (failed != null) {
                        return failed;
                    }
                }
            }

            // 5) 断点续传：逐块读取来源 -> 逐块哈希校验 -> 暂存。
            //    组装复核发现暂存数据损坏时，审计剔除坏块后回到传输继续，
            //    避免“进度看着快满却永远推不动”。
            Path assembled = null;
            while (assembled == null) {
                try {
                    transferChunks(packageDir, state, manifest);
                } catch (TransferInterruptedException interrupted) {
                    state.phase = UpdatePhase.INTERRUPTED;
                    stateStore.save(state);
                    log.warn("传输中断 upgradeId={} 已完成 {}/{} 块，已校验数据保留，其余重传",
                            manifest.upgradeId(), state.verifiedChunks, state.totalChunks);
                    return null;
                } catch (PackageRejectedRuntime tampered) {
                    return onRejected(state, manifest, tampered.reason, tampered.getMessage());
                }

                // 6) 组装（含逐块复核 + 整体哈希校验）
                state.phase = UpdatePhase.INSTALLING;
                stateStore.save(state);
                try {
                    assembled = stagingStore.assemble(manifest.upgradeId(), manifest);
                } catch (IllegalStateException corrupt) {
                    log.warn("组装复核发现已暂存数据不可用（{}），审计剔除后重处理",
                            corrupt.getMessage());
                    java.util.List<Integer> evicted =
                            stagingStore.evictInvalidChunks(manifest.upgradeId(), manifest);
                    UpgradeRecord failed =
                            registerRepair(state, manifest, evicted.size(), startedAt);
                    if (failed != null) {
                        return failed;
                    }
                }
            }

            // 7) 安装到非活动槽（活动槽全程只读运行）
            slotInstaller.eraseSlot(state.candidateSlot);
            slotInstaller.install(state.candidateSlot, manifest, assembled);

            // 8) 原子切换（提交点）：此后运行新版本，等待首启自检确认
            slotSwitcher.switchTo(state.candidateSlot);
            state.phase = UpdatePhase.INSTALLED_PENDING_TEST;
            state.runningVersion = manifest.version();
            state.activeSlot = state.candidateSlot;
            stateStore.save(state);

            // 8) 首启自检
            state.phase = UpdatePhase.TESTING;
            state.pendingRollbackReason = FailureReason.SELFTEST_TIMEOUT;
            stateStore.save(state);
            FailureReason failure = selftestExecutor.run(
                    manifest, properties.getSelftestTimeoutMillis());

            if (failure != null) {
                // 9a) 自检失败/超时：自动回滚到 lastGood
                log.warn("首启自检未通过（{}），自动回滚", failure);
                bootRecovery.doRollback(state, failure, true);
                // doRollback 已 append ROLLED_BACK 记录（含可区分原因）
                return latestRecordOf(manifest.upgradeId());
            }            // 9b) 自检通过：确认新版本为 lastGood，释放暂存
            int repairedChunks = state.repairedChunks;
            state.phase = UpdatePhase.CONFIRMED;
            state.pendingRollbackReason = null;
            state.lastGoodVersion = manifest.version();
            state.candidateSlot = null;
            state.candidateVersion = null;
            state.verifiedChunks = state.totalChunks = 0;
            state.activeUpgradeId = null;
            state.activeTargetVersion = null;
            state.resumed = false;
            state.repairAttempts = 0;
            state.repairedChunks = 0;
            stateStore.save(state);
            stagingStore.cleanup(manifest.upgradeId());
            UpgradeRecord ok = history.append(new UpgradeRecord(
                    manifest.upgradeId(), manifest.version(), "SUCCEEDED",
                    null, null, null, startedAt, Instant.now(), resumed,
                    repairedChunks));
            log.info("升级成功并确认 version={} upgradeId={} resumed={}",
                    manifest.version(), manifest.upgradeId(), resumed);
            // 确认完成，回到稳态
            state.phase = UpdatePhase.IDLE;
            stateStore.save(state);
            return ok;
        } catch (PackageRejectedRuntime tampered) {
            return onRejected(state, manifest, tampered.reason, tampered.getMessage());
        } catch (SelftestExecutor.SelftestKilledException killed) {
            // 模拟自检中掉电/强杀：不下任何结论，状态保持 TESTING 落盘，
            // 由下一次启动恢复裁决回滚
            log.error("自检过程中进程被强制终止（模拟），状态保持 TESTING 等待启动恢复");
            state.phase = UpdatePhase.TESTING;
            stateStore.save(state);
            return null;
        } catch (RuntimeException e) {
            // 安装/切换/自检阶段的意外异常：切换尚未提交则在位版本不受影响；
            // 已提交进入自检的异常不应走到这里（失败原因已被显式处理）。
            log.error("升级异常，安装未提交，在位版本不变 upgradeId={}",
                    manifest.upgradeId(), e);
            state.phase = UpdatePhase.INTERRUPTED;
            stateStore.save(state);
            return null;
        }
    }

    private void transferChunks(Path packageDir, DeviceState state,
                                FirmwareManifest manifest) {
        state.phase = UpdatePhase.DOWNLOADING;
        stateStore.save(state);
        Path payload = packageDir.resolve("payload.bin");
        int total = manifest.chunkSha256().size();
        try (var channel = Files.newByteChannel(payload)) {
            for (int i = 0; i < total; i++) {
                if (stagingStore.isChunkVerified(manifest.upgradeId(), i)) {
                    // 续传/重处理：跳过位图中已通过校验的块，绝不重复处理；
                    // 被审计剔除的坏块不在位图中，会在此处重新传输
                    continue;
                }
                long offset = (long) i * manifest.chunkSize();
                int size = (int) Math.min(manifest.chunkSize(),
                        manifest.payloadSize() - offset);
                byte[] buffer = readFully(channel, offset, size);
                String expected = manifest.chunkSha256().get(i);
                boolean ok = stagingStore.stageChunk(
                        manifest.upgradeId(), i, buffer, expected);
                if (!ok) {
                    // 单块哈希失败：该块未进入位图、未被当作有效数据；
                    // 重新读取源并重传一次，再次失败则按内容篡改拒绝整包
                    buffer = readFully(channel, offset, size);
                    ok = stagingStore.stageChunk(
                            manifest.upgradeId(), i, buffer, expected);
                    if (!ok) {
                        throw new PackageRejectedRuntime(RejectReason.PAYLOAD_TAMPERED,
                                "块 " + i + " 重试后仍校验失败");
                    }
                }
                state.verifiedChunks = stagingStore.verifiedChunkCount(
                        manifest.upgradeId());
                stateStore.save(state);

                // 测试钩子：在指定块之后模拟写入中断（当前块未完成则不进位图）
                if (interruptAfterChunks >= 0
                        && state.verifiedChunks >= interruptAfterChunks
                        && state.verifiedChunks < total) {
                    throw new TransferInterruptedException(
                            state.verifiedChunks, "模拟写入中断");
                }
            }
        } catch (TransferInterruptedException e) {
            throw e;
        } catch (PackageRejectedRuntime e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalStateException("读取升级包载荷失败", e);
        }
        state.phase = UpdatePhase.VERIFYING;
        stateStore.save(state);
    }

    private static byte[] readFully(java.nio.channels.SeekableByteChannel channel,
                                    long offset, int size) throws IOException {
        channel.position(offset);
        byte[] data = new byte[size];
        int read = 0;
        while (read < size) {
            int n = channel.read(java.nio.ByteBuffer.wrap(data, read, size - read));
            if (n < 0) {
                throw new IOException("载荷提前结束 offset=" + offset);
            }
            read += n;
        }
        return data;
    }

    private void beginOrResumeUpgrade(DeviceState state, FirmwareManifest manifest,
                                      boolean resumed) {
        String candidate = layout.otherSlotName(state.activeSlot);
        state.activeUpgradeId = manifest.upgradeId();
        state.activeTargetVersion = manifest.version();
        state.candidateSlot = candidate;
        state.candidateVersion = manifest.version();
        state.totalChunks = manifest.chunkSha256().size();
        state.resumed = resumed;
        if (!resumed) {
            state.verifiedChunks = 0;
            state.repairAttempts = 0;
            state.repairedChunks = 0;
        } else {
            state.verifiedChunks =
                    stagingStore.verifiedChunkCount(manifest.upgradeId());
        }
        state.pendingRollbackReason = null;
        state.phase = UpdatePhase.DOWNLOADING;
        stateStore.save(state);
    }

    /**
     * 登记一次“已暂存数据不可用并重处理”：累计轮次与块数（跨重启持久化），
     * 进度回退到真实可用块数。超过 firmware.ota.max-repair-attempts 上限时
     * 按 STAGED_DATA_REPAIR_EXHAUSTED 失败收场并返回失败记录，否则返回 null。
     */
    private UpgradeRecord registerRepair(DeviceState state, FirmwareManifest manifest,
                                         int evictedCount, Instant startedAt) {
        state.repairAttempts++;
        state.repairedChunks += evictedCount;
        state.verifiedChunks = stagingStore.verifiedChunkCount(manifest.upgradeId());
        stateStore.save(state);
        log.warn("暂存数据重处理：剔除 {} 个不可用块，第 {}/{} 次 upgradeId={}",
                evictedCount, state.repairAttempts,
                properties.getMaxRepairAttempts(), manifest.upgradeId());
        if (state.repairAttempts > properties.getMaxRepairAttempts()) {
            return failUpgrade(state, manifest, startedAt);
        }
        return null;
    }

    /**
     * 重处理次数超限的失败收场：写一条 outcome=FAILED、原因可区分的历史记录，
     * 释放本次升级占用的全部临时空间，复位进行中状态；在位版本继续运行。
     */
    private UpgradeRecord failUpgrade(DeviceState state, FirmwareManifest manifest,
                                      Instant startedAt) {
        log.error("暂存数据重处理次数超过上限（{}），升级失败 upgradeId={}",
                properties.getMaxRepairAttempts(), manifest.upgradeId());
        int repaired = state.repairedChunks;
        boolean resumed = state.resumed;
        stagingStore.cleanup(manifest.upgradeId());
        state.phase = UpdatePhase.IDLE;
        state.activeUpgradeId = null;
        state.activeTargetVersion = null;
        state.candidateSlot = null;
        state.candidateVersion = null;
        state.verifiedChunks = 0;
        state.totalChunks = 0;
        state.resumed = false;
        state.repairAttempts = 0;
        state.repairedChunks = 0;
        stateStore.save(state);
        return history.append(new UpgradeRecord(
                manifest.upgradeId(), manifest.version(), "FAILED",
                null, FailureReason.STAGED_DATA_REPAIR_EXHAUSTED, null,
                startedAt, Instant.now(), resumed, repaired));
    }

    private boolean isResumedUpgrade(DeviceState state, String upgradeId) {
        return upgradeId != null && upgradeId.equals(state.activeUpgradeId)
                && (state.phase == UpdatePhase.INTERRUPTED
                        || state.phase == UpdatePhase.DOWNLOADING
                        || state.phase == UpdatePhase.VERIFYING
                        || state.phase == UpdatePhase.INSTALLING);
    }

    private UpgradeRecord onRejected(DeviceState state, FirmwareManifest manifest,
                                     RejectReason reason, String message) {
        log.warn("升级被拒绝 reason={} detail={}", reason, message);
        state.lastRejectReason = reason;
        state.lastRejectedUpgradeId =
                manifest != null ? manifest.upgradeId() : state.lastRejectedUpgradeId;
        stateStore.save(state);
        // 拒绝后释放任何为该包产生的临时资源；在位版本继续运行
        if (manifest != null) {
            stagingStore.cleanup(manifest.upgradeId());
        }
        return history.append(new UpgradeRecord(
                manifest != null ? manifest.upgradeId() : "unknown",
                manifest != null ? manifest.version() : null,
                "REJECTED", reason, null, null, Instant.now(), Instant.now(),
                false, 0));
    }

    private boolean storageBudgetExceeded(FirmwareManifest manifest) {
        long used = dirSize(layout.slotsDir()) + dirSize(layout.root().resolve("staging"))
                + manifest.payloadSize() + manifest.payloadSize(); // 暂存副本 + 新槽
        return used > properties.getMaxStorageBytes();
    }

    private static long dirSize(Path dir) {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    public UpgradeRecord rollback() {
        lock.lock();
        try {
            DeviceState state = requireState();
            String upgradeId = state.activeUpgradeId;
            log.info("手动回滚请求 current={} lastGood={} upgradeId={}",
                    state.runningVersion, state.lastGoodVersion, upgradeId);
            bootRecovery.doRollback(state, FailureReason.SELFTEST_FAILED, false);
            return latestRecordOf(upgradeId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 状态查询不与升级互斥：直接读取原子持久化的 state.json。
     * 每次状态迁移都先落盘，因此读到的永远是某个完整一致的迁移点，
     * 升级/回滚进行中也能查询到可解释的中间状态，而不会看到半完成数据。
     */
    public UpgradeState currentState() {
        DeviceState s = stateStore.load();
        if (s == null) {
            throw new IllegalStateException("设备尚未完成启动恢复");
        }
        long total = s.totalChunks;
        long verified = s.verifiedChunks;
        int percent = total == 0 ? 0 : (int) (verified * 100 / total);
        return new UpgradeState(
                s.phase, s.activeUpgradeId, s.activeTargetVersion,
                s.runningVersion, s.lastGoodVersion, s.candidateVersion,
                s.activeSlot, s.candidateSlot, verified, total, percent,
                s.lastRejectReason, s.lastRejectedUpgradeId,
                s.pendingRollbackReason, s.repairAttempts, s.repairedChunks);
    }

    public List<UpgradeRecord> history() {
        return history.all();
    }

    private DeviceState requireState() {
        DeviceState state = stateStore.load();
        if (state == null) {
            throw new IllegalStateException("设备尚未完成启动恢复");
        }
        return state;
    }

    private UpgradeRecord latestRecordOf(String upgradeId) {
        if (upgradeId == null) {
            return null;
        }
        return history.all().stream()
                .filter(r -> upgradeId.equals(r.upgradeId()))
                .reduce((a, b) -> b).orElse(null);
    }

    /** 包校验运行时异常（传输阶段检测到篡改时使用）。 */
    private static class PackageRejectedRuntime extends RuntimeException {
        final RejectReason reason;

        PackageRejectedRuntime(RejectReason reason, String message) {
            super(message);
            this.reason = reason;
        }
    }
}
