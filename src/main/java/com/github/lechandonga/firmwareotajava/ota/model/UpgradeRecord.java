package com.github.lechandonga.firmwareotajava.ota.model;

import java.time.Instant;

/**
 * 升级历史条目（追加式，回滚记录不会因后续升级而丢失）。
 * phase 为终态时携带可区分原因：
 * REJECTED -> rejectReason；ROLLED_BACK -> rollbackReason；
 * 写入/自检失败 -> failureReason（文本）。
 */
public class UpgradeRecord {

    private String upgradeId;
    private String fromVersion;
    private String targetVersion;
    private String hardwareModel;
    private String hardwareVersion;
    /** 安装目标槽位（INSTALLING 及之后阶段用于掉电恢复）。 */
    private String targetSlot;
    private UpgradePhase phase;
    private RejectReason rejectReason;
    private RollbackReason rollbackReason;
    private String failureReason;
    private Instant startedAt;
    private Instant finishedAt;

    public UpgradeRecord() {
    }

    public String getUpgradeId() {
        return upgradeId;
    }

    public void setUpgradeId(String upgradeId) {
        this.upgradeId = upgradeId;
    }

    public String getFromVersion() {
        return fromVersion;
    }

    public void setFromVersion(String fromVersion) {
        this.fromVersion = fromVersion;
    }

    public String getTargetVersion() {
        return targetVersion;
    }

    public void setTargetVersion(String targetVersion) {
        this.targetVersion = targetVersion;
    }

    public String getHardwareModel() {
        return hardwareModel;
    }

    public void setHardwareModel(String hardwareModel) {
        this.hardwareModel = hardwareModel;
    }

    public String getHardwareVersion() {
        return hardwareVersion;
    }

    public void setHardwareVersion(String hardwareVersion) {
        this.hardwareVersion = hardwareVersion;
    }

    public String getTargetSlot() {
        return targetSlot;
    }

    public void setTargetSlot(String targetSlot) {
        this.targetSlot = targetSlot;
    }

    public UpgradePhase getPhase() {
        return phase;
    }

    public void setPhase(UpgradePhase phase) {
        this.phase = phase;
    }

    public RejectReason getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(RejectReason rejectReason) {
        this.rejectReason = rejectReason;
    }

    public RollbackReason getRollbackReason() {
        return rollbackReason;
    }

    public void setRollbackReason(RollbackReason rollbackReason) {
        this.rollbackReason = rollbackReason;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }
}
