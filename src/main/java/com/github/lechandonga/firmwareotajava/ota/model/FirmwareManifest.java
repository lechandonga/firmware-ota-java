package com.github.lechandonga.firmwareotajava.ota.model;

import java.util.List;

/**
 * 升级包清单。
 *
 * @param manifestVersion    清单格式版本
 * @param hardwareModel      目标设备型号
 * @param hardwareVersion    目标硬件版本
 * @param version            固件版本（语义化版本）
 * @param payloadSize        负载字节数
 * @param payloadSha256      完整负载 SHA-256（十六进制）
 * @param chunkSize          分块大小（字节，最后一块可更小）
 * @param chunkSha256        每个分块的 SHA-256（十六进制，按顺序）
 * @param minCompatibleVersion 最低兼容版本（可空）
 * @param selfTest           启动自检规格
 */
public record FirmwareManifest(
        int manifestVersion,
        String hardwareModel,
        String hardwareVersion,
        String version,
        long payloadSize,
        String payloadSha256,
        int chunkSize,
        List<String> chunkSha256,
        String minCompatibleVersion,
        SelfTestSpec selfTest) {

    public static final int CURRENT_MANIFEST_VERSION = 1;
}
