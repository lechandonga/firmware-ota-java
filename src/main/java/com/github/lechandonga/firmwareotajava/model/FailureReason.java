package com.github.lechandonga.firmwareotajava.model;

/** 新版本首启失败/回滚/升级失败的可区分原因。 */
public enum FailureReason {
    SELFTEST_FAILED,
    SELFTEST_TIMEOUT,
    /** 设备上已暂存的升级数据损坏，自动重处理超过配置上限，升级以失败收场。 */
    STAGING_DATA_UNRECOVERABLE
}
