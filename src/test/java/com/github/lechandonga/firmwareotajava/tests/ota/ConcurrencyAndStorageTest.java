package com.github.lechandonga.firmwareotajava.ota.tests.ota;

import com.github.lechandonga.firmwareotajava.ota.model.RejectReason;
import com.github.lechandonga.firmwareotajava.ota.model.UpgradePhase;
import com.github.lechandonga.firmwareotajava.ota.model.UpgradeRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发与存储策略：
 * 同一目标不得并行升级；重复请求按幂等语义处理；存储超限时拒绝且释放临时资源。
 */
class ConcurrencyAndStorageTest {

    @TempDir
    Path tmp;

    @Test
    void concurrentRequestsForSameUpgradeIdProduceSingleHistoryEntry() throws Exception {
        TestEnv env = TestEnv.createDefault(tmp);
        Path pkg = env.buildPackage("p2", "2.0.0", "concurrent firmware payload");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<UpgradeRecord>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return env.service.startUpgrade("u-concurrent", pkg);
            }));
        }
        start.countDown();
        for (Future<UpgradeRecord> f : futures) {
            assertEquals(UpgradePhase.COMMITTED, f.get(30, TimeUnit.SECONDS).getPhase());
        }
        pool.shutdown();

        long count = env.service.history().stream()
                .filter(r -> "u-concurrent".equals(r.getUpgradeId()))
                .count();
        assertEquals(1, count, "同一 upgradeId 只能产生一条升级记录，不得重复写入");
        assertEquals("2.0.0", env.activeVersion());
    }

    @Test
    void parallelDifferentUpgradesAreSerializedNotCorrupted() throws Exception {
        TestEnv env = TestEnv.createDefault(tmp);
        Path p2 = env.buildPackage("p2", "2.0.0", "firmware two payload data");
        // 构造两个不同 upgradeId 指向同一目标版本；服务串行化后第二个应判为已安装/冲突
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<UpgradeRecord> f1 = pool.submit(() -> {
            start.await();
            return env.service.startUpgrade("u-a", p2);
        });
        Future<UpgradeRecord> f2 = pool.submit(() -> {
            start.await();
            return env.service.startUpgrade("u-b", p2);
        });
        start.countDown();
        UpgradeRecord r1 = f1.get(30, TimeUnit.SECONDS);
        UpgradeRecord r2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 恰好一个升级成功提交，另一个得到明确的拒绝/冲突结论，二者都不得产生半完成状态
        List<UpgradeRecord> finals = List.of(r1, r2);
        long committed = finals.stream().filter(r -> r.getPhase() == UpgradePhase.COMMITTED).count();
        assertEquals(1, committed);
        UpgradeRecord other = finals.stream()
                .filter(r -> r.getPhase() != UpgradePhase.COMMITTED).findFirst().orElseThrow();
        assertTrue(other.getPhase() == UpgradePhase.REJECTED
                        && other.getRejectReason() == RejectReason.ALREADY_INSTALLED,
                "并发竞争失败方必须得到可解释结论，实际: " + other.getPhase()
                        + " / " + other.getRejectReason());
        assertEquals("2.0.0", env.activeVersion());
    }

    @Test
    void newUpgradeBlockedWhilePendingSelfTest() {
        TestEnv env = TestEnv.createDefault(tmp);
        // 手工制造 pending 状态：先成功升级，再把 layout 改回 pending 形态
        Path p2 = env.buildPackage("p2", "2.0.0", "fw2 payload");
        assertEquals(UpgradePhase.COMMITTED, env.service.startUpgrade("u1", p2).getPhase());

        var layout = env.slots.layout();
        layout.setPendingSlot(layout.getActiveSlot());
        layout.setPendingUpgradeId("u1");
        env.slots.writeLayout(layout);

        Path p3 = env.buildPackage("p3", "3.0.0", "fw3 payload");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> env.service.startUpgrade("u2", p3));
        assertTrue(ex.getMessage().contains("待自检"));
    }

    @Test
    void insufficientStorageRejectsAndReleasesTempResources() throws Exception {
        // 上限 400 字节：出厂镜像占少量空间，再写入一个 200 字节镜像会超预算
        TestEnv env = TestEnv.create(tmp, 32, 64L * 1024 * 1024, 400L, false, "");
        byte[] big = new byte[200];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) ('m');
        }
        Path pkg = env.buildPackage("p2", "2.0.0", big);

        UpgradeRecord rec = env.service.startUpgrade("u-space", pkg);
        assertEquals(RejectReason.INSUFFICIENT_STORAGE, rec.getRejectReason());
        assertEquals("1.0.0", env.activeVersion());
        try (var walk = Files.walk(env.storage.stagingDir())) {
            assertEquals(0L, walk.filter(Files::isRegularFile).count(),
                    "存储不足拒绝后必须释放全部临时资源");
        }
        assertTrue(env.service.history().isEmpty(), "预检拒绝不得写入历史");
    }

    @Test
    void statusAndHistoryRemainExplainableAfterOperations() {
        TestEnv env = TestEnv.createDefault(tmp);
        env.service.startUpgrade("u-ok", env.buildPackage("p2", "2.0.0", "fw2"));
        var status = env.service.status();
        assertEquals("2.0.0", status.activeVersion());
        assertEquals(null, status.pendingVersion());
        assertEquals(null, status.activeUpgradePhase());
        assertTrue(status.storageUsedBytes() <= status.storageMaxBytes());
        assertEquals(1, status.bootCount(), "升级过程触发一次启动自检（单元测试未走 Spring 首次启动）");
    }
}
