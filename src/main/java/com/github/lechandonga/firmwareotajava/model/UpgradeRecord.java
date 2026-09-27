package com.github.lechandonga.firmwareotajava.model;

import java.time.Instant;

/**
 * 一条持久化升级记录（append-only，回滚记录不被后续升级覆盖）。
 * outcome = SUCCEEDED / REJECTED / ROLLED_BACK / FAILED；
 * rejectReason 与 failureReason 相互可区分、可空；
 * repairedChunks 记录本次升级期间被判定不可用并重处理的暂存块数（0 表示未发生）。
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
