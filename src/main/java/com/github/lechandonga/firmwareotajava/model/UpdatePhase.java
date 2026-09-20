package com.github.lechandonga.firmwareotajava.model;

/**
 * 升级状态机阶段（状态可解释）。
 * IDLE 无升级；INTERRUPTED 上次升级在传输/写入/校验阶段中断；
 * INSTALLED_PENDING_TEST 新槽位安装完成、原子切换完成，等待首启自检；
 * TESTING 正在首启自检；CONFIRMED 已确认；ROLLING_BACK 正在回滚。
 */
public enum UpdatePhase {
    IDLE,
    DOWNLOADING,
    INTERRUPTED,
    VERIFYING,
    INSTALLING,
    INSTALLED_PENDING_TEST,
    TESTING,
    CONFIRMED,
    ROLLING_BACK,
    ROLLED_BACK
}
