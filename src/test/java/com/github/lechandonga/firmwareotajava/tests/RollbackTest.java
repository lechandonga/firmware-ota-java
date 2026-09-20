package com.github.lechandonga.firmwareotajava.tests;

import com.github.lechandonga.firmwareotajava.model.FailureReason;
import com.github.lechandonga.firmwareotajava.model.UpgradeRecord;
import com.github.lechandonga.firmwareotajava.model.UpdatePhase;
import com.github.lechandonga.firmwareotajava.model.UpgradeState;
import com.github.lechandonga.firmwareotajava.tests.support.DeviceHarness;
import com.github.lechandonga.firmwareotajava.tests.support.TestPackageBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回滚：新版本首启自检失败/超时自动回滚到 1.0.0；
 * 回滚记录含可区分原因，且后续成功升级不覆盖回滚记录。
 */
class RollbackTest {

    @Test
    void selftestFailureRollsBackToLastGood(@TempDir Path tmp) throws Exception {
        DeviceHarness d = DeviceHarness.boot(tmp.resolve("s"), tmp.resolve("p"));
        new TestPackageBuilder(d.packages, "bad-self")
                .version("2.0.0").selftestFails(0).build();

        UpgradeRecord rec = d.service.upgradeFromPackage("bad-self");
        assertNotNull(rec);
        assertEquals("ROLLED_BACK", rec.outcome());
        assertEquals(FailureReason.SELFTEST_FAILED, rec.failureReason());
        assertEquals("1.0.0", rec.rolledBackTo().toString());
        assertNull(rec.rejectReason(), "拒绝原因与失败原因必须可区分");

        UpgradeState s = d.service.currentState();
        assertEquals("1.0.0", s.currentRunningVersion().toString());
        assertEquals("1.0.0", s.lastGoodVersion().toString());
        assertEquals("A", s.activeSlot(), "应回退到 A 槽旧版本");
        assertEquals(UpdatePhase.ROLLED_BACK, s.phase());
    }

    @Test
    void selftestTimeoutIsDistinctReasonAndRollsBack(@TempDir Path tmp) throws Exception {
        DeviceHarness d = DeviceHarness.boot(tmp.resolve("s"), tmp.resolve("p"));
        // 自检卡住 2s，超时阈值 300ms
        new TestPackageBuilder(d.packages, "hang-self")
                .version("2.0.0").selftestTimesOut(2_000).build();

        UpgradeRecord rec = d.service.upgradeFromPackage("hang-self");
        assertEquals("ROLLED_BACK", rec.outcome());
        assertEquals(FailureReason.SELFTEST_TIMEOUT, rec.failureReason(),
                "超时必须与失败区分");
        assertEquals("1.0.0", d.service.currentState()
                .currentRunningVersion().toString());
    }

    @Test
    void crashWhileTestingTriggersRollbackOnReboot(@TempDir Path tmp) throws Exception {
        // 超长自检超时，保证升级稳定停留在 TESTING 阶段供我们“强杀”
        var props = DeviceHarness.defaultProps(tmp.resolve("s"), tmp.resolve("p"));
        props.setSelftestTimeoutMillis(600_000);
        DeviceHarness d = DeviceHarness.boot(tmp.resolve("s"), tmp.resolve("p"), props);
        new TestPackageBuilder(d.packages, "hang-self")
                .version("2.0.0").selftestTimesOut(600_000).build();

        Thread t = new Thread(() -> d.service.upgradeFromPackage("hang-self"));
        t.setDaemon(true);
        t.start();
        // 等待进入 TESTING
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            UpgradeState s = d.service.currentState();
            if (s.phase() == UpdatePhase.TESTING) {
                break;
            }
            Thread.sleep(20);
        }
        assertEquals(UpdatePhase.TESTING, d.service.currentState().phase());

        // 模拟自检进行中 kill -9：中止自检、不下任何结论
        d.service.killDuringSelftest();
        t.join(5000);
        assertFalse(t.isAlive(), "被强杀的升级线程应退出并释放锁");
        assertEquals(UpdatePhase.TESTING, d.service.currentState().phase(),
                "强杀后状态停留在 TESTING");

        // 重新上电：必须确定回到 1.0.0 并产生可区分的回滚记录
        DeviceHarness rebooted = d.reboot();

        UpgradeState after = rebooted.service.currentState();
        assertEquals("1.0.0", after.currentRunningVersion().toString(),
                "掉电在自检阶段，重启必须确定回到 1.0.0");
        assertEquals(UpdatePhase.ROLLED_BACK, after.phase());

        List<UpgradeRecord> history = rebooted.service.history();
        UpgradeRecord rollback = history.get(history.size() - 1);
        assertEquals("ROLLED_BACK", rollback.outcome());
        // 重启时无法区分“失败/超时”的未完成自检，归类为超时（未能在时限内完成确认）
        assertEquals(FailureReason.SELFTEST_TIMEOUT, rollback.failureReason());
    }

    @Test
    void rollbackRecordSurvivesLaterSuccessfulUpgrade(@TempDir Path tmp) throws Exception {
        DeviceHarness d = DeviceHarness.boot(tmp.resolve("s"), tmp.resolve("p"));

        // 先一次失败升级并回滚
        new TestPackageBuilder(d.packages, "bad")
                .version("2.0.0").selftestFails(0).build();
        UpgradeRecord rb = d.service.upgradeFromPackage("bad");
        assertEquals("ROLLED_BACK", rb.outcome());

        // 再一次成功升级到 3.0.0
        new TestPackageBuilder(d.packages, "good")
                .version("3.0.0").selftestSuccess().build();
        UpgradeRecord ok = d.service.upgradeFromPackage("good");
        assertEquals("SUCCEEDED", ok.outcome());
        assertEquals("3.0.0",
                d.service.currentState().currentRunningVersion().toString());

        // 历史 append-only：回滚记录仍在，且可区分
        List<UpgradeRecord> all = d.service.history();
        assertTrue(all.stream().anyMatch(r ->
                "ROLLED_BACK".equals(r.outcome())
                        && r.failureReason() == FailureReason.SELFTEST_FAILED),
                "回滚记录不得因后续升级丢失");
        assertTrue(all.stream().anyMatch(r -> "SUCCEEDED".equals(r.outcome())));
    }

    @Test
    void rebootAfterRollbackStaysOnLastGood(@TempDir Path tmp) throws Exception {
        DeviceHarness d = DeviceHarness.boot(tmp.resolve("s"), tmp.resolve("p"));
        new TestPackageBuilder(d.packages, "bad")
                .version("2.0.0").selftestFails(0).build();
        d.service.upgradeFromPackage("bad");

        DeviceHarness rebooted = d.reboot();
        assertEquals("1.0.0", rebooted.service.currentState()
                .currentRunningVersion().toString());
    }
}
