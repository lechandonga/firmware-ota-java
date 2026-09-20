package com.github.lechandonga.firmwareotajava.tests;

import com.github.lechandonga.firmwareotajava.config.OtaProperties;
import com.github.lechandonga.firmwareotajava.model.RejectReason;
import com.github.lechandonga.firmwareotajava.model.UpgradeRecord;
import com.github.lechandonga.firmwareotajava.model.UpdatePhase;
import com.github.lechandonga.firmwareotajava.model.UpgradeState;
import com.github.lechandonga.firmwareotajava.tests.support.DeviceHarness;
import com.github.lechandonga.firmwareotajava.tests.support.TestPackageBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** 并发互斥、重复请求幂等与存储占用上限。 */
class ConcurrencyAndLimitsTest {

    @Test
    void concurrentUpgradeRequestsAreSerializedAndDoNotCorruptState(@TempDir Path tmp)
            throws Exception {
        DeviceHarness d = DeviceHarness.boot(tmp.resolve("s"), tmp.resolve("p"));
        new TestPackageBuilder(d.packages, "v2")
                .version("2.0.0").payloadSize(20_000).chunkSize(4096).build();

        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        @SuppressWarnings("unchecked")
        Future<UpgradeRecord>[] futures = new Future[n];
        for (int i = 0; i < n; i++) {
            futures[i] = pool.submit(() -> {
                start.await();
                return d.service.upgradeFromPackage("v2");
            });
        }
        start.countDown();
        UpgradeRecord first = futures[0].get();
        assertNotNull(first);
        assertEquals("SUCCEEDED", first.outcome());
        for (Future<UpgradeRecord> f : futures) {
            // 后续请求（无论并发还是排队到其后）都幂等返回同一条成功记录
            assertEquals(first, f.get(), "重复请求不得产生重复升级/重复写入");
        }
        pool.shutdown();

        long succeededRecords = d.service.history().stream()
                .filter(r -> "SUCCEEDED".equals(r.outcome())).count();
        assertEquals(1, succeededRecords, "不得产生重复写入/重复成功记录");
        assertEquals("2.0.0",
                d.service.currentState().currentRunningVersion().toString());
    }

    @Test
    void repeatedRequestAfterSuccessIsIdempotent(@TempDir Path tmp) throws Exception {
        DeviceHarness d = DeviceHarness.boot(tmp.resolve("s"), tmp.resolve("p"));
        new TestPackageBuilder(d.packages, "v2").version("2.0.0").build();

        UpgradeRecord first = d.service.upgradeFromPackage("v2");
        UpgradeRecord second = d.service.upgradeFromPackage("v2");
        UpgradeRecord third = d.service.upgradeFromPackage("v2");
        assertEquals(first, second);
        assertEquals(second, third);
        assertEquals(1, d.service.history().size());
    }

    @Test
    void storageLimitExceededIsRejectedAndTemporaryResourcesReleased(@TempDir Path tmp)
            throws Exception {
        // 存储总上限 10 KiB，而一个升级包就 20 KiB：安装所需空间不足
        OtaProperties props = DeviceHarness.defaultProps(
                tmp.resolve("s"), tmp.resolve("p"));
        props.setMaxStorageBytes(10 * 1024L);
        props.setMaxPackageBytes(1024L * 1024L);
        DeviceHarness d = DeviceHarness.boot(tmp.resolve("s"), tmp.resolve("p"), props);

        new TestPackageBuilder(d.packages, "v2")
                .version("2.0.0").payloadSize(20_000).chunkSize(4096).build();
        UpgradeRecord rec = d.service.upgradeFromPackage("v2");
        assertEquals("REJECTED", rec.outcome());
        assertEquals(RejectReason.STORAGE_LIMIT_EXCEEDED, rec.rejectReason());
        assertEquals("1.0.0",
                d.service.currentState().currentRunningVersion().toString());

        // 拒绝后不得残留暂存目录，空间不被无界占用
        Path staging = d.storage.resolve("staging");
        if (Files.exists(staging)) {
            try (var walk = Files.walk(staging)) {
                long files = walk.filter(Files::isRegularFile).count();
                assertEquals(0, files, "拒绝后必须释放临时资源");
            }
        }
    }

    @Test
    void stateIsQueryableDuringInterruptedUpgrade(@TempDir Path tmp) throws Exception {
        DeviceHarness d = DeviceHarness.boot(tmp.resolve("s"), tmp.resolve("p"));
        new TestPackageBuilder(d.packages, "v2")
                .version("2.0.0").payloadSize(10_000).chunkSize(4096).build();
        d.service.enableWriteInterruptAfterChunks(1);
        d.service.upgradeFromPackage("v2");
        d.service.disableWriteInterrupt();

        UpgradeState s = d.service.currentState();
        assertEquals(UpdatePhase.INTERRUPTED, s.phase());
        assertTrue(s.progressPercent() > 0 && s.progressPercent() < 100);
        assertEquals(3, s.totalChunks());
    }
}
