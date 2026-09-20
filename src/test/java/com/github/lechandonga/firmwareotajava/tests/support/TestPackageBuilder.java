package com.github.lechandonga.firmwareotajava.tests.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.firmwareotajava.model.FirmwareManifest;
import com.github.lechandonga.firmwareotajava.pkg.HashUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试用升级包构建器：在“来源目录”生成符合格式的升级包，
 * 可构造清单缺失、载荷缺失、内容篡改、型号/硬件错配、自检失败/超时等场景。
 */
public class TestPackageBuilder {

    public static final String MODEL = "DEVICE-X1";
    public static final String HW = "hw-1.0";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path sourceDir;
    private String name;
    private String upgradeId;
    private String version = "2.0.0";
    private String model = MODEL;
    private String hw = HW;
    private int payloadSize = 10_000;
    private int chunkSize = 4096;
    private String selftestResult = "SUCCESS";
    private long selftestDuration = 0L;
    private byte seedSalt = 7;

    public TestPackageBuilder(Path sourceDir, String name) {
        this.sourceDir = sourceDir;
        this.name = name;
        this.upgradeId = "upg-" + name;
    }

    public TestPackageBuilder version(String v) { this.version = v; return this; }

    public TestPackageBuilder upgradeId(String id) { this.upgradeId = id; return this; }

    public TestPackageBuilder model(String m) { this.model = m; return this; }

    public TestPackageBuilder hardware(String h) { this.hw = h; return this; }

    public TestPackageBuilder payloadSize(int bytes) {
        this.payloadSize = bytes;
        return this;
    }

    public TestPackageBuilder chunkSize(int bytes) {
        this.chunkSize = bytes;
        return this;
    }

    public TestPackageBuilder selftestSuccess() {
        this.selftestResult = "SUCCESS";
        return this;
    }

    public TestPackageBuilder selftestFails(long durationMillis) {
        this.selftestResult = "FAIL";
        this.selftestDuration = durationMillis;
        return this;
    }

    public TestPackageBuilder selftestTimesOut(long stuckMillis) {
        this.selftestResult = "TIMEOUT";
        this.selftestDuration = stuckMillis;
        return this;
    }

    public TestPackageBuilder seed(byte salt) {
        this.seedSalt = salt;
        return this;
    }

    public Path build() throws IOException {
        Path dir = sourceDir.resolve(name);
        Files.createDirectories(dir);
        byte[] payload = payload();
        String overall = HashUtil.sha256Hex(payload);
        List<String> chunkHashes = new ArrayList<>();
        for (int off = 0; off < payload.length; off += chunkSize) {
            int len = Math.min(chunkSize, payload.length - off);
            byte[] chunk = new byte[len];
            System.arraycopy(payload, off, chunk, 0, len);
            chunkHashes.add(HashUtil.sha256Hex(chunk));
        }
        FirmwareManifest manifest = new FirmwareManifest(
                upgradeId,
                com.github.lechandonga.firmwareotajava.model.Version.parse(version),
                model, hw, payload.length, overall, chunkSize, chunkHashes,
                selftestResult, selftestDuration);
        Files.write(dir.resolve("payload.bin"), payload);
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(dir.resolve("manifest.json").toFile(), manifest);
        return dir;
    }

    public byte[] payload() {
        byte[] data = new byte[payloadSize];
        for (int i = 0; i < payloadSize; i++) {
            data[i] = (byte) (i * 31 + seedSalt + version.hashCode());
        }
        return data;
    }

    // ---- 非法包构造 ----

    /** 只建目录，不放任何文件：清单缺失。 */
    public Path buildMissingManifest() throws IOException {
        Path dir = sourceDir.resolve(name);
        Files.createDirectories(dir);
        Files.write(dir.resolve("payload.bin"), payload());
        return dir;
    }

    /** 清单在但 payload 缺失。 */
    public Path buildMissingPayload() throws IOException {
        Path dir = build();
        Files.delete(dir.resolve("payload.bin"));
        return dir;
    }

    /** 清单声明哈希正确但写入后篡改 payload：内容篡改。 */
    public Path buildTamperedPayload() throws IOException {
        Path dir = build();
        Path payload = dir.resolve("payload.bin");
        byte[] bytes = Files.readAllBytes(payload);
        bytes[0] ^= (byte) 0xFF;
        Files.write(payload, bytes);
        return dir;
    }

    /** 直接写坏清单 JSON。 */
    public Path buildMalformedManifest() throws IOException {
        Path dir = sourceDir.resolve(name);
        Files.createDirectories(dir);
        Files.write(dir.resolve("payload.bin"), payload());
        Files.writeString(dir.resolve("manifest.json"), "{ not-json ,,");
        return dir;
    }
}
