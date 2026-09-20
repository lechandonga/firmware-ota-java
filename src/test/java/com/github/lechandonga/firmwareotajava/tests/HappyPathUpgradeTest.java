package com.github.lechandonga.firmwareotajava.tests;

import com.github.lechandonga.firmwareotajava.model.UpgradeRecord;
import com.github.lechandonga.firmwareotajava.model.UpgradeState;
import com.github.lechandonga.firmwareotajava.model.UpdatePhase;
import com.github.lechandonga.firmwareotajava.model.Version;
import com.github.lechandonga.firmwareotajava.tests.support.DeviceHarness;
import com.github.lechandonga.firmwareotajava.tests.support.TestPackageBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 正常升级路径：1.0.0 -> 2.0.0，确认后 lastGood 更新、历史可查、重启仍运行新版本。 */
class HappyPathUpgradeTest {

    @Test
    void upgradeSucceedsAtomicallyAndPersistsAcrossReboot(@TempDir Path tmp) throws Exception {
        Path storage = tmp.resolve("storage");
        Path packages = tmp.resolve("packages");
        DeviceHarness device = DeviceHarness.boot(storage, packages);

        assertEquals("1.0.0", device.service.currentState().currentRunningVersion().toString());
        assertEquals(UpdatePhase.IDLE, device.service.currentState().phase());

        new TestPackageBuilder(packages, "v2")
                .version("2.0.0").payloadSize(10_000).build();

        UpgradeRecord record = device.service.upgradeFromPackage("v2");
        assertNotNull(record);
        assertEquals("SUCCEEDED", record.outcome());
        assertNull(record.rejectReason());
        assertNull(record.failureReason());
        assertFalse(record.resumed());

        UpgradeState state = device.service.currentState();
        assertEquals(new Version(2, 0, 0), state.currentRunningVersion());
        assertEquals(new Version(2, 0, 0), state.lastGoodVersion());
        assertEquals(UpdatePhase.IDLE, state.phase());
        assertEquals("B", state.activeSlot(), "新版本应安装并切换到另一槽位");

        List<UpgradeRecord> history = device.service.history();
        assertEquals(1, history.size());
        assertEquals("SUCCEEDED", history.get(0).outcome());

        // 掉电重启：必须确定地继续运行已确认的 2.0.0
        DeviceHarness afterReboot = device.reboot();
        assertEquals("2.0.0",
                afterReboot.service.currentState().currentRunningVersion().toString());
        assertEquals(UpdatePhase.IDLE, afterReboot.service.currentState().phase());
    }

    @Test
    void bootWithoutStateInstallsFactoryFirmware(@TempDir Path tmp) {
        DeviceHarness device = DeviceHarness.boot(tmp.resolve("s"), tmp.resolve("p"));
        assertEquals("1.0.0",
                device.service.currentState().currentRunningVersion().toString());
    }
}
