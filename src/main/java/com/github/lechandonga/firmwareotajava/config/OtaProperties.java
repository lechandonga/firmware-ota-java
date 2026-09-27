package com.github.lechandonga.firmwareotajava.config;

import com.github.lechandonga.firmwareotajava.model.Version;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** OTA 可配置项（前缀 firmware.ota）。 */
@ConfigurationProperties(prefix = "firmware.ota")
public class OtaProperties {

    /** 设备根存储目录（模拟设备存储）。 */
    private String storageDir = "data/device-storage";
    /** 升级包来源目录（模拟升级服务器/本地来源）。 */
    private String packageSourceDir = "data/packages";
    /** 设备型号。 */
    private String deviceModel = "DEVICE-X1";
    /** 设备硬件版本。 */
    private String hardwareVersion = "hw-1.0";
    /** 出厂固件版本（首次启动时安装为初始在位版本）。 */
    private String factoryVersion = "1.0.0";
    /** 是否允许降级。 */
    private boolean allowDowngrade = false;
    /** 最低兼容版本，低于该版本的升级一律拒绝；为空表示不限制。 */
    private String minimumCompatibleVersion = null;
    /** 单个升级包大小上限（字节）。 */
    private long maxPackageBytes = 64L * 1024 * 1024;
    /** 设备存储总占用上限（字节）。 */
    private long maxStorageBytes = 256L * 1024 * 1024;
    /** 分块大小（字节），用于断点续传与逐块校验。 */
    private int chunkSize = 64 * 1024;
    /** 首启自检超时（毫秒），超时按失败处理并自动回滚。 */
    private long selftestTimeoutMillis = 5000;
    /**
     * 同一升级内“已暂存数据损坏自动重处理”的次数上限。
     * 超过上限按 STAGED_DATA_REPAIR_EXHAUSTED 失败收场并释放暂存。
     */
    private int maxRepairAttempts = 3;

    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
    public String getPackageSourceDir() { return packageSourceDir; }
    public void setPackageSourceDir(String packageSourceDir) { this.packageSourceDir = packageSourceDir; }
    public String getDeviceModel() { return deviceModel; }
    public void setDeviceModel(String deviceModel) { this.deviceModel = deviceModel; }
    public String getHardwareVersion() { return hardwareVersion; }
    public void setHardwareVersion(String hardwareVersion) { this.hardwareVersion = hardwareVersion; }
    public String getFactoryVersion() { return factoryVersion; }
    public void setFactoryVersion(String factoryVersion) { this.factoryVersion = factoryVersion; }
    public boolean isAllowDowngrade() { return allowDowngrade; }
    public void setAllowDowngrade(boolean allowDowngrade) { this.allowDowngrade = allowDowngrade; }
    public String getMinimumCompatibleVersion() { return minimumCompatibleVersion; }
    public void setMinimumCompatibleVersion(String minimumCompatibleVersion) { this.minimumCompatibleVersion = minimumCompatibleVersion; }
    public long getMaxPackageBytes() { return maxPackageBytes; }
    public void setMaxPackageBytes(long maxPackageBytes) { this.maxPackageBytes = maxPackageBytes; }
    public long getMaxStorageBytes() { return maxStorageBytes; }
    public void setMaxStorageBytes(long maxStorageBytes) { this.maxStorageBytes = maxStorageBytes; }
    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
    public long getSelftestTimeoutMillis() { return selftestTimeoutMillis; }
    public void setSelftestTimeoutMillis(long selftestTimeoutMillis) { this.selftestTimeoutMillis = selftestTimeoutMillis; }
    public int getMaxRepairAttempts() { return maxRepairAttempts; }
    public void setMaxRepairAttempts(int maxRepairAttempts) { this.maxRepairAttempts = maxRepairAttempts; }

    public Version minimumCompatible() {
        return (minimumCompatibleVersion == null || minimumCompatibleVersion.isBlank())
                ? null : Version.parse(minimumCompatibleVersion);
    }
}
