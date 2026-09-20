package com.github.lechandonga.firmwareotajava.model;

import java.util.List;

/**
 * 升级包清单 manifest.json。
 * payloadSha256 为固件内容的 SHA-256（十六进制小写），用于内容篡改校验；
 * chunkSize/chunkSha256 用于分块断点续传的逐块校验；
 * selftestResult = SUCCESS / FAIL / TIMEOUT，供设备首启自检模拟使用。
 */
public record FirmwareManifest(
        String upgradeId,
        Version version,
        String model,
        String hardwareVersion,
        long payloadSize,
        String payloadSha256,
        int chunkSize,
        List<String> chunkSha256,
        String selftestResult,
        long selftestDurationMillis) {
}
