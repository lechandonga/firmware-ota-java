package com.github.lechandonga.firmwareotajava.ota.tests.ota;

import com.github.lechandonga.firmwareotajava.ota.model.UpgradePhase;
import com.github.lechandonga.firmwareotajava.ota.model.UpgradeRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安装（写非活动槽）期间掉电：
 * 在位版本始终可运行；simulateReboot 后确定落在一份完整版本；恢复后升级可完成。
 */
class InstallPowerLossTest {

    @TempDir
    Path tmp;

    private byte[] payload200() {
        byte[] data = new byte[200];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ('a' + i % 26);
        }
        return data;
    }

    @Test
    void powerLossDuringInstallKeepsOldVersionBootableAndResumeCompletes() throws Exception {
        TestEnv env = TestEnv.create(tmp, 32, 64L * 1024 * 1024, 256L * 1024 * 1024, false, "");
        Path pkg = env.buildPackage("p2", "2.0.0", payload200());

        // staging 完成后、写槽位到 100 字节处掉电
        env.faults.setFailInstallAfterBytes(100);
        UpgradeRecord interrupted;
        try {
            interrupted = env.service.startUpgrade("u-pl", pkg);
        } catch (RuntimeException e) {
            // 安装期故障按“失败但在位版本可用”向上抛出
            interrupted = env.service.getUpgrade("u-pl");
        }
        assertEquals(UpgradePhase.INSTALLING, interrupted.getPhase());

        // 在位版本仍是出厂版本，且 A 槽完好
        assertEquals("1.0.0", env.activeVersion());
        assertTrue(env.slots.verifySlot("a"), "活动槽 A 必须保持完整可运行");
        assertFalse(env.slots.verifySlot("b"), "半成品 B 槽没有可信 image.json/完整负载");
        assertFalse(Files.exists(env.storage.slotDir("b").resolve("payload.incoming")),
                "掉电临时文件应被清理");
        // layout 从未切换
        assertEquals("a", env.slots.layout().getActiveSlot());
        assertEquals(null, env.slots.layout().getPendingSlot());

        // 模拟重新上电：必须正常启动旧版本（而不是半成品）
        env.boot.simulateReboot();
        assertEquals("1.0.0", env.activeVersion());
        assertTrue(env.boot.lastBootDecision().startsWith("NORMAL_BOOT"));

        // 清除故障，用同一 upgradeId 恢复，最终完成升级
        env.faults.reset();
        UpgradeRecord done = env.service.startUpgrade("u-pl", pkg);
        assertEquals(UpgradePhase.COMMITTED, done.getPhase());
        assertEquals("2.0.0", env.activeVersion());
        assertTrue(env.slots.verifySlot("b"));
    }

    @Test
    void rebootAfterSuccessfulSwitchButBeforeCommitStaysDeterministic() {
        TestEnv env = TestEnv.createDefault(tmp);
        Path pkg = env.buildPackage("p2", "2.0.0", "firmware version two payload");

        UpgradeRecord done = env.service.startUpgrade("u-switch", pkg);
        assertEquals(UpgradePhase.COMMITTED, done.getPhase());

        // 再次“掉电重启”：已转正版本必须继续确定启动
        env.boot.simulateReboot();
        assertEquals("2.0.0", env.activeVersion());
        assertTrue(env.boot.lastBootDecision().startsWith("NORMAL_BOOT"));
        assertTrue(env.slots.verifySlot(env.slots.layout().getActiveSlot()));
    }
}
