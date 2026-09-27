package com.github.lechandonga.firmwareotajava.model;

/**
 * 当前升级状态快照（供状态查询）。
 */
public record UpgradeState(
        UpdatePhase phase,
        String activeUpgradeId,
        Version activeTargetVersion,
        Version currentRunningVersion,
        Version lastGoodVersion,
        Version candidateVersion,
        String activeSlot,
        String candidateSlot,
        long verifiedChunks,
        long totalChunks,
        int progressPercent,
        RejectReason lastRejectReason,
        String lastRejectedUpgradeId,
        FailureReason pendingRollbackReason,
        int repairAttempts,
        int repairedChunks) {
}
