package com.github.lechandonga.firmwareotajava.ota.packageinfo;

import com.github.lechandonga.firmwareotajava.ota.config.OtaProperties;
import com.github.lechandonga.firmwareotajava.ota.model.FirmwareManifest;
import com.github.lechandonga.firmwareotajava.ota.model.ImageInfo;
import com.github.lechandonga.firmwareotajava.ota.model.PackageException;
import com.github.lechandonga.firmwareotajava.ota.model.RejectReason;
import com.github.lechandonga.firmwareotajava.ota.util.Versions;

/**
 * 设备兼容性、版本策略与包大小策略校验。
 * 任何一项不满足都抛 PackageException（携带可区分 RejectReason），不改变在位版本与历史。
 */
public class FirmwarePackageValidator {

    private final OtaProperties props;

    public FirmwarePackageValidator(OtaProperties props) {
        this.props = props;
    }

    /**
     * @param manifest    待升级清单
     * @param currentImage 当前在位镜像（可空，空存储时）
     * @param storageUsed 当前存储已占用字节
     */
    public void validate(FirmwareManifest manifest, ImageInfo currentImage, long storageUsed)
            throws PackageException {
        checkPackageSize(manifest);
        checkDeviceCompatibility(manifest);
        checkVersionPolicy(manifest, currentImage);
        checkStorage(manifest, storageUsed);
    }

    private void checkPackageSize(FirmwareManifest manifest) {
        if (manifest.payloadSize() > props.getMaxPackageBytes()) {
            throw new PackageException(RejectReason.PACKAGE_TOO_LARGE,
                    "升级包超过大小上限：" + manifest.payloadSize() + " > "
                            + props.getMaxPackageBytes());
        }
    }

    private void checkDeviceCompatibility(FirmwareManifest manifest) {
        if (!props.getDeviceModel().equals(manifest.hardwareModel())) {
            throw new PackageException(RejectReason.HARDWARE_MODEL_MISMATCH,
                    "设备型号不匹配：设备 " + props.getDeviceModel()
                            + "，包目标 " + manifest.hardwareModel());
        }
        if (!props.getHardwareVersion().equals(manifest.hardwareVersion())) {
            throw new PackageException(RejectReason.HARDWARE_VERSION_MISMATCH,
                    "硬件版本不匹配：设备 " + props.getHardwareVersion()
                            + "，包目标 " + manifest.hardwareVersion());
        }
    }

    private void checkVersionPolicy(FirmwareManifest manifest, ImageInfo currentImage) {
        if (currentImage != null
                && currentImage.getVersion().equals(manifest.version())) {
            throw new PackageException(RejectReason.ALREADY_INSTALLED,
                    "版本 " + manifest.version() + " 已在位，拒绝重复安装");
        }
        if (currentImage != null) {
            int cmp = Versions.compare(manifest.version(), currentImage.getVersion());
            if (cmp < 0 && !props.isAllowDowngrade()) {
                throw new PackageException(RejectReason.DOWNGRADE_NOT_ALLOWED,
                        "不允许降级：当前 " + currentImage.getVersion()
                                + "，目标 " + manifest.version());
            }
        }
        String minCompatible = effectiveMinCompatible(manifest);
        if (!minCompatible.isBlank() && Versions.isValid(minCompatible)) {
            if (Versions.compare(manifest.version(), minCompatible) < 0) {
                throw new PackageException(RejectReason.BELOW_MIN_COMPATIBLE_VERSION,
                        "目标版本低于最低兼容版本：目标 " + manifest.version()
                                + "，下限 " + minCompatible);
            }
        }
    }

    /**
     * 生效的最低兼容版本取设备配置与包声明中的较高者，
     * 保证旧设备策略与新包自述要求都不被绕过。
     */
    private String effectiveMinCompatible(FirmwareManifest manifest) {
        String deviceFloor = props.getMinCompatibleVersion();
        String packageFloor = manifest.minCompatibleVersion();
        if (deviceFloor == null || deviceFloor.isBlank()) {
            return packageFloor == null ? "" : packageFloor;
        }
        if (packageFloor == null || packageFloor.isBlank()) {
            return deviceFloor;
        }
        return Versions.compare(deviceFloor, packageFloor) >= 0 ? deviceFloor : packageFloor;
    }

    private void checkStorage(FirmwareManifest manifest, long storageUsed) {
        // 安装需要在非活动槽再写一份镜像；staging 在升级结束后清理
        long projected = storageUsed + manifest.payloadSize();
        if (projected > props.getMaxStorageBytes()) {
            throw new PackageException(RejectReason.INSUFFICIENT_STORAGE,
                    "存储空间不足：预计占用 " + projected + "，上限 "
                            + props.getMaxStorageBytes());
        }
    }
}
