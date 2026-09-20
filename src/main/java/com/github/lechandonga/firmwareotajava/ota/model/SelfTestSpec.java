package com.github.lechandonga.firmwareotajava.ota.model;

/**
 * 新版本启动自检规格（来自升级清单）。
 *
 * @param mode        自检模式
 * @param timeoutMs   自检超时时间（毫秒）
 * @param requiredMarker MARKER 模式下负载中必须包含的字节序列（UTF-8）
 */
public record SelfTestSpec(SelfTestMode mode, long timeoutMs, String requiredMarker) {

    public static SelfTestSpec alwaysOk() {
        return new SelfTestSpec(SelfTestMode.ALWAYS_OK, 0, null);
    }
}
