package com.github.lechandonga.firmwareotajava.pkg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.firmwareotajava.config.OtaProperties;
import com.github.lechandonga.firmwareotajava.model.DeviceIdentity;
import com.github.lechandonga.firmwareotajava.model.FirmwareManifest;
import com.github.lechandonga.firmwareotajava.model.RejectReason;
import com.github.lechandonga.firmwareotajava.model.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 从本地来源目录解析升级包并做静态校验。
 *
 * <p>包格式：
 * <pre>
 * {packageDir}/manifest.json   清单（缺失/损坏 => MANIFEST_MISSING/MANIFEST_MALFORMED）
 * {packageDir}/payload.bin     固件载荷（缺失 => PAYLOAD_MISSING，
 *                              大小/哈希不符 => PAYLOAD_TAMPERED）
 * </pre>
 *
 * <p>校验顺序保证拒绝原因可区分：先来源与清单，再载荷存在性，
 * 再包大小上限（PACKAGE_TOO_LARGE），再内容哈希（PAYLOAD_TAMPERED），
 * 最后型号/硬件（MODEL_MISMATCH/HARDWARE_MISMATCH）。
 */
@Component
public class PackageParser {

    private static final String MANIFEST_NAME = "manifest.json";
    private static final String PAYLOAD_NAME = "payload.bin";

    private static final Logger log = LoggerFactory.getLogger(PackageParser.class);

    private final OtaProperties properties;
    private final ObjectMapper objectMapper;

    public PackageParser(OtaProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 来源目录是否存在（不存在 => SOURCE_NOT_FOUND）。 */
    public void verifySourceExists(Path packageDir) throws PackageRejectedException {
        if (packageDir == null || !Files.isDirectory(packageDir)) {
            throw reject(RejectReason.SOURCE_NOT_FOUND,
                    "升级包来源目录不存在: " + packageDir);
        }
    }

    /** 解析来源目录并执行全部静态校验（含设备兼容性与大小上限）。 */
    public FirmwareManifest parse(Path packageDir, DeviceIdentity identity)
            throws PackageRejectedException {
        verifySourceExists(packageDir);
        FirmwareManifest manifest = parseManifest(packageDir);
        Path payload = packageDir.resolve(PAYLOAD_NAME);
        verifyPayloadPresent(payload);
        verifyPackageSize(payload, manifest);
        verifyPayloadIntegrity(packageDir, manifest);
        verifyCompatibility(manifest, identity);
        log.info("升级包校验通过 upgradeId={} version={} model={} hw={}",
                manifest.upgradeId(), manifest.version(), manifest.model(),
                manifest.hardwareVersion());
        return manifest;
    }

    public FirmwareManifest parseManifest(Path packageDir) throws PackageRejectedException {
        Path manifestFile = packageDir.resolve(MANIFEST_NAME);
        if (!Files.exists(manifestFile)) {
            throw reject(RejectReason.MANIFEST_MISSING,
                    "清单缺失: " + manifestFile);
        }
        FirmwareManifest raw;
        try {
            raw = objectMapper.readValue(manifestFile.toFile(), FirmwareManifest.class);
        } catch (IOException e) {
            throw reject(RejectReason.MANIFEST_MALFORMED,
                    "清单无法解析: " + e.getMessage());
        }
        // 语义校验：必填项缺失/非法版本号都归为清单损坏
        try {
            if (raw == null || isBlank(raw.upgradeId()) || raw.version() == null
                    || isBlank(raw.model()) || isBlank(raw.hardwareVersion())
                    || raw.payloadSha256() == null || raw.chunkSha256() == null
                    || raw.chunkSize() <= 0 || raw.payloadSize() < 0) {
                throw new IllegalArgumentException("清单必填字段缺失或非法");
            }
            // record 的 version 反序列化依赖 Version 解析；这里再次确保格式合法
            Version.parse(raw.version().toString());
            return raw;
        } catch (RuntimeException e) {
            throw reject(RejectReason.MANIFEST_MALFORMED,
                    "清单内容非法: " + e.getMessage());
        }
    }

    public void verifyPayloadPresent(Path payload) throws PackageRejectedException {
        if (!Files.exists(payload)) {
            throw reject(RejectReason.PAYLOAD_MISSING, "固件载荷缺失: " + payload);
        }
    }

    public void verifyPackageSize(Path payload, FirmwareManifest manifest)
            throws PackageRejectedException {
        long actual;
        try {
            actual = Files.size(payload);
        } catch (IOException e) {
            throw reject(RejectReason.PAYLOAD_MISSING, "无法读取载荷大小: " + e.getMessage());
        }
        long declared = manifest.payloadSize();
        if (declared != actual) {
            throw reject(RejectReason.PAYLOAD_TAMPERED,
                    "载荷大小与清单不符: declared=" + declared + ", actual=" + actual);
        }
        if (actual > properties.getMaxPackageBytes()) {
            throw reject(RejectReason.PACKAGE_TOO_LARGE,
                    "升级包大小 " + actual + " 超过上限 " + properties.getMaxPackageBytes());
        }
    }

    public void verifyPayloadIntegrity(Path packageDir, FirmwareManifest manifest)
            throws PackageRejectedException {
        verifyPayloadFile(packageDir.resolve(PAYLOAD_NAME), manifest);
    }

    /** 校验整体大小/哈希与分块哈希列表是否与清单一致（检测内容篡改）。 */
    public void verifyPayloadFile(Path payload, FirmwareManifest manifest)
            throws PackageRejectedException {
        long actual;
        String actualHash;
        try {
            actual = Files.size(payload);
            actualHash = HashUtil.sha256Hex(payload);
        } catch (IOException e) {
            throw reject(RejectReason.PAYLOAD_TAMPERED, "读取载荷失败: " + e.getMessage());
        }
        if (actual != manifest.payloadSize()) {
            throw reject(RejectReason.PAYLOAD_TAMPERED,
                    "载荷大小与清单不符: declared=" + manifest.payloadSize() + ", actual=" + actual);
        }
        if (!actualHash.equalsIgnoreCase(manifest.payloadSha256())) {
            throw reject(RejectReason.PAYLOAD_TAMPERED,
                    "载荷 SHA-256 与清单不符（内容可能被篡改）: expected="
                            + manifest.payloadSha256() + ", actual=" + actualHash);
        }
        int expectedChunks = (int) ((actual + manifest.chunkSize() - 1) / manifest.chunkSize());
        if (manifest.chunkSha256().size() != expectedChunks) {
            throw reject(RejectReason.MANIFEST_MALFORMED,
                    "分块哈希数量与载荷/块大小不符: expected=" + expectedChunks
                            + ", actual=" + manifest.chunkSha256().size());
        }
    }

    /**
     * 校验清单声明的分块粒度与设备配置一致。设备按固定 chunk-size 做断点续传，
     * 不一致的清单无法被本机正确分块处理，归类为清单不合法。
     */
    public void verifyChunking(FirmwareManifest manifest) throws PackageRejectedException {
        int expected = properties.getChunkSize();
        if (expected > 0 && manifest.chunkSize() != expected) {
            throw reject(RejectReason.MANIFEST_MALFORMED,
                    "分块大小与设备配置不符: package=" + manifest.chunkSize()
                            + ", device=" + expected);
        }
    }

    public void verifyCompatibility(FirmwareManifest manifest, DeviceIdentity identity)
            throws PackageRejectedException {        if (!identity.model().equals(manifest.model())) {
            throw reject(RejectReason.MODEL_MISMATCH,
                    "型号不匹配: device=" + identity.model()
                            + ", package=" + manifest.model());
        }
        if (!identity.hardwareVersion().equals(manifest.hardwareVersion())) {
            throw reject(RejectReason.HARDWARE_MISMATCH,
                    "硬件版本不匹配: device=" + identity.hardwareVersion()
                            + ", package=" + manifest.hardwareVersion());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static PackageRejectedException reject(RejectReason reason, String message) {
        log.warn("升级包被拒绝 reason={} detail={}", reason, message);
        return new PackageRejectedException(reason, message);
    }
}
