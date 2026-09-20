package com.github.lechandonga.firmwareotajava.upgrade;

import com.github.lechandonga.firmwareotajava.model.FailureReason;
import com.github.lechandonga.firmwareotajava.model.FirmwareManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 新版本首启自检执行器（本地模拟）。
 *
 * <p>真实设备上自检是“在新版本环境启动并验证关键功能”。本地模拟时由清单声明：
 * <ul>
 *   <li>{@code selftestResult=SUCCESS}：在 selftestDurationMillis 后通过；</li>
 *   <li>{@code selftestResult=FAIL}：自检失败，返回 SELFTEST_FAILED；</li>
 *   <li>{@code selftestResult=TIMEOUT}：自检时间超过配置超时，
 *       {@link Future#get(long, TimeUnit)} 触发并归类为 SELFTEST_TIMEOUT。</li>
 * </ul>
 * 失败与超时是两个相互可区分的回滚原因。
 *
 * <p>{@link #kill()} 供测试模拟“自检进行中进程被强杀”：立即终止在跑的自检，
 * 且不产生任何确认/回滚结论，由下一次启动恢复负责裁决。
 */
@Component
public class SelftestExecutor {

    private static final Logger log = LoggerFactory.getLogger(SelftestExecutor.class);

    private volatile Future<?> running;
    private volatile ExecutorService pool;
    private final AtomicBoolean killed = new AtomicBoolean(false);

    /** 模拟强杀：中止当前自检线程并释放执行器，调用方不应再使用本次结果。 */
    public void kill() {
        killed.set(true);
        Future<?> f = running;
        ExecutorService p = pool;
        if (f != null) {
            f.cancel(true);
        }
        if (p != null) {
            p.shutdownNow();
        }
        log.warn("自检执行器被强制中止（模拟进程强杀）");
    }

    public FailureReason run(FirmwareManifest manifest, long timeoutMillis) {
        killed.set(false);
        String declared = manifest.selftestResult() == null
                ? "SUCCESS" : manifest.selftestResult().trim().toUpperCase();
        long duration = Math.max(0, manifest.selftestDurationMillis());

        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "selftest-" + manifest.upgradeId());
            t.setDaemon(true);
            return t;
        });
        this.pool = executor;
        try {
            Future<?> future = executor.submit(() -> {
                switch (declared) {
                    case "FAIL" -> {
                        sleep(duration);
                        throw new IllegalStateException("新版本自检发现致命故障（模拟）");
                    }
                    case "TIMEOUT" -> sleep(duration > timeoutMillis
                            ? duration : timeoutMillis + 500);
                    default -> sleep(duration);
                }
            });
            this.running = future;
            future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            log.info("首启自检通过 version={} upgradeId={}", manifest.version(),
                    manifest.upgradeId());
            return null;
        } catch (TimeoutException e) {
            log.warn("首启自检超时（>{}ms），归类 SELFTEST_TIMEOUT version={}",
                    timeoutMillis, manifest.version());
            return FailureReason.SELFTEST_TIMEOUT;
        } catch (Exception e) {
            if (killed.get()) {
                // 强杀路径：抛出让上层中断（不写结论），状态停留在 TESTING
                throw new SelftestKilledException();
            }
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("首启自检失败 version={} cause={}", manifest.version(),
                    cause.getMessage());
            return FailureReason.SELFTEST_FAILED;
        } finally {
            executor.shutdownNow();
            this.running = null;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SelftestKilledException();
        }
    }

    /** 自检被强杀的内部信号（仅测试构造掉电用）。 */
    static class SelftestKilledException extends RuntimeException {
    }
}
