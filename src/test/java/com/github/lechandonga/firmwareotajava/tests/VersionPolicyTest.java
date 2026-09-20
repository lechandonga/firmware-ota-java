package com.github.lechandonga.firmwareotajava.tests;

import com.github.lechandonga.firmwareotajava.config.OtaProperties;
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
 * 版本策略：默认禁止降级；最低兼容版本约束。
 * 策略拒绝必须不改变在位版本、不写升级历史。
 */
class VersionPolicyTest {

    @Test
    void downgradeForbiddenByDefault(@TempDir Path tmp) throws Exception {
        DeviceHarness d = DeviceHarness.boot(tmp.resolve("s"), tmp.resolve("p"));
        new TestPackageBuilder(d.packages, "v2").version("2.0.0").build();
        d.service.upgradeFromPackage("v2");
        int historyBefore = d.service.history().size();

        new TestPackageBuilder(d.packages, "v15")
                .version("1.5.0").seed((byte) 99).build();
        UpgradeRecord rec = d.service.upgradeFromPackage("v15");
        assertNull(rec, "策略拒绝返回 null 且不写历史");

        UpgradeState s = d.service.currentState();
        assertEquals("2.0.0", s.currentRunningVersion().toString(), "在位版本不变");
        assertEquals(RejectReason.DOWNGRADE_FORBIDDEN, s.lastRejectReason());
        assertEquals(historyBefore, d.service.history().size(), "不得写升级历史");
    }

    @Test
    void downgradeAllowedWhenConfigured(@TempDir Path tmp) throws Exception {
        OtaProperties props = DeviceHarness.defaultProps(
                tmp.resolve("s"), tmp.resolve("p"));
        props.setAllowDowngrade(true);
        DeviceHarness d = DeviceHarness.boot(tmp.resolve("s"), tmp.resolve("p"), props);

        new TestPackageBuilder(d.packages, "v2").version("2.0.0").build();
        d.service.upgradeFromPackage("v2");

        new TestPackageBuilder(d.packages, "v15")
                .version("1.5.0").seed((byte) 99).build();
        UpgradeRecord rec = d.service.upgradeFromPackage("v15");
        assertNotNull(rec);
        assertEquals("SUCCEEDED", rec.outcome());
        assertEquals("1.5.0",
                d.service.currentState().currentRunningVersion().toString());
    }

    @Test
    void belowMinimumCompatibleIsRejectedEvenIfNewerThanCurrent(@TempDir Path tmp)
            throws Exception {
        OtaProperties props = DeviceHarness.defaultProps(
                tmp.resolve("s"), tmp.resolve("p"));
        props.setMinimumCompatibleVersion("1.5.0");
        DeviceHarness d = DeviceHarness.boot(tmp.resolve("s"), tmp.resolve("p"), props);

        // 1.2.0 相对出厂 1.0.0 是升级，但低于最低兼容版本
        new TestPackageBuilder(d.packages, "v12").version("1.2.0").build();
        UpgradeRecord rec = d.service.upgradeFromPackage("v12");
        assertNull(rec);
        assertEquals(RejectReason.VERSION_BELOW_MIN_COMPATIBLE,
                d.service.currentState().lastRejectReason());
        assertEquals("1.0.0",
                d.service.currentState().currentRunningVersion().toString());
        assertTrue(d.service.history().isEmpty(), "策略拒绝不写历史");
    }

    @Test
    void minimumCompatibleAlsoBlocksDowngradeWhenDowngradeAllowed(@TempDir Path tmp)
            throws Exception {
        OtaProperties props = DeviceHarness.defaultProps(
                tmp.resolve("s"), tmp.resolve("p"));
        props.setAllowDowngrade(true);
        props.setMinimumCompatibleVersion("1.5.0");
        DeviceHarness d = DeviceHarness.boot(tmp.resolve("s"), tmp.resolve("p"), props);

        new TestPackageBuilder(d.packages, "v2").version("2.0.0").build();
        d.service.upgradeFromPackage("v2");
        new TestPackageBuilder(d.packages, "v10")
                .version("1.0.0").seed((byte) 5).build();
        d.service.upgradeFromPackage("v10");
        assertEquals(RejectReason.VERSION_BELOW_MIN_COMPATIBLE,
                d.service.currentState().lastRejectReason());
        assertEquals("2.0.0",
                d.service.currentState().currentRunningVersion().toString());
    }
}
