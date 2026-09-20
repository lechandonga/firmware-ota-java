package com.github.lechandonga.firmwareotajava.tests;

import com.github.lechandonga.firmwareotajava.model.UpgradeRecord;
import com.github.lechandonga.firmwareotajava.model.UpdatePhase;
import com.github.lechandonga.firmwareotajava.model.UpgradeState;
import com.github.lechandonga.firmwareotajava.tests.support.DeviceHarness;
import com.github.lechandonga.firmwareotajava.tests.support.TestPackageBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 断点续传：传输/写入中断后，重新升级只处理未通过校验的块；
 * 已写入但未通过校验的数据不被当作有效数据，且不残留脏临时文件。
 */
class ResumableTransferTest {

    /** 10000 字节 / 4096 = 3 块；传完 2 块后中断。 */
    @Test
    void interruptedTransferResumesFromVerifiedBoundary(@TempDir Path tmp) throws Exception {
        DeviceHarness d = DeviceHarness.boot(tmp.resolve("s"), tmp.resolve("p"));
        new TestPackageBuilder(d.packages, "v2")
                .version("2.0.0").payloadSize(10_000).chunkSize(4096).build();

        // 第一次：传 2 块后中断
        d.service.enableWriteInterruptAfterChunks(2);
        UpgradeRecord first = d.service.upgradeFromPackage("v2");
        assertNull(first, "中断时升级尚未完成，无最终记录");
        d.service.disableWriteInterrupt();

        UpgradeState mid = d.service.currentState();
        assertEquals(UpdatePhase.INTERRUPTED, mid.phase());
        assertEquals(2, mid.verifiedChunks());
        assertEquals(3, mid.totalChunks());
        assertEquals("1.0.0", mid.currentRunningVersion().toString(), "中断期间继续运行旧版本");

        // 暂存目录：只有 2 个 .part（已校验），不得残留 .wr（未完成）脏文件
        Path chunks = d.storage.resolve("staging/upg-v2/chunks");
        try (Stream<Path> files = Files.list(chunks)) {
            long wr = files.filter(p -> p.toString().endsWith(".wr")).count();
            assertEquals(0, wr, "未完成写入的 .wr 不得残留");
        }
        try (Stream<Path> files = Files.list(chunks)) {
            assertEquals(2, files.filter(p -> p.toString().endsWith(".part")).count());
        }

        // 掉电后重启：恢复到 INTERRUPTED，运行版本仍是 1.0.0
        DeviceHarness rebooted = d.reboot();
        assertEquals(UpdatePhase.INTERRUPTED,
                rebooted.service.currentState().phase());
        assertEquals("1.0.0",
                rebooted.service.currentState().currentRunningVersion().toString());

        // 同包再升级：只传第 3 块，最终成功，记录标记 resumed=true
        UpgradeRecord done = rebooted.service.upgradeFromPackage("v2");
        assertNotNull(done);
        assertEquals("SUCCEEDED", done.outcome());
        assertTrue(done.resumed(), "由中断恢复完成的升级应标记为 resumed");
        assertEquals("2.0.0",
                rebooted.service.currentState().currentRunningVersion().toString());

        // 成功后暂存临时资源被释放
        assertFalse(Files.exists(rebooted.storage.resolve("staging/upg-v2")),
                "升级成功后不得残留暂存目录");
    }

    @Test
    void resumeUsesOnlyChecksumVerifiedChunks(@TempDir Path tmp) throws Exception {
        DeviceHarness d = DeviceHarness.boot(tmp.resolve("s"), tmp.resolve("p"));
        new TestPackageBuilder(d.packages, "v2")
                .version("2.0.0").payloadSize(4096 * 4).chunkSize(4096).build();

        d.service.enableWriteInterruptAfterChunks(1);
        d.service.upgradeFromPackage("v2");
        d.service.disableWriteInterrupt();

        // 人为放置一个“写了但未校验”的脏块文件（位图中不存在）
        Path chunks = d.storage.resolve("staging/upg-v2/chunks");
        Files.createDirectories(chunks);
        Path dirty = chunks.resolve("000002.part");
        Files.writeString(dirty, "garbage-not-verified");

        DeviceHarness rebooted = d.reboot();
        UpgradeRecord done = rebooted.service.upgradeFromPackage("v2");
        assertEquals("SUCCEEDED", done.outcome());
        assertEquals("2.0.0",
                rebooted.service.currentState().currentRunningVersion().toString());
        // 脏块若被当作有效数据，组装哈希会失败；成功即证明它没有被采用
    }

    @Test
    void repeatedRequestWhileInterruptedDoesNotDuplicateWork(@TempDir Path tmp) throws Exception {
        DeviceHarness d = DeviceHarness.boot(tmp.resolve("s"), tmp.resolve("p"));
        new TestPackageBuilder(d.packages, "v2")
                .version("2.0.0").payloadSize(10_000).chunkSize(4096).build();
        d.service.enableWriteInterruptAfterChunks(1);
        d.service.upgradeFromPackage("v2");
        d.service.disableWriteInterrupt();

        // 再次请求同一包：从第 1 块边界续传并完成，不重置进度
        UpgradeRecord done = d.service.upgradeFromPackage("v2");
        assertNotNull(done);
        assertEquals("SUCCEEDED", done.outcome());
        assertTrue(done.resumed());
    }
}
