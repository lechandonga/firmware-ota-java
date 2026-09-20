package com.github.lechandonga.firmwareotajava.ota.model;

/**
 * 对外可查询的设备与升级状态。
 */
public record DeviceStatus(
        String deviceModel,
        String hardwareVersion,
        String activeSlot,
        String activeVersion,
        String pendingSlot,
        String pendingVersion,
        UpgradePhase activeUpgradePhase,
        int bootCount,
        int selfTestAttempts,
        long storageUsedBytes,
        long storageMaxBytes) {
}
