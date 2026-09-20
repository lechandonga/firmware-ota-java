package com.github.lechandonga.firmwareotajava.ota.boot;

import com.github.lechandonga.firmwareotajava.ota.fault.FaultInjection;
import com.github.lechandonga.firmwareotajava.ota.model.ImageInfo;
import com.github.lechandonga.firmwareotajava.ota.model.SelfTestMode;
import com.github.lechandonga.firmwareotajava.ota.model.SelfTestSpec;
import com.github.lechandonga.firmwareotajava.ota.model.SelfTestVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 新版本启动自检（模拟）：
 * ALWAYS_OK 直接通过；MARKER 检查负载是否含规定标记；SLOW 模拟自检卡死超时。
 * 故障注入可强制自检失败或超时。超时在独立工作线程 + join 上实现，绝不无限阻塞启动流程。
 */
public class SelfTestRunner {

    private static final Logger log = LoggerFactory.getLogger(SelfTestRunner.class);

    private final FaultInjection faults;

    public SelfTestRunner(FaultInjection faults) {
        this.faults = faults;
    }

    public SelfTestVerdict run(Path payloadFile, ImageInfo image, SelfTestSpec spec) {
        long timeoutMs = spec.timeoutMs() > 0 ? spec.timeoutMs() : 30_000L;
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "firmware-self-test");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<SelfTestVerdict> future =
                    executor.submit(() -> executeChecks(payloadFile, image, spec));
            try {
                SelfTestVerdict verdict = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                log.info("版本 {} 启动自检结论：{}", image == null ? "?" : image.getVersion(), verdict);
                return verdict;
            } catch (TimeoutException e) {
                future.cancel(true);
                log.error("版本 {} 启动自检超时（{}ms）", image == null ? "?" : image.getVersion(), timeoutMs);
                return SelfTestVerdict.TIMEOUT;
            } catch (Exception e) {
                log.error("版本 {} 启动自检异常: {}",
                        image == null ? "?" : image.getVersion(), e.getMessage());
                return SelfTestVerdict.FAILED;
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private SelfTestVerdict executeChecks(Path payloadFile, ImageInfo image, SelfTestSpec spec)
            throws Exception {
        if (faults.isForceSelfTestTimeout()) {
            // 模拟自检卡死，直到被超时机制取消
            Thread.sleep(Long.MAX_VALUE);
        }
        if (faults.isForceSelfTestFailure()) {
            throw new IllegalStateException("故障注入：自检判定失败");
        }
        SelfTestMode mode = spec == null ? SelfTestMode.ALWAYS_OK : spec.mode();
        return switch (mode) {
            case ALWAYS_OK -> SelfTestVerdict.PASS;
            case MARKER -> markerCheck(payloadFile, spec);
            case SLOW -> {
                // 清单声明的慢自检：按 timeout 的 3 倍睡眠，触发超时
                Thread.sleep(spec.timeoutMs() * 3);
                yield SelfTestVerdict.PASS;
            }
        };
    }

    private SelfTestVerdict markerCheck(Path payloadFile, SelfTestSpec spec) throws Exception {
        byte[] payload = Files.readAllBytes(payloadFile);
        String content = new String(payload, StandardCharsets.UTF_8);
        if (content.contains(spec.requiredMarker())) {
            return SelfTestVerdict.PASS;
        }
        log.error("MARKER 自检失败：负载中未包含要求的标记 [{}]", spec.requiredMarker());
        return SelfTestVerdict.FAILED;
    }
}
