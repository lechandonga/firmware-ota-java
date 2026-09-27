package com.github.lechandonga.firmwareotajava.model;

/**
 * 设备持久状态（state.json，原子写入）。
 * 字段序列化保持简单可变 POJO，配合 Jackson 持久化。
 */
public class DeviceState {
    /** 当前活动槽位 "A" / "B"。 */
    public String activeSlot;
    /** 活动槽位版本（当前运行版本）。 */
    public Version runningVersion;
    /** 最近一次确认可正常启动的版本。 */
    public Version lastGoodVersion;
    /** 候选槽位（安装新固件的槽位）。 */
    public String candidateSlot;
    public Version candidateVersion;
    /** 当前升级状态机阶段。 */
    public UpdatePhase phase = UpdatePhase.IDLE;
    /** 正在进行/最近一次升级的标识与目标版本。 */
    public String activeUpgradeId;
    public Version activeTargetVersion;
    /** 已通过校验的块数 / 总块数。 */
    public int verifiedChunks;
    public int totalChunks;
    /** 升级是否由断点恢复继续。 */
    public boolean resumed;
    /**
     * 本次升级内“已暂存数据被判定不可用并重处理”的轮次（跨重启持久化，
     * 超过 firmware.ota.max-repair-attempts 即失败收场）。
     */
    public int repairAttempts;
    /** 本次升级内累计被判定不可用并重处理的块数（可观测）。 */
    public int repairedChunks;
    /** 待启动后处理的回滚原因（TESTING 失败/掉电恢复时使用）。 */
    public FailureReason pendingRollbackReason;
    /** 最近一次拒绝原因（供查询，区分不同拒绝场景）。 */
    public RejectReason lastRejectReason;
    public String lastRejectedUpgradeId;

    public DeviceState() {
    }
}
