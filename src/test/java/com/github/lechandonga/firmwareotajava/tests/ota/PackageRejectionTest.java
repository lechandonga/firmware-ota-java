package com.github.lechandonga.firmwareotajava.ota.tests.ota;

import com.github.lechandonga.firmwareotajava.ota.model.RejectReason;
import com.github.lechandonga.firmwareotajava.ota.model.UpgradePhase;
import com.github.lechandonga.firmwareotajava.ota.model.UpgradeRecord;
import com.github.lechandonga.firmwareotajava.ota.packageinfo.FirmwarePackageBuilder;
import com.github.lechandonga.firmwareotajava.ota.packageinfo.FirmwarePackageReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 非法包与策略拒绝场景：每种失败必须有可区分原因，拒绝后在位版本继续运行，
 * 且预检拒绝不写入升级历史、不残留临时资源。
 */
class PackageRejectionTest {

    @TempDir
    Path tmp;

    @Test
    void manifestMissingIsRejectedAndDeviceKeepsRunning() throws Exception {
        TestEnv env = TestEnv.createDefault(tmp);
        Path pkg = env.buildPackage("p", "2.0.0", "fw 2.0");
        Files.delete(pkg.resolve(FirmwarePackageReader.MANIFEST_FILE));

        UpgradeRecord rec = env.service.startUpgrade("u-missing", pkg);

        assertEquals(UpgradePhase.REJECTED, rec.getPhase());
        assertEquals(RejectReason.MANIFEST_NOT_FOUND, rec.getRejectReason());
        assertEquals("1.0.0", env.activeVersion());
        assertTrue(env.service.history().isEmpty(), "预检拒绝不得写入升级历史");
    }

    @Test
    void tamperedManifestIsRejectedWithSignatureReason() throws Exception {
        TestEnv env = TestEnv.createDefault(tmp);
        Path pkg = env.buildPackage("p", "2.0.0", "fw 2.0");
        String manifest = Files.readString(pkg.resolve(FirmwarePackageReader.MANIFEST_FILE));
        String tampered = manifest.replace("\"version\":\"2.0.0\"", "\"version\":\"2.0.1\"");
        assertTrue(!tampered.equals(manifest), "前置条件：清单中的版本字段应被替换");
        Files.writeString(pkg.resolve(FirmwarePackageReader.MANIFEST_FILE), tampered);

        UpgradeRecord rec = env.service.startUpgrade("u-tamper-manifest", pkg);

        assertEquals(RejectReason.MANIFEST_SIGNATURE_INVALID, rec.getRejectReason());
        assertEquals("1.0.0", env.activeVersion());
    }

    @Test
    void tamperedPayloadIsRejectedWithChecksumReason() throws Exception {
        TestEnv env = TestEnv.createDefault(tmp);
        Path pkg = env.buildPackage("p", "2.0.0", "fw 2.0");
        byte[] original = Files.readAllBytes(pkg.resolve(FirmwarePackageReader.PAYLOAD_FILE));
        byte[] corrupted = original.clone();
        corrupted[0] = (byte) (corrupted[0] ^ 0xFF);
        Files.write(pkg.resolve(FirmwarePackageReader.PAYLOAD_FILE), corrupted);

        UpgradeRecord rec = env.service.startUpgrade("u-tamper-payload", pkg);

        assertEquals(RejectReason.PAYLOAD_CHECKSUM_MISMATCH, rec.getRejectReason());
        assertEquals("1.0.0", env.activeVersion());
        assertTrue(env.service.history().isEmpty());
    }

    @Test
    void wrongHardwareModelIsRejected() {
        TestEnv env = TestEnv.createDefault(tmp);
        Path dir = tmp.resolve("packages").resolve("other-model");
        new FirmwarePackageBuilder(env.json).build(dir, "OTHER-DEVICE", "1.0",
                "2.0.0", "x".getBytes(StandardCharsets.UTF_8), 32, null,
                com.github.lechandonga.firmwareotajava.ota.model.SelfTestMode.ALWAYS_OK, 1000, null);

        UpgradeRecord rec = env.service.startUpgrade("u-model", dir);
        assertEquals(RejectReason.HARDWARE_MODEL_MISMATCH, rec.getRejectReason());
        assertEquals("1.0.0", env.activeVersion());
    }

    @Test
    void wrongHardwareVersionIsRejected() {
        TestEnv env = TestEnv.createDefault(tmp);
        Path dir = tmp.resolve("packages").resolve("other-hw");
        new FirmwarePackageBuilder(env.json).build(dir, "DEMO-DEVICE", "9.9",
                "2.0.0", "x".getBytes(StandardCharsets.UTF_8), 32, null,
                com.github.lechandonga.firmwareotajava.ota.model.SelfTestMode.ALWAYS_OK, 1000, null);

        UpgradeRecord rec = env.service.startUpgrade("u-hw", dir);
        assertEquals(RejectReason.HARDWARE_VERSION_MISMATCH, rec.getRejectReason());
        assertEquals("1.0.0", env.activeVersion());
    }

    @Test
    void downgradeRejectedByPolicyButAllowedAfterConfigChange() {
        TestEnv env = TestEnv.createDefault(tmp);
        // 先升级到 2.0.0
        UpgradeRecord up = env.service.startUpgrade("u-up", env.buildPackage("p2", "2.0.0", "fw2"));
        assertEquals(UpgradePhase.COMMITTED, up.getPhase());
        assertEquals("2.0.0", env.activeVersion());

        // 再尝试降级到 1.5.0
        Path down = env.buildPackage("p15", "1.5.0", "fw15");
        UpgradeRecord rejected = env.service.startUpgrade("u-down", down);
        assertEquals(RejectReason.DOWNGRADE_NOT_ALLOWED, rejected.getRejectReason());
        assertEquals("2.0.0", env.activeVersion(), "降级被拒绝后必须保持在位版本");

        // 打开降级策略后允许
        env.props.setAllowDowngrade(true);
        UpgradeRecord allowed = env.service.startUpgrade("u-down-allowed", down);
        assertEquals(UpgradePhase.COMMITTED, allowed.getPhase());
        assertEquals("1.5.0", env.activeVersion());
    }

    @Test
    void belowMinimumCompatibleVersionIsRejected() {
        TestEnv env = TestEnv.create(tmp, 32, 64L * 1024 * 1024, 256L * 1024 * 1024,
                false, "1.5.0");
        Path pkg = env.buildPackage("p-low", "1.4.0", "old fw");

        UpgradeRecord rec = env.service.startUpgrade("u-low", pkg);
        assertEquals(RejectReason.BELOW_MIN_COMPATIBLE_VERSION, rec.getRejectReason());
        assertEquals("1.0.0", env.activeVersion());
    }

    @Test
    void packageOverSizeLimitIsRejectedWithoutResidue() {
        TestEnv env = TestEnv.create(tmp, 32, 64L, 256L * 1024 * 1024, false, "");
        byte[] big = new byte[100];
        java.util.Arrays.fill(big, (byte) 'x');
        Path pkg = env.buildPackage("p-big", "2.0.0", big);

        UpgradeRecord rec = env.service.startUpgrade("u-big", pkg);
        assertEquals(RejectReason.PACKAGE_TOO_LARGE, rec.getRejectReason());
        assertEquals("1.0.0", env.activeVersion());
        try (var walk = Files.walk(env.storage.stagingDir())) {
            assertEquals(0L, walk.filter(Files::isRegularFile).count(),
                    "拒绝后不得残留 staging 临时文件");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void reinstallSameVersionIsRejectedAsAlreadyInstalled() {
        TestEnv env = TestEnv.createDefault(tmp);
        UpgradeRecord up = env.service.startUpgrade("u", env.buildPackage("p2", "2.0.0", "fw2"));
        assertEquals(UpgradePhase.COMMITTED, up.getPhase());

        UpgradeRecord again = env.service.startUpgrade("u-same",
                env.buildPackage("p2again", "2.0.0", "fw2"));
        assertEquals(RejectReason.ALREADY_INSTALLED, again.getRejectReason());
    }
}
