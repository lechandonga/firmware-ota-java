package com.github.lechandonga.firmwareotajava.model;

/** 新版本首启失败/回滚/升级失败的可区分原因。 */
public enum FailureReason {
    SELFTEST_FAILED,
    SELFTEST_TIMEOUT,
    /** 已暂存数据反复损坏，自动重处理次数超过上限，升级失败收场。 */
    STAGED_DATA_REPAIR_EXHAUSTED
}
