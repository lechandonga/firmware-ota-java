package com.github.lechandonga.firmwareotajava.ota.model;

/**
 * 新版本启动自检的模拟方式：
 * ALWAYS_OK 恒通过；MARKER 要求负载中包含标记；SLOW 模拟自检卡死超时。
 */
public enum SelfTestMode {
    ALWAYS_OK,
    MARKER,
    SLOW
}
