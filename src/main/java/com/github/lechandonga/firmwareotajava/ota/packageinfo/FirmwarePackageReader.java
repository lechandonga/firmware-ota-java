package com.github.lechandonga.firmwareotajava.ota.packageinfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.lechandonga.firmwareotajava.ota.model.FirmwareManifest;
import com.github.lechandonga.firmwareotajava.ota.model.PackageException;
import com.github.lechandonga.firmwareotajava.ota.model.RejectReason;
import com.github.lechandonga.firmwareotajava.ota.model.SelfTestMode;
import com.github.lechandonga.firmwareotajava.ota.model.SelfTestSpec;
import com.github.lechandonga.firmwareotajava.ota.storage.JsonFileStore;
import com.github.lechandonga.firmwareotajava.ota.util.Hashes;
import com.github.lechandonga.firmwareotajava.ota.util.Versions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 解析本地目录形式的升级包：
 * <pre>
 * packageDir/manifest.json
 * packageDir/manifest.sha256      清单文件自身的 SHA-256（十六进制文本）
 * packageDir/payload.bin
 * </pre>
 * 只做结构与完整性校验；设备兼容性、版本策略由 FirmwarePackageValidator 负责。
 */
public class FirmwarePackageReader {

    public static final String MANIFEST_FILE = "manifest.json";
    public static final String MANIFEST_SIG_FILE = "manifest.sha256";
    public static final String PAYLOAD_FILE = "payload.bin";

    private final JsonFileStore json;

    public FirmwarePackageReader(JsonFileStore json) {
        this.json = json;
    }

    /** 读取并校验清单（存在性、签名、结构、格式版本、负载大小与整体校验和）。 */
    public FirmwareManifest readManifest(Path packageDir) throws PackageException {
        Path manifestFile = packageDir.resolve(MANIFEST_FILE);
        Path signatureFile = packageDir.resolve(MANIFEST_SIG_FILE);
        Path payloadFile = packageDir.resolve(PAYLOAD_FILE);

        if (!Files.isRegularFile(manifestFile)) {
            throw new PackageException(RejectReason.MANIFEST_NOT_FOUND,
                    "升级包清单缺失: " + manifestFile);
        }
        if (!Files.isRegularFile(signatureFile)) {
            throw new PackageException(RejectReason.MANIFEST_SIGNATURE_INVALID,
                    "清单校验文件缺失: " + signatureFile);
        }
        if (!Files.isRegularFile(payloadFile)) {
            throw new PackageException(RejectReason.PAYLOAD_NOT_FOUND,
                    "升级负载缺失: " + payloadFile);
        }

        verifyManifestSignature(manifestFile, signatureFile);

        ManifestDto dto;
        try {
            dto = json.getMapper().readValue(manifestFile.toFile(), ManifestDto.class);
        } catch (IOException e) {
            throw new PackageException(RejectReason.MANIFEST_CORRUPT,
                    "清单无法解析（内容可能被篡改）: " + e.getMessage(), e);
        }
        FirmwareManifest manifest = toManifest(dto);
        validateStructure(manifest);
        verifyPayloadSizeAndChecksum(payloadFile, manifest);
        return manifest;
    }

    private void verifyManifestSignature(Path manifestFile, Path signatureFile) {
        String expected;
        String actual;
        try {
            expected = Files.readString(signatureFile, StandardCharsets.UTF_8)
                    .trim()
                    // 支持 "hash  filename" 形式
                    .split("\\s+")[0]
                    .toLowerCase();
            actual = Hashes.sha256Hex(manifestFile);
        } catch (IOException e) {
            throw new PackageException(RejectReason.MANIFEST_SIGNATURE_INVALID,
                    "读取清单签名失败: " + e.getMessage(), e);
        }
        if (!expected.equals(actual)) {
            throw new PackageException(RejectReason.MANIFEST_SIGNATURE_INVALID,
                    "清单签名不匹配：内容被篡改或签名文件有误（期望 " + expected + "，实际 " + actual + "）");
        }
    }

    private FirmwareManifest toManifest(ManifestDto dto) {
        SelfTestDto st = dto.selfTest == null ? new SelfTestDto() : dto.selfTest;
        SelfTestMode mode;
        try {
            mode = st.mode == null || st.mode.isBlank()
                    ? SelfTestMode.ALWAYS_OK
                    : SelfTestMode.valueOf(st.mode.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new PackageException(RejectReason.MANIFEST_INVALID,
                    "未知自检模式: " + st.mode);
        }
        return new FirmwareManifest(
                dto.manifestVersion == null ? -1 : dto.manifestVersion,
                dto.hardwareModel,
                dto.hardwareVersion,
                dto.version,
                dto.payloadSize == null ? -1L : dto.payloadSize,
                dto.payloadSha256,
                dto.chunkSize == null ? -1 : dto.chunkSize,
                dto.chunkSha256 == null ? List.of() : List.copyOf(dto.chunkSha256),
                dto.minCompatibleVersion,
                new SelfTestSpec(mode, st.timeoutMs == null ? 0L : st.timeoutMs,
                        st.requiredMarker));
    }

    private void validateStructure(FirmwareManifest m) {
        if (m.manifestVersion() != FirmwareManifest.CURRENT_MANIFEST_VERSION) {
            throw new PackageException(RejectReason.UNSUPPORTED_MANIFEST_VERSION,
                    "不支持的清单格式版本: " + m.manifestVersion());
        }
        if (isBlank(m.hardwareModel()) || isBlank(m.hardwareVersion())) {
            throw new PackageException(RejectReason.MANIFEST_INVALID, "清单缺少设备型号或硬件版本");
        }
        if (!Versions.isValid(m.version())) {
            throw new PackageException(RejectReason.INVALID_VERSION,
                    "清单中的固件版本非法: " + m.version());
        }
        if (m.payloadSize() <= 0 || m.chunkSize() <= 0) {
            throw new PackageException(RejectReason.MANIFEST_INVALID,
                    "清单中的负载大小或分块大小非法");
        }
        if (isBlank(m.payloadSha256()) || m.chunkSha256().isEmpty()) {
            throw new PackageException(RejectReason.MANIFEST_INVALID,
                    "清单缺少负载校验和或分块校验和");
        }
        int expectedChunks = (int) ((m.payloadSize() + m.chunkSize() - 1) / m.chunkSize());
        if (expectedChunks != m.chunkSha256().size()) {
            throw new PackageException(RejectReason.MANIFEST_INVALID,
                    "分块校验和数量与负载大小不匹配：期望 " + expectedChunks
                            + "，实际 " + m.chunkSha256().size());
        }
        if (m.selfTest().mode() == SelfTestMode.MARKER
                && isBlank(m.selfTest().requiredMarker())) {
            throw new PackageException(RejectReason.MANIFEST_INVALID,
                    "MARKER 自检模式必须提供 requiredMarker");
        }
    }

    private void verifyPayloadSizeAndChecksum(Path payloadFile, FirmwareManifest m) {
        long actualSize;
        try {
            actualSize = Files.size(payloadFile);
        } catch (IOException e) {
            throw new PackageException(RejectReason.PAYLOAD_NOT_FOUND,
                    "无法读取负载: " + e.getMessage(), e);
        }
        if (actualSize != m.payloadSize()) {
            throw new PackageException(RejectReason.PAYLOAD_SIZE_MISMATCH,
                    "负载大小不匹配：清单声明 " + m.payloadSize() + "，实际 " + actualSize);
        }
        try {
            String actualHash = Hashes.sha256Hex(payloadFile);
            if (!actualHash.equalsIgnoreCase(m.payloadSha256())) {
                throw new PackageException(RejectReason.PAYLOAD_CHECKSUM_MISMATCH,
                        "负载整体校验和不匹配：内容被篡改或传输损坏");
            }
        } catch (IOException e) {
            throw new PackageException(RejectReason.PAYLOAD_CHECKSUM_MISMATCH,
                    "计算负载校验和失败: " + e.getMessage(), e);
        }
    }

    /** 打开负载输入流（调用方负责关闭）。 */
    public InputStream openPayload(Path packageDir) throws PackageException {
        Path payloadFile = packageDir.resolve(PAYLOAD_FILE);
        try {
            return Files.newInputStream(payloadFile);
        } catch (IOException e) {
            throw new PackageException(RejectReason.PAYLOAD_NOT_FOUND,
                    "无法打开负载: " + e.getMessage(), e);
        }
    }

    /** 对完整负载做 SHA-256 校验（供 staging 终验复用）。 */
    public void verifyFullPayload(Path packageDir, FirmwareManifest manifest) throws PackageException {
        verifyPayloadSizeAndChecksum(packageDir.resolve(PAYLOAD_FILE), manifest);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ManifestDto {
        @JsonProperty("manifestVersion")
        public Integer manifestVersion;
        @JsonProperty("hardwareModel")
        public String hardwareModel;
        @JsonProperty("hardwareVersion")
        public String hardwareVersion;
        @JsonProperty("version")
        public String version;
        @JsonProperty("payloadSize")
        public Long payloadSize;
        @JsonProperty("payloadSha256")
        public String payloadSha256;
        @JsonProperty("chunkSize")
        public Integer chunkSize;
        @JsonProperty("chunkSha256")
        public List<String> chunkSha256;
        @JsonProperty("minCompatibleVersion")
        public String minCompatibleVersion;
        @JsonProperty("selfTest")
        public SelfTestDto selfTest;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SelfTestDto {
        @JsonProperty("mode")
        public String mode;
        @JsonProperty("timeoutMs")
        public Long timeoutMs;
        @JsonProperty("requiredMarker")
        public String requiredMarker;
    }
}
