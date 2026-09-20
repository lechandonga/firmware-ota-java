package com.github.lechandonga.firmwareotajava.tests;

import com.github.lechandonga.firmwareotajava.model.RejectReason;
import com.github.lechandonga.firmwareotajava.model.UpgradeRecord;
import com.github.lechandonga.firmwareotajava.model.UpgradeState;
import com.github.lechandonga.firmwareotajava.tests.support.DeviceHarness;
import com.github.lechandonga.firmwareotajava.tests.support.TestPackageBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 非法/不兼容升级包必须被拒绝，且拒绝原因相互可区分；
 * 拒绝后设备继续运行在位版本 1.0.0，不写槽位、不改 lastGood。
 */
class InvalidPackageRejectionTest {

    @Test
    void sourceNotFound(@TempDir Path tmp) {
        DeviceHarness d = DeviceHarness.boot(tmp.resolve("s"), tmp.resolve("p"));
        UpgradeRecord rec = d.service.upgradeFromPackage("does-not-exist");
        assertEquals("REJECTED", rec.outcome());
        assertEquals(RejectReason.SOURCE_NOT_FOUND, rec.rejectReason());
        assertRunningUnchanged(d);
    }

    @Test
    void manifestMissing(@TempDir Path tmp) throws Exception {
        DeviceHarness d = harnessWith(tmp, b -> b.buildMissingManifest());
        assertEquals(RejectReason.MANIFEST_MISSING,
                d.service.upgradeFromPackage("bad").rejectReason());
        assertRunningUnchanged(d);
    }

    @Test
    void manifestMalformed(@TempDir Path tmp) throws Exception {
        DeviceHarness d = harnessWith(tmp, b -> b.buildMalformedManifest());
        assertEquals(RejectReason.MANIFEST_MALFORMED,
                d.service.upgradeFromPackage("bad").rejectReason());
        assertRunningUnchanged(d);
    }

    @Test
    void payloadMissing(@TempDir Path tmp) throws Exception {
        DeviceHarness d = harnessWith(tmp, b -> b.buildMissingPayload());
        assertEquals(RejectReason.PAYLOAD_MISSING,
                d.service.upgradeFromPackage("bad").rejectReason());
        assertRunningUnchanged(d);
    }

    @Test
    void payloadTampered(@TempDir Path tmp) throws Exception {
        DeviceHarness d = harnessWith(tmp, b -> b.buildTamperedPayload());
        assertEquals(RejectReason.PAYLOAD_TAMPERED,
                d.service.upgradeFromPackage("bad").rejectReason());
        assertRunningUnchanged(d);
    }

    @Test
    void modelMismatch(@TempDir Path tmp) throws Exception {
        DeviceHarness d = harnessWith(tmp, b -> b.model("DEVICE-OTHER").build());
        assertEquals(RejectReason.MODEL_MISMATCH,
                d.service.upgradeFromPackage("bad").rejectReason());
        assertRunningUnchanged(d);
    }

    @Test
    void hardwareMismatch(@TempDir Path tmp) throws Exception {
        DeviceHarness d = harnessWith(tmp, b -> b.hardware("hw-9.9").build());
        assertEquals(RejectReason.HARDWARE_MISMATCH,
                d.service.upgradeFromPackage("bad").rejectReason());
        assertRunningUnchanged(d);
    }

    @Test
    void packageTooLarge(@TempDir Path tmp) throws Exception {
        DeviceHarness d = DeviceHarness.boot(tmp.resolve("s"), tmp.resolve("p"));
        // 默认上限 1 MiB，构造 2 MiB 包
        new TestPackageBuilder(d.packages, "big")
                .version("2.0.0").payloadSize(2 * 1024 * 1024).build();
        assertEquals(RejectReason.PACKAGE_TOO_LARGE,
                d.service.upgradeFromPackage("big").rejectReason());
        assertRunningUnchanged(d);
    }

    @Test
    void rejectedReasonsAreQueryableAndDistinguishable(@TempDir Path tmp) throws Exception {
        DeviceHarness d = harnessWith(tmp, b -> b.model("X").build());
        d.service.upgradeFromPackage("bad");
        UpgradeState s = d.service.currentState();
        assertEquals(RejectReason.MODEL_MISMATCH, s.lastRejectReason());
        assertEquals("upg-bad", s.lastRejectedUpgradeId());
    }

    private interface BuildAction {
        Path run(TestPackageBuilder b) throws Exception;
    }

    private DeviceHarness harnessWith(Path tmp, BuildAction action) throws Exception {
        DeviceHarness d = DeviceHarness.boot(tmp.resolve("s"), tmp.resolve("p"));
        action.run(new TestPackageBuilder(d.packages, "bad").version("2.0.0"));
        return d;
    }

    private void assertRunningUnchanged(DeviceHarness d) {
        UpgradeState s = d.service.currentState();
        assertEquals("1.0.0", s.currentRunningVersion().toString());
        assertEquals("1.0.0", s.lastGoodVersion().toString());
        assertEquals("A", s.activeSlot());
        // 历史记录拒绝事件，但运行版本不受影响
        UpgradeRecord last = d.service.history().get(d.service.history().size() - 1);
        assertEquals("REJECTED", last.outcome());
    }
}
