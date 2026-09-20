package com.github.lechandonga.firmwareotajava.ota.tests.ota;

import com.github.lechandonga.firmwareotajava.ota.model.UpgradePhase;
import com.github.lechandonga.firmwareotajava.ota.model.UpgradeRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 断点续传场景：写入中断后只重传未通过校验的部分；
 * 已写入但未通过校验的分块绝不作为有效数据；最终升级成功。
 */
class ResumableTransferTest {

    @TempDir
    Path tmp;

    private byte[] payload100() {
        // 100 字节，分块 32 -> 4 块（32/32/32/4）
        byte[] data = new byte[100];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ('A' + (i % 26));
        }
        return data;
    }

    @Test
    void interruptedTransferResumesAndOnlyRewritesUnverifiedChunks() throws Exception {
        TestEnv env = TestEnv.create(tmp, 32, 64L * 1024 * 1024, 256L * 1024 * 1024, false, "");
        Path pkg = env.buildPackage("p2", "2.0.0", payload100());

        // 第一次：在处理到约 50 字节处中断（第 2 块写到一半），
        // 编排层捕获中断并以 STAGING 中间态返回（可断点续传）
        env.faults.setFailAfterBytes(50);
        UpgradeRecord interrupted = env.service.startUpgrade("u-resume", pkg);
        assertEquals(UpgradePhase.STAGING, interrupted.getPhase());

        // 第 1 块（32 字节）已校验落盘；第 2 块残片必须被删除
        UpgradeRecord mid = env.service.getUpgrade("u-resume");
        assertEquals(UpgradePhase.STAGING, mid.getPhase());
        assertTrue(Files.exists(env.storage.chunkFile("u-resume", 0)),
                "已通过校验的分块 0 应保留");
        assertFalse(Files.exists(env.storage.chunkFile("u-resume", 1)),
                "未完成的分块 1 残片必须删除，不得当作有效数据");
        assertEquals(1L, Files.list(env.storage.chunkDir("u-resume")).count());

        // 第二次：清除故障后用同一 upgradeId 续传 -> 成功
        env.faults.reset();
        UpgradeRecord done = env.service.startUpgrade("u-resume", pkg);
        assertEquals(UpgradePhase.COMMITTED, done.getPhase());
        assertEquals("2.0.0", env.activeVersion());
        assertTrue(env.slots.verifySlot("b"));
    }

    @Test
    void repeatedInterruptionsEventuallyComplete() {
        TestEnv env = TestEnv.create(tmp, 32, 64L * 1024 * 1024, 256L * 1024 * 1024, false, "");
        Path pkg = env.buildPackage("p2", "2.0.0", payload100());

        // 连续三次在不同进度中断
        long[] thresholds = {33, 70, 90};
        for (long t : thresholds) {
            env.faults.setFailAfterBytes(t);
            UpgradeRecord r = env.service.startUpgrade("u-multi", pkg);
            assertEquals(UpgradePhase.STAGING, r.getPhase(),
                    "阈值 " + t + " 处中断后应停在 STAGING");
        }
        env.faults.reset();
        UpgradeRecord done = env.service.startUpgrade("u-multi", pkg);
        assertEquals(UpgradePhase.COMMITTED, done.getPhase());
        assertEquals("2.0.0", env.activeVersion());
    }

    @Test
    void duplicateUpgradeIdAfterCommitDoesNotRewrite() {
        TestEnv env = TestEnv.createDefault(tmp);
        Path pkg = env.buildPackage("p2", "2.0.0", "fw2");
        UpgradeRecord first = env.service.startUpgrade("u-idem", pkg);
        assertEquals(UpgradePhase.COMMITTED, first.getPhase());

        // 同一 upgradeId 再次请求：直接返回同一条记录，不产生新写入
        UpgradeRecord second = env.service.startUpgrade("u-idem", pkg);
        assertEquals(UpgradePhase.COMMITTED, second.getPhase());
        assertEquals(first.getStartedAt(), second.getStartedAt());
        assertFalse(Files.exists(env.storage.upgradeDir("u-idem")),
                "已完成升级的 staging 临时目录应已清理，重复请求不重建");
    }

    @Test
    void unverifiedChunkOnDiskIsRecheckedAndRejectedAfterResume() throws Exception {
        TestEnv env = TestEnv.create(tmp, 32, 64L * 1024 * 1024, 256L * 1024 * 1024, false, "");
        Path pkg = env.buildPackage("p2", "2.0.0", payload100());

        // 先完成第一块
        env.faults.setFailAfterBytes(40);
        UpgradeRecord r = env.service.startUpgrade("u-dirty", pkg);
        assertEquals(UpgradePhase.STAGING, r.getPhase());
        assertTrue(Files.exists(env.storage.chunkFile("u-dirty", 0)));

        // 手工把已“校验通过”的分块 0 改坏（模拟掉电导致的落盘损坏），
        // 恢复时必须重新复核并判为无效，触发重传而不是使用脏数据
        Path chunk0 = env.storage.chunkFile("u-dirty", 0);
        byte[] bad = Files.readAllBytes(chunk0);
        bad[0] = (byte) (bad[0] ^ 0xFF);
        Files.write(chunk0, bad);

        env.faults.reset();
        UpgradeRecord done = env.service.startUpgrade("u-dirty", pkg);
        assertEquals(UpgradePhase.COMMITTED, done.getPhase());
        assertEquals("2.0.0", env.activeVersion());
        byte[] active = Files.readAllBytes(env.storage.slotPayload(env.slots.layout().getActiveSlot()));
        assertEquals(new String(payload100(), StandardCharsets.UTF_8),
                new String(active, StandardCharsets.UTF_8));
    }
}
