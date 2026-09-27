package com.github.lechandonga.firmwareotajava.model;

import java.time.Instant;

/**
 * 一条持久化升级记录（append-only，回滚记录不被后续升级覆盖）。
 * outcome = SUCCEEDED / REJECTED / ROLLED_BACK / FAILED；
 * rejectReason 与 failureReason 相互可区分、可空。
 * repairedChunks 记录本次升级中因设备上已保存数据不可用而被重新处理的块数
 * （0 表示未发生重处理），与“升级失败/被拒绝”是相互独立、可区分的信息。
 */
public record UpgradeRecord(
        String upgradeId,
        Version targetVersion,
        String outcome,
        RejectReason rejectReason,
        FailureReason failureReason,
        Version rolledBackTo,
        Instant startedAt,
        Instant finishedAt,
        boolean resumed,
        int repairedChunks) {
}
