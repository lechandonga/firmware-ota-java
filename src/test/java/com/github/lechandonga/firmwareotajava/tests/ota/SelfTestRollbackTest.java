package com.github.lechandonga.firmwareotajava.ota.tests.ota;

import com.github.lechandonga.firmwareotajava.ota.model.RollbackReason;
import com.github.lechandonga.firmwareotajava.ota.model.SelfTestMode;
import com.github.lechandonga.firmwareotajava.ota.model.UpgradePhase;
import com.github.lechandonga.firmwareotajava.ota.model.UpgradeRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新版本自检失败/超时必须自动回滚到最近确认版本；
 * 回滚结果与原因可查询且相互可区分；回滚记录不被后续升级删除。
 */
class SelfTestRollbackTest {

    @TempDir
    Path tmp;

    private Path markerPackage(TestEnv env, String name, String version, String payload, String marker) {
        Path dir = env.packageRoot.resolve(name);
        env.builder.build(dir, "DEMO-DEVICE", "1.0", version,
                payload.getBytes(StandardCharsets.UTF_8), env.props.getChunkSizeBytes(),
                null, SelfTestMode.MARKER, 5_000L, marker);
        return dir;
    }

    private Path slowPackage(TestEnv env, String name, String version, long timeoutMs) {
        Path dir = env.packageRoot.resolve(name);
        env.builder.build(dir, "DEMO-DEVICE", "1.0", version,
                ("slow-fw-" + version).getBytes(StandardCharsets.UTF_8),
                env.props.getChunkSizeBytes(), null, SelfTestMode.SLOW, timeoutMs, null);
        return dir;
    }

    @Test
    void selfTestFailureRollsBackWithDistinguishableReason() {
        TestEnv env = TestEnv.createDefault(tmp);
        // 负载不含 REQUIRED-OK 标记 -> MARKER 自检失败
        Path pkg = markerPackage(env, "p-bad", "2.0.0", "firmware without marker", "REQUIRED-OK");

        UpgradeRecord rec = env.service.startUpgrade("u-fail", pkg);

        assertEquals(UpgradePhase.ROLLED_BACK, rec.getPhase());
        assertEquals(RollbackReason.SELF_TEST_FAILED, rec.getRollbackReason());
        assertNotNull(rec.getFailureReason());
        assertEquals("1.0.0", env.activeVersion(), "自检失败后必须回退到 1.0.0");
        assertTrue(env.slots.verifySlot("a"));
        assertTrue(env.boot.lastBootDecision().startsWith("ROLLBACK(SELF_TEST_FAILED)"));
    }

    @Test
    void selfTestTimeoutRollsBackWithTimeoutReason() {
        TestEnv env = TestEnv.createDefault(tmp);
        // SLOW 模式睡眠超过 300ms 超时
        Path pkg = slowPackage(env, "p-slow", "2.0.0", 300L);

        long start = System.currentTimeMillis();
        UpgradeRecord rec = env.service.startUpgrade("u-timeout", pkg);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(UpgradePhase.ROLLED_BACK, rec.getPhase());
        assertEquals(RollbackReason.SELF_TEST_TIMEOUT, rec.getRollbackReason(),
                "超时与失败原因必须可区分");
        assertEquals("1.0.0", env.activeVersion());
        assertTrue(elapsed < 3_000, "超时机制必须在超时点附近返回，不能无限等待，实际 " + elapsed + "ms");
        assertTrue(env.boot.lastBootDecision().startsWith("ROLLBACK(SELF_TEST_TIMEOUT)"));
    }

    @Test
    void injectedSelfTestFailureRollsBack() {
        TestEnv env = TestEnv.createDefault(tmp);
        Path pkg = env.buildPackage("p2", "2.0.0", "good payload");
        env.faults.setForceSelfTestFailure(true);

        UpgradeRecord rec = env.service.startUpgrade("u-inj-fail", pkg);
        assertEquals(UpgradePhase.ROLLED_BACK, rec.getPhase());
        assertEquals(RollbackReason.SELF_TEST_FAILED, rec.getRollbackReason());
        assertEquals("1.0.0", env.activeVersion());
    }

    @Test
    void rollbackRecordSurvivesLaterUpgrades() {
        TestEnv env = TestEnv.createDefault(tmp);

        // 第一次升级自检失败并回滚
        Path bad = markerPackage(env, "p-bad", "2.0.0", "no marker here", "REQUIRED-OK");
        UpgradeRecord rolled = env.service.startUpgrade("u-bad", bad);
        assertEquals(UpgradePhase.ROLLED_BACK, rolled.getPhase());

        // 第二次升级成功（带上标记）
        Path good = markerPackage(env, "p-good", "2.1.0", "payload REQUIRED-OK present", "REQUIRED-OK");
        UpgradeRecord ok = env.service.startUpgrade("u-good", good);
        assertEquals(UpgradePhase.COMMITTED, ok.getPhase());
        assertEquals("2.1.0", env.activeVersion());

        // 历史中回滚记录仍然存在，且原因不丢失
        UpgradeRecord stillThere = env.service.getUpgrade("u-bad");
        assertNotNull(stillThere);
        assertEquals(UpgradePhase.ROLLED_BACK, stillThere.getPhase());
        assertEquals(RollbackReason.SELF_TEST_FAILED, stillThere.getRollbackReason());
        assertTrue(env.service.history().size() >= 2);
    }

    @Test
    void manualRollbackProducesQueryableRecord() {
        TestEnv env = TestEnv.createDefault(tmp);
        Path good = markerPackage(env, "p-good", "2.0.0", "payload REQUIRED-OK", "REQUIRED-OK");
        UpgradeRecord up = env.service.startUpgrade("u-up", good);
        assertEquals(UpgradePhase.COMMITTED, up.getPhase());
        assertEquals("2.0.0", env.activeVersion());

        UpgradeRecord rb = env.service.rollback("manual-1");
        assertEquals(UpgradePhase.ROLLED_BACK, rb.getPhase());
        assertEquals(RollbackReason.MANUAL, rb.getRollbackReason());
        assertEquals("1.0.0", env.activeVersion());
        // 手动回滚记录同样持久可查
        assertEquals(RollbackReason.MANUAL, env.service.getUpgrade("manual-1").getRollbackReason());
    }
}
