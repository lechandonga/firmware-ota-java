package com.github.lechandonga.firmwareotajava.ota.model;

/**
 * 回滚的可区分原因。
 */
public enum RollbackReason {
    SELF_TEST_FAILED,
    SELF_TEST_TIMEOUT,
    IMAGE_CORRUPT_ON_BOOT,
    MANUAL
}
