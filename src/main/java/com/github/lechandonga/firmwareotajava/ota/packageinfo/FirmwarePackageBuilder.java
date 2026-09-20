package com.github.lechandonga.firmwareotajava.ota.packageinfo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.lechandonga.firmwareotajava.ota.model.FirmwareManifest;
import com.github.lechandonga.firmwareotajava.ota.model.SelfTestMode;
import com.github.lechandonga.firmwareotajava.ota.storage.JsonFileStore;
import com.github.lechandonga.firmwareotajava.ota.util.Hashes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 升级包构建工具（供演示、测试与手工构造场景使用）：生成 manifest.json、
 * manifest.sha256、payload.bin，并按分块大小计算每块 SHA-256。
 */
public class FirmwarePackageBuilder {

    private final JsonFileStore json;

    public FirmwarePackageBuilder(JsonFileStore json) {
        this.json = json;
    }

    /**
     * 生成一个合法升级包。
     */
    public FirmwareManifest build(Path packageDir,
                                  String hardwareModel,
                                  String hardwareVersion,
                                  String version,
                                  byte[] payload,
                                  int chunkSize,
                                  String minCompatibleVersion,
                                  SelfTestMode selfTestMode,
                                  long selfTestTimeoutMs,
                                  String requiredMarker) {
        try {
            Files.createDirectories(packageDir);
            Path payloadFile = packageDir.resolve(FirmwarePackageReader.PAYLOAD_FILE);
            Files.write(payloadFile, payload);

            List<String> chunkHashes = chunkHashes(payload, chunkSize);
            FirmwareManifest manifest = new FirmwareManifest(
                    FirmwareManifest.CURRENT_MANIFEST_VERSION,
                    hardwareModel,
                    hardwareVersion,
                    version,
                    payload.length,
                    Hashes.sha256Hex(payload),
                    chunkSize,
                    chunkHashes,
                    minCompatibleVersion == null || minCompatibleVersion.isBlank()
                            ? null : minCompatibleVersion,
                    new com.github.lechandonga.firmwareotajava.ota.model.SelfTestSpec(
                            selfTestMode, selfTestTimeoutMs, requiredMarker));

            Path manifestFile = packageDir.resolve(FirmwarePackageReader.MANIFEST_FILE);
            json.write(manifestFile, new ManifestWriteModel(manifest, selfTestMode));

            String signature = Hashes.sha256Hex(manifestFile);
            Files.writeString(packageDir.resolve(FirmwarePackageReader.MANIFEST_SIG_FILE),
                    signature + "  " + FirmwarePackageReader.MANIFEST_FILE + "\n",
                    StandardCharsets.UTF_8);
            return manifest;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to build package at " + packageDir, e);
        }
    }

    public static List<String> chunkHashes(byte[] payload, int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }
        List<String> hashes = new ArrayList<>();
        for (int offset = 0; offset < payload.length; offset += chunkSize) {
            int end = Math.min(offset + chunkSize, payload.length);
            hashes.add(Hashes.sha256Hex(java.util.Arrays.copyOfRange(payload, offset, end)));
        }
        return hashes;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class ManifestWriteModel {
        @JsonProperty("manifestVersion")
        final int manifestVersion;
        @JsonProperty("hardwareModel")
        final String hardwareModel;
        @JsonProperty("hardwareVersion")
        final String hardwareVersion;
        @JsonProperty("version")
        final String version;
        @JsonProperty("payloadSize")
        final long payloadSize;
        @JsonProperty("payloadSha256")
        final String payloadSha256;
        @JsonProperty("chunkSize")
        final int chunkSize;
        @JsonProperty("chunkSha256")
        final List<String> chunkSha256;
        @JsonProperty("minCompatibleVersion")
        final String minCompatibleVersion;
        @JsonProperty("selfTest")
        final SelfTestWriteModel selfTest;

        ManifestWriteModel(FirmwareManifest m, SelfTestMode mode) {
            this.manifestVersion = m.manifestVersion();
            this.hardwareModel = m.hardwareModel();
            this.hardwareVersion = m.hardwareVersion();
            this.version = m.version();
            this.payloadSize = m.payloadSize();
            this.payloadSha256 = m.payloadSha256();
            this.chunkSize = m.chunkSize();
            this.chunkSha256 = m.chunkSha256();
            this.minCompatibleVersion = m.minCompatibleVersion();
            this.selfTest = new SelfTestWriteModel(mode.name(),
                    m.selfTest().timeoutMs(), m.selfTest().requiredMarker());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record SelfTestWriteModel(
            @JsonProperty("mode") String mode,
            @JsonProperty("timeoutMs") long timeoutMs,
            @JsonProperty("requiredMarker") String requiredMarker) {
    }
}
