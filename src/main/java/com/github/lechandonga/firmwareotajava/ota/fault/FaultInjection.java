package com.github.lechandonga.firmwareotajava.ota.fault;

import com.github.lechandonga.firmwareotajava.ota.model.TransferInterruptedException;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 易失性故障注入开关（仅用于测试中断/超时场景，重启即失效，不影响持久化状态机）。
 */
@Component
public class FaultInjection {

    /** 写入达到该字节数后中断（&lt;=0 表示关闭）。 */
    private final AtomicLong failAfterBytes = new AtomicLong(0);
    /** 自检时注入超时/失败。 */
    private volatile boolean forceSelfTestTimeout = false;
    private volatile boolean forceSelfTestFailure = false;
    /** 安装（写槽位）达到该字节数后模拟掉电（&lt;=0 表示关闭）。 */
    private final AtomicLong failInstallAfterBytes = new AtomicLong(0);

    public void reset() {
        failAfterBytes.set(0);
        failInstallAfterBytes.set(0);
        forceSelfTestTimeout = false;
        forceSelfTestFailure = false;
    }

    public long getFailAfterBytes() {
        return failAfterBytes.get();
    }

    public void setFailAfterBytes(long bytes) {
        failAfterBytes.set(bytes);
    }

    public boolean isForceSelfTestTimeout() {
        return forceSelfTestTimeout;
    }

    public void setForceSelfTestTimeout(boolean v) {
        this.forceSelfTestTimeout = v;
    }

    public boolean isForceSelfTestFailure() {
        return forceSelfTestFailure;
    }

    public void setForceSelfTestFailure(boolean v) {
        this.forceSelfTestFailure = v;
    }

    public long getFailInstallAfterBytes() {
        return failInstallAfterBytes.get();
    }

    public void setFailInstallAfterBytes(long bytes) {
        this.failInstallAfterBytes.set(bytes);
    }

    /** staging 写入循环中调用，累计写入达到阈值则抛出模拟中断。 */
    public void checkWriteProgress(long bytes) {
        long threshold = failAfterBytes.get();
        if (threshold > 0 && bytes >= threshold) {
            throw new TransferInterruptedException(bytes,
                    "故障注入：staging 写入在 " + bytes + " 字节后中断");
        }
    }

    /** 槽位安装写入循环中调用，达到阈值则抛出模拟掉电。 */
    public void checkInstallProgress(long bytes) {
        long threshold = failInstallAfterBytes.get();
        if (threshold > 0 && bytes >= threshold) {
            throw new TransferInterruptedException(bytes,
                    "故障注入：槽位安装在 " + bytes + " 字节后掉电");
        }
    }
}
