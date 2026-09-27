package com.github.lechandonga.firmwareotajava.tests;

import com.github.lechandonga.firmwareotajava.config.OtaProperties;
import com.github.lechandonga.firmwareotajava.model.FailureReason;
import com.github.lechandonga.firmwareotajava.model.UpdatePhase;
import com.github.lechandonga.firmwareotajava.model.UpgradeRecord;
import com.github.lechandonga.firmwareotajava.model.UpgradeState;
import com.github.lechandonga.firmwareotajava.tests.support.DeviceHarness;
import com.github.lechandonga.firmwareotajava.tests.support.TestPackageBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 设备存储不可靠兜底：上次中断后，设备上已保存的暂存数据被损坏
 * （闪存老化/坏块/翻位），再次续传时系统自行发现、只重处理损坏部分，
 * 升级正常走完；重处理次数有上限，超限以明确失败原因收场并释放临时空间。
 */
class StagingCorruptionRecoveryTest {

    /** 把某个已暂存块的内容翻位，模拟设备存储上的数据损坏。 */
    private static void corruptStagedChunk(Path storage, String upgradeId, int index)
            throws IOException {
        Path part = storage.resolve(
                "staging/" + upgradeId + "/chunks/" + String.format("%06d.part", index));
        byte[] data = Files.readAllBytes(part);
        data[0] ^= (byte) 0xFF;
        data[data.length - 1] ^= (byte) 0x0F;
        Files.write(part, data);
    }

    @Test
    void corruptedStagedDataIsDetectedAndRepairedOnResume(@TempDir Path tmp)
            throws Exception {
        DeviceHarness d = DeviceHarness.boot(tmp.resolve("s"), tmp.resolve("p"));
        new TestPackageBuilder(d.packages, "v2")
                .version("2.0.0").payloadSize(4096 * 5).chunkSize(4096).build();

        // 第一次：传完 3/5 块后中断
        d.service.enableWriteInterruptAfterChunks(3);
        assertNull(d.service.upgradeFromPackage("v2"));
        d.service.disableWriteInterrupt();
        assertEquals(3, d.service.currentState().verifiedChunks());

        // 掉电 + 设备上已保存的第 1 块内容损坏（位图仍在，模拟翻位/坏块）
        corruptStagedChunk(d.storage, "upg-v2", 1);
        DeviceHarness rebooted = d.reboot();
        UpgradeState afterReboot = rebooted.service.currentState();
        assertEquals(UpdatePhase.INTERRUPTED, afterReboot.phase());
        assertEquals("1.0.0", afterReboot.currentRunningVersion().toString());

        // 再次续传：自行发现损坏块、只重处理该块，升级正常完成
        UpgradeRecord done = rebooted.service.upgradeFromPackage("v2");
        assertNotNull(done);
        assertEquals("SUCCEEDED", done.outcome(), "修复后升级应正常走完");
        assertTrue(done.resumed());
        assertEquals(1, done.repairedChunks(),
                "只有真正损坏的 1 个块被重处理，未受影响的部分不重复处理");
        assertNull(done.rejectReason());
        assertNull(done.failureReason());
        assertEquals("2.0.0",
                rebooted.service.currentState().currentRunningVersion().toString());

        // 重启后仍稳定运行新版本，临时空间已释放
        DeviceHarness again = rebooted.reboot();
        assertEquals("2.0.0",
                again.service.currentState().currentRunningVersion().toString());
        assertFalse(Files.exists(again.storage.resolve("staging/upg-v2")),
                "成功后不得残留暂存目录");
    }

    @Test
    void repairAttemptsExceededFailsWithDistinctReasonAndReleasesStorage(
            @TempDir Path tmp) throws Exception {
        OtaProperties props = DeviceHarness.defaultProps(
                tmp.resolve("s"), tmp.resolve("p"));
        props.setMaxRepairAttempts(1);
        DeviceHarness d = DeviceHarness.boot(tmp.resolve("s"), tmp.resolve("p"), props);
        new TestPackageBuilder(d.packages, "v2")
                .version("2.0.0").payloadSize(4096 * 5).chunkSize(4096).build();

        // 中断在 3/5；损坏已保存的第 1 块
        d.service.enableWriteInterruptAfterChunks(3);
        d.service.upgradeFromPackage("v2");
        corruptStagedChunk(d.storage, "upg-v2", 1);

        // 第一次续传：修复 1 块（第 1 次重处理，未超上限），但在 4/5 处再次中断
        d.service.enableWriteInterruptAfterChunks(4);
        assertNull(d.service.upgradeFromPackage("v2"));
        d.service.disableWriteInterrupt();
        UpgradeState mid = d.service.currentState();
        assertEquals(UpdatePhase.INTERRUPTED, mid.phase());
        assertEquals(4, mid.verifiedChunks());
        assertEquals(1, mid.repairAttempts());
        assertEquals(1, mid.repairedChunks());

        // 已保存数据再次损坏：重处理将超上限 -> 明确失败收场
        corruptStagedChunk(d.storage, "upg-v2", 0);
        DeviceHarness rebooted = d.reboot();
        UpgradeRecord failed = rebooted.service.upgradeFromPackage("v2");
        assertNotNull(failed);
        assertEquals("FAILED", failed.outcome());
        assertEquals(FailureReason.STAGING_DATA_UNRECOVERABLE, failed.failureReason(),
                "超限失败原因必须与拒绝/回滚可区分");
        assertNull(failed.rejectReason());
        assertTrue(failed.repairedChunks() >= 1);

        // 在位版本保留、状态回到稳态、临时空间释放干净
        UpgradeState after = rebooted.service.currentState();
        assertEquals(UpdatePhase.IDLE, after.phase());
        assertEquals("1.0.0", after.currentRunningVersion().toString());
        assertEquals(FailureReason.STAGING_DATA_UNRECOVERABLE,
                after.lastFailureReason());
        assertFalse(Files.exists(rebooted.storage.resolve("staging/upg-v2")),
                "失败收场后不得残留暂存目录，避免无界占用");

        // 设备并未被卡死：来源包完好，重新发起同一升级可从零正常完成
        UpgradeRecord retry = rebooted.service.upgradeFromPackage("v2");
        assertNotNull(retry);
        assertEquals("SUCCEEDED", retry.outcome());
        assertFalse(retry.resumed(), "失败收场后重新发起是全新升级");
        assertEquals(0, retry.repairedChunks());
        assertEquals("2.0.0",
                rebooted.service.currentState().currentRunningVersion().toString());
    }

    @Test
    void healthyResumedUpgradeReportsNoRepair(@TempDir Path tmp) throws Exception {
        DeviceHarness d = DeviceHarness.boot(tmp.resolve("s"), tmp.resolve("p"));
        new TestPackageBuilder(d.packages, "v2")
                .version("2.0.0").payloadSize(10_000).chunkSize(4096).build();
        d.service.enableWriteInterruptAfterChunks(2);
        d.service.upgradeFromPackage("v2");
        d.service.disableWriteInterrupt();

        UpgradeRecord done = d.reboot().service.upgradeFromPackage("v2");
        assertNotNull(done);
        assertEquals("SUCCEEDED", done.outcome());
        assertEquals(0, done.repairedChunks(),
                "暂存数据完好的普通续传不应报告重处理");
    }
}
