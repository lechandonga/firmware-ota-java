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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 设备存储不可靠场景：上次中断后，已暂存（位图承认）的升级数据在设备上被
 * 破坏（坏块/翻位/半截写入）。再次续传时系统应自行发现、只重处理损坏部分，
 * 升级最终正常完成；重处理次数超过上限则按明确原因失败收场并释放暂存。
 */
class CorruptStagingRecoveryTest {

    /** 翻转指定暂存块文件的一个字节，模拟闪存翻位/坏块。 */
    private static void corruptChunk(Path storage, String upgradeId, int index) throws Exception {
        Path chunk = storage.resolve("staging/" + upgradeId + "/chunks")
                .resolve(String.format("%06d.part", index));
        byte[] data = Files.readAllBytes(chunk);
        data[0] ^= (byte) 0xFF;
        Files.write(chunk, data);
    }

    @Test
    void corruptedStagedChunksAreRepairedOnResumeAfterReboot(@TempDir Path tmp) throws Exception {
        DeviceHarness d = DeviceHarness.boot(tmp.resolve("s"), tmp.resolve("p"));
        new TestPackageBuilder(d.packages, "v2")
                .version("2.0.0").payloadSize(4096 * 4).chunkSize(4096).build();

        // 第一次：传完 3/4 块后中断
        d.service.enableWriteInterruptAfterChunks(3);
        assertNull(d.service.upgradeFromPackage("v2"));
        d.service.disableWriteInterrupt();
        assertEquals(3, d.service.currentState().verifiedChunks());

        // 设备存储不可靠：位图承认的第 1 块内容翻位、第 2 块文件丢失
        corruptChunk(d.storage, "upg-v2", 1);
        Files.delete(d.storage.resolve("staging/upg-v2/chunks/000002.part"));

        // 掉电重启：仍是中断待续传视角，运行旧版本
        DeviceHarness rebooted = d.reboot();
        assertEquals(UpdatePhase.INTERRUPTED, rebooted.service.currentState().phase());
        assertEquals("1.0.0",
                rebooted.service.currentState().currentRunningVersion().toString());

        // 再次续传：审计发现 2 个块不可用，只重传这 2 块，升级正常走完
        UpgradeRecord done = rebooted.service.upgradeFromPackage("v2");
        assertNotNull(done);
        assertEquals("SUCCEEDED", done.outcome());
        assertTrue(done.resumed());
        assertEquals(2, done.repairedChunks(),
                "只有损坏的 2 块被重处理，未受影响的部分不重复处理");
        assertEquals("2.0.0",
                rebooted.service.currentState().currentRunningVersion().toString());
        assertFalse(Files.exists(rebooted.storage.resolve("staging/upg-v2")),
                "成功后暂存空间必须释放干净");
    }

    @Test
    void progressReflectsOnlyUsableDataAfterEviction(@TempDir Path tmp) throws Exception {
        DeviceHarness d = DeviceHarness.boot(tmp.resolve("s"), tmp.resolve("p"));
        new TestPackageBuilder(d.packages, "v2")
                .version("2.0.0").payloadSize(4096 * 4).chunkSize(4096).build();

        d.service.enableWriteInterruptAfterChunks(3);
        d.service.upgradeFromPackage("v2");

        // 破坏一个已暂存块，然后带着“传 3 块再中断”的钩子续传：
        // 审计剔除坏块后进度必须回退到真实可用数据，而不是停在 75% 假象
        corruptChunk(d.storage, "upg-v2", 0);
        DeviceHarness rebooted = d.reboot();
        // 中断钩子是进程内测试钩子，重启后需重新挂上
        rebooted.service.enableWriteInterruptAfterChunks(3);
        assertNull(rebooted.service.upgradeFromPackage("v2"), "再次中断，无最终记录");
        rebooted.service.disableWriteInterrupt();

        UpgradeState mid = rebooted.service.currentState();
        assertEquals(UpdatePhase.INTERRUPTED, mid.phase());
        assertEquals(3, mid.verifiedChunks());
        assertEquals(4, mid.totalChunks());
        assertEquals(75, mid.progressPercent());
        assertEquals(1, mid.repairAttempts());
        assertEquals(1, mid.repairedChunks());

        // 恢复完成后记录可见“有数据被判定不可用并重处理”
        UpgradeRecord done = rebooted.service.upgradeFromPackage("v2");
        assertEquals("SUCCEEDED", done.outcome());
        assertEquals(1, done.repairedChunks());
    }

    @Test
    void repairAttemptsExhaustedFailsUpgradeAndReclaimsStorage(@TempDir Path tmp) throws Exception {
        OtaProperties props = DeviceHarness.defaultProps(tmp.resolve("s"), tmp.resolve("p"));
        props.setMaxRepairAttempts(1);
        DeviceHarness d = DeviceHarness.boot(tmp.resolve("s"), tmp.resolve("p"), props);
        new TestPackageBuilder(d.packages, "v2")
                .version("2.0.0").payloadSize(4096 * 4).chunkSize(4096).build();

        // 中断在 2/4 块；之后每一次续传前都再次破坏暂存数据
        d.service.enableWriteInterruptAfterChunks(2);
        d.service.upgradeFromPackage("v2");

        // 第 1 次续传：审计剔除坏块（第 1 次重处理），重传后再次中断
        corruptChunk(d.storage, "upg-v2", 0);
        assertNull(d.service.upgradeFromPackage("v2"));
        assertEquals(1, d.service.currentState().repairAttempts());

        // 第 2 次续传：再次发现暂存损坏，重处理次数超限 -> 明确失败收场
        corruptChunk(d.storage, "upg-v2", 0);
        DeviceHarness rebooted = d.reboot();
        UpgradeRecord failed = rebooted.service.upgradeFromPackage("v2");
        rebooted.service.disableWriteInterrupt();

        assertNotNull(failed);
        assertEquals("FAILED", failed.outcome(), "超限是失败而非拒绝");
        assertEquals(FailureReason.STAGED_DATA_REPAIR_EXHAUSTED, failed.failureReason());
        assertNull(failed.rejectReason(), "失败与拒绝必须可区分");
        assertTrue(failed.repairedChunks() > 0);

        // 在位版本保留、临时空间释放干净、状态回到稳态
        UpgradeState after = rebooted.service.currentState();
        assertEquals(UpdatePhase.IDLE, after.phase());
        assertEquals("1.0.0", after.currentRunningVersion().toString());
        assertFalse(Files.exists(rebooted.storage.resolve("staging/upg-v2")),
                "失败收场后暂存空间必须释放，不得无界占用");

        // 同一升级可重新发起（全新尝试，计数复位），最终成功
        UpgradeRecord retry = rebooted.service.upgradeFromPackage("v2");
        assertNotNull(retry);
        assertEquals("SUCCEEDED", retry.outcome());
        assertFalse(retry.resumed());
        assertEquals(0, retry.repairedChunks());
        assertEquals("2.0.0",
                rebooted.service.currentState().currentRunningVersion().toString());
    }
}
