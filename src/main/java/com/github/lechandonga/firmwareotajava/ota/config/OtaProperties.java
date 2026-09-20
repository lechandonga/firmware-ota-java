package com.github.lechandonga.firmwareotajava.ota.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OTA 可配置项（前缀 ota）。
 */
@ConfigurationProperties(prefix = "ota")
public class OtaProperties {

    /** 设备存储根目录（模拟设备存储）。 */
    private String storageRoot = "build/device";

    /** 本设备型号。 */
    private String deviceModel = "DEMO-DEVICE";

    /** 本设备硬件版本。 */
    private String hardwareVersion = "1.0";

    /** 出厂固件版本（空存储时预置）。 */
    private String factoryVersion = "1.0.0";

    /** 出厂固件内容（文本，写入 payload.bin）。 */
    private String factoryPayload = "factory firmware 1.0.0\n";

    /** 单个升级包大小上限（字节）。 */
    private long maxPackageBytes = 64L * 1024 * 1024;

    /** 设备存储占用上限（字节）。 */
    private long maxStorageBytes = 256L * 1024 * 1024;

    /** 是否允许降级。 */
    private boolean allowDowngrade = false;

    /** 最低兼容版本（空表示不限制）。 */
    private String minCompatibleVersion = "";

    /** 续传分块大小（字节）。 */
    private int chunkSizeBytes = 64 * 1024;

    public String getStorageRoot() {
        return storageRoot;
    }

    public void setStorageRoot(String storageRoot) {
        this.storageRoot = storageRoot;
    }

    public String getDeviceModel() {
        return deviceModel;
    }

    public void setDeviceModel(String deviceModel) {
        this.deviceModel = deviceModel;
    }

    public String getHardwareVersion() {
        return hardwareVersion;
    }

    public void setHardwareVersion(String hardwareVersion) {
        this.hardwareVersion = hardwareVersion;
    }

    public String getFactoryVersion() {
        return factoryVersion;
    }

    public void setFactoryVersion(String factoryVersion) {
        this.factoryVersion = factoryVersion;
    }

    public String getFactoryPayload() {
        return factoryPayload;
    }

    public void setFactoryPayload(String factoryPayload) {
        this.factoryPayload = factoryPayload;
    }

    public long getMaxPackageBytes() {
        return maxPackageBytes;
    }

    public void setMaxPackageBytes(long maxPackageBytes) {
        this.maxPackageBytes = maxPackageBytes;
    }

    public long getMaxStorageBytes() {
        return maxStorageBytes;
    }

    public void setMaxStorageBytes(long maxStorageBytes) {
        this.maxStorageBytes = maxStorageBytes;
    }

    public boolean isAllowDowngrade() {
        return allowDowngrade;
    }

    public void setAllowDowngrade(boolean allowDowngrade) {
        this.allowDowngrade = allowDowngrade;
    }

    public String getMinCompatibleVersion() {
        return minCompatibleVersion;
    }

    public void setMinCompatibleVersion(String minCompatibleVersion) {
        this.minCompatibleVersion = minCompatibleVersion;
    }

    public int getChunkSizeBytes() {
        return chunkSizeBytes;
    }

    public void setChunkSizeBytes(int chunkSizeBytes) {
        this.chunkSizeBytes = chunkSizeBytes;
    }
}
