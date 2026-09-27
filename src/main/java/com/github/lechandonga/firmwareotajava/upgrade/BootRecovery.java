package com.github.lechandonga.firmwareotajava.upgrade;

import com.github.lechandonga.firmwareotajava.config.OtaProperties;
import com.github.lechandonga.firmwareotajava.model.DeviceState;
import com.github.lechandonga.firmwareotajava.model.FailureReason;
import com.github.lechandonga.firmwareotajava.model.UpdatePhase;
import com.github.lechandonga.firmwareotajava.model.UpgradeRecord;
import com.github.lechandonga.firmwareotajava.model.Version;
import com.github.lechandonga.firmwareotajava.storage.ChunkStagingStore;
import com.github.lechandonga.firmwareotajava.storage.FactoryInstaller;
import com.github.lechandonga.firmwareotajava.storage.SlotInstaller;
import com.github.lechandonga.firmwareotajava.storage.SlotLayout;
import com.github.lechandonga.firmwareotajava.storage.SlotSwitcher;
import com.github.lechandonga.firmwareotajava.storage.StateStore;
import com.github.lechandonga.firmwareotajava.storage.UpgradeHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 启动恢复：模拟设备掉电 / 进程被 kill -9 后重新上电。
 *
 * <p>恢复目标：无论上次死在哪个阶段，重启后都确定地落在一份完整可用版本上。
 * 依据 state.json（原子写）+ current 指针（原子 rename）+ 槽位 VERSION 提交标记：
 * <ul>
 *   <li>无状态：安装出厂固件；</li>
 *   <li>死在 DOWNLOADING/VERIFYING/INSTALLING/INTERRUPTED：
 *       current 仍指旧版本（新槽未提交），状态置为 INTERRUPTED 等待续传；</li>
 *   <li>死在 TESTING / 带待处理回滚原因：新版本未完成首启确认，
 *       判定为启动失败，自动回滚到最近 lastGoodVersion，并写 ROLLED_BACK 历史；</li>
 *   <li>死在 INSTALLED_PENDING_TEST（已切换、尚未自检）：
 *       同样视为“未能证明可启动”，保守回滚（宁可回到已知好版本）。</li>
 *   <li>CONFIRMED / IDLE / ROLLED_BACK / ROLLING_BACK：
 *       校验 current 槽完整后继续运行；ROLLING_BACK 收尾完成回滚。</li>
 * </ul>
 */
@Component
public class BootRecovery {

    private static final Logger log = LoggerFactory.getLogger(BootRecovery.class);

    private final OtaProperties properties;
    private final StateStore stateStore;
    private final SlotInstaller slotInstaller;
    private final ChunkStagingStore stagingStore;
    private final UpgradeHistory history;
    private final FactoryInstaller factoryInstaller;
    private final SlotSwitcher switcher;
    private final SlotLayout layout;

    public BootRecovery(OtaProperties properties, StateStore stateStore,
                        SlotInstaller slotInstaller, ChunkStagingStore stagingStore,
                        UpgradeHistory history, FactoryInstaller factoryInstaller,
                        SlotSwitcher switcher, SlotLayout layout) {
        this.properties = properties;
        this.stateStore = stateStore;
        this.slotInstaller = slotInstaller;
        this.stagingStore = stagingStore;
        this.history = history;
        this.factoryInstaller = factoryInstaller;
        this.switcher = switcher;
        this.layout = layout;
    }

    public Version recover() {
        DeviceState state = stateStore.load();
        if (state == null) {
            Version v = factoryInstaller.installIfAbsent();
            log.info("启动恢复：无持久状态，安装出厂固件 {}", v);
            String slot = switcher.currentSlotName() != null
                    ? switcher.currentSlotName() : SlotLayout.SLOT_A;
            DeviceState fresh = new DeviceState();
            fresh.activeSlot = slot;
            fresh.runningVersion = v;
            fresh.lastGoodVersion = v;
            fresh.phase = UpdatePhase.IDLE;
            stateStore.save(fresh);
            return v;
        }

        // 先确保 current 指向的槽完整；若损坏且 lastGood 在另一槽，立即回退
        String currentSlot = switcher.currentSlotName();
        if (currentSlot == null || !slotInstaller.isSlotComplete(currentSlot)) {
            return recoverBrokenPointer(state);
        }
        Version running = slotInstaller.installedVersion(currentSlot);
        state.activeSlot = currentSlot;
        state.runningVersion = running;

        UpdatePhase phase = state.phase == null ? UpdatePhase.IDLE : state.phase;
        log.info("启动恢复：上次阶段 {}，current={} running={}", phase, currentSlot, running);

        switch (phase) {
            case DOWNLOADING, VERIFYING, INSTALLING -> {
                // 切换尚未发生：继续运行旧版本，升级标记为中断、可续传
                markInterrupted(state);
            }
            case INSTALLED_PENDING_TEST, TESTING -> {
                // 新版本未经确认即重启（可能掉电/自检挂死）：回滚到 lastGood
                FailureReason reason = state.pendingRollbackReason != null
                        ? state.pendingRollbackReason
                        : (phase == UpdatePhase.TESTING
                                ? FailureReason.SELFTEST_TIMEOUT
                                : FailureReason.SELFTEST_FAILED);
                log.warn("启动恢复：新版本 {} 未完成首启确认（{}），执行自动回滚",
                        state.candidateVersion, reason);
                doRollback(state, reason, true);
            }
            case ROLLING_BACK -> {
                log.warn("启动恢复：回滚过程中掉电，完成回滚收尾");
                doRollback(state,
                        state.pendingRollbackReason != null
                                ? state.pendingRollbackReason : FailureReason.SELFTEST_FAILED,
                        true);
            }
            case INTERRUPTED -> {
                // 保持可续传状态，运行版本不变；清理非活动槽上任何半安装残留
                // （缺少 VERSION 提交标记的候选槽不被承认，也不允许脏数据残留）
                if (state.candidateSlot != null
                        && !state.candidateSlot.equals(state.activeSlot)
                        && !slotInstaller.isSlotComplete(state.candidateSlot)) {
                    slotInstaller.eraseSlot(state.candidateSlot);
                }
                log.info("启动恢复：升级此前已中断 upgradeId={}，等待续传",
                        state.activeUpgradeId);
                stateStore.save(state);
            }
            case IDLE, CONFIRMED, ROLLED_BACK -> {
                if (phase == UpdatePhase.CONFIRMED) {
                    // 正常启动到已确认版本，状态回到 IDLE
                    state.phase = UpdatePhase.IDLE;
                    stateStore.save(state);
                }
            }
        }
        return state.runningVersion;
    }

    private void markInterrupted(DeviceState state) {
        state.phase = UpdatePhase.INTERRUPTED;
        // 清理可能写了一半的候选槽（VERSION 未提交本就无效，这里确保不残留脏数据）
        if (state.candidateSlot != null && !state.candidateSlot.equals(state.activeSlot)) {
            slotInstaller.eraseSlot(state.candidateSlot);
        }
        log.info("升级已中断，可断点续传 upgradeId={} 已校验块={}/{}",
                state.activeUpgradeId, state.verifiedChunks, state.totalChunks);
        stateStore.save(state);
    }

    private Version recoverBrokenPointer(DeviceState state) {
        String goodSlot = SlotLayout.SLOT_A.equals(state.activeSlot)
                ? SlotLayout.SLOT_B : SlotLayout.SLOT_A;
        if (slotInstaller.isSlotComplete(goodSlot)) {
            switcher.switchTo(goodSlot);
            state.activeSlot = goodSlot;
            state.runningVersion = slotInstaller.installedVersion(goodSlot);
            state.phase = UpdatePhase.ROLLED_BACK;
            stateStore.save(state);
            log.error("启动恢复：current 槽损坏，已切回完整槽 {} version={}",
                    goodSlot, state.runningVersion);
            return state.runningVersion;
        }
        // 极端情况：两个槽都不可用，重装出厂固件到 A
        log.error("启动恢复：两个槽均不完整，重新安装出厂固件");
        return factoryInstaller.installIfAbsent();
    }

    /**
     * 自动/手动回滚：切回 lastGoodVersion 所在槽（不完整则以出厂兜底），
     * 写一条 ROLLED_BACK 记录（含可区分原因），不删除任何既有历史。
     *
     * @return 回滚后确定运行的版本
     */
    Version doRollback(DeviceState state, FailureReason reason, boolean automatic) {
        String goodSlot = SlotLayout.SLOT_A.equals(state.activeSlot)
                ? SlotLayout.SLOT_B : SlotLayout.SLOT_A;
        String candidateSlot = state.candidateSlot;
        Version candidate = state.candidateVersion;
        String upgradeId = state.activeUpgradeId;
        boolean resumed = state.resumed;
        int repairedChunks = state.repairedChunks;

        state.phase = UpdatePhase.ROLLING_BACK;
        state.pendingRollbackReason = reason;
        stateStore.save(state);

        Version target;
        if (slotInstaller.isSlotComplete(goodSlot)) {
            switcher.switchTo(goodSlot);
            target = slotInstaller.installedVersion(goodSlot);
        } else {
            // 无另一完整槽（理论上不会发生），出厂兜底
            target = factoryInstaller.installIfAbsent();
            goodSlot = switcher.currentSlotName();
        }

        // 擦除坏的候选槽，杜绝脏数据残留
        if (candidateSlot != null && !candidateSlot.equals(goodSlot)) {
            slotInstaller.eraseSlot(candidateSlot);
        }

        state.activeSlot = goodSlot;
        state.runningVersion = target;
        state.lastGoodVersion = target;
        state.phase = UpdatePhase.ROLLED_BACK;
        state.pendingRollbackReason = null;
        state.candidateSlot = null;
        state.candidateVersion = null;
        state.verifiedChunks = 0;
        state.totalChunks = 0;
        state.resumed = false;
        state.repairAttempts = 0;
        state.repairedChunks = 0;
        stateStore.save(state);

        // 回滚记录 append-only：只要存在被回滚的候选版本就记录，永不被后续升级覆盖
        if (candidate != null) {
            history.append(new UpgradeRecord(
                    upgradeId,
                    candidate,
                    "ROLLED_BACK",
                    null,
                    reason,
                    target,
                    Instant.now(),
                    Instant.now(),
                    resumed,
                    repairedChunks));
        }
        log.warn("{}回滚完成 candidate={} -> running={} reason={}",
                automatic ? "自动" : "手动", candidate, target, reason);

        // 清理候选升级暂存与进行中标记
        if (upgradeId != null) {
            stagingStore.cleanup(upgradeId);
            state.activeUpgradeId = null;
            state.activeTargetVersion = null;
            stateStore.save(state);
        }
        return target;
    }
}
