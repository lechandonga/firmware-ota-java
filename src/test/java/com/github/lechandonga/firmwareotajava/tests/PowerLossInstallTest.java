package com.github.lechandonga.firmwareotajava.tests;

import com.github.lechandonga.firmwareotajava.model.UpdatePhase;
import com.github.lechandonga.firmwareotajava.model.UpgradeRecord;
import com.github.lechandonga.firmwareotajava.tests.support.DeviceHarness;
import com.github.lechandonga.firmwareotajava.tests.support.TestPackageBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 安装阶段掉电：候选槽 payload/manifest 已写、但 VERSION 提交标记未落盘。
 * 任何重启都必须确定运行旧版本；候选槽不被承认为完整版本；
 * 重新升级仍可成功（残留的半安装候选槽被重新擦写）。
 */
class PowerLossInstallTest {

    @Test
    void crashBeforeSlotCommitKeepsOldVersionBootableAndUpgradeRetryable(@TempDir Path tmp)
            throws Exception {
        DeviceHarness d = DeviceHarness.boot(tmp.resolve("s"), tmp.resolve("p"));
        new TestPackageBuilder(d.packages, "v2")
                .version("2.0.0").payloadSize(10_000).chunkSize(4096).build();

        d.installer.setCrashBeforeCommit(true);
        UpgradeRecord rec = d.service.upgradeFromPackage("v2");
        assertNull(rec, "安装掉电时升级未完成");
        d.installer.setCrashBeforeCommit(false);

        // 候选槽 B 缺少 VERSION 提交标记，不被视为完整版本
        assertFalse(d.installer.isSlotComplete("B"),
                "提交标记缺失的槽位不得被当作完整版本");
        // current 仍指向 A，旧版本持续可运行
        assertEquals("A", d.switcher.currentSlotName());
        assertEquals("1.0.0",
                d.service.currentState().currentRunningVersion().toString());

        // 掉电重启：确定仍在 1.0.0，状态可解释为 INTERRUPTED
        DeviceHarness rebooted = d.reboot();
        assertEquals("1.0.0", rebooted.service.currentState()
                .currentRunningVersion().toString());
        assertEquals(UpdatePhase.INTERRUPTED,
                rebooted.service.currentState().phase());
        assertEquals("A", rebooted.switcher.currentSlotName());
        // 恢复时半安装候选槽已被擦除，无脏数据
        assertFalse(Files.exists(rebooted.storage.resolve("slots/B/payload.bin")),
                "半安装候选槽的 payload 残留必须被清理");

        // 重新升级同一包：成功
        UpgradeRecord done = rebooted.service.upgradeFromPackage("v2");
        assertNotNull(done);
        assertEquals("SUCCEEDED", done.outcome());
        assertEquals("2.0.0", rebooted.service.currentState()
                .currentRunningVersion().toString());
        assertEquals("B", rebooted.switcher.currentSlotName());
    }
}
