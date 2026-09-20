package com.github.lechandonga.firmwareotajava.ota.model;

/**
 * 升级生命周期状态。
 * REJECTED/COMMITTED/ROLLED_BACK 为终态；STAGING/STAGED/INSTALLING/PENDING_SELF_TEST 为中间态。
 */
public enum UpgradePhase {
    REJECTED,
    STAGING,
    STAGED,
    INSTALLING,
    PENDING_SELF_TEST,
    COMMITTED,
    ROLLED_BACK
}
