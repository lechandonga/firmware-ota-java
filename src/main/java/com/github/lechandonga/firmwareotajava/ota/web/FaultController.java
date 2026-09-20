package com.github.lechandonga.firmwareotajava.ota.web;

import com.github.lechandonga.firmwareotajava.ota.boot.BootCoordinator;
import com.github.lechandonga.firmwareotajava.ota.fault.FaultInjection;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地验证用故障注入端点（易失开关，重启进程即失效）。
 */
@RestController
@RequestMapping("/api/ota/faults")
public class FaultController {

    private final FaultInjection faults;
    private final BootCoordinator bootCoordinator;

    public FaultController(FaultInjection faults, BootCoordinator bootCoordinator) {
        this.faults = faults;
        this.bootCoordinator = bootCoordinator;
    }

    /**
     * 注入/清除故障：
     * <pre>
     * {"failAfterBytes": 50}                    staging 写入到 50 字节后中断
     * {"failInstallAfterBytes": 100}            写槽位到 100 字节后掉电
     * {"forceSelfTestFailure": true}            下一次自检判定失败
     * {"forceSelfTestTimeout": true}            下一次自检超时
     * {"reset": true}                           清除全部注入
     * </pre>
     */
    @PostMapping
    public Map<String, Object> inject(@RequestBody Map<String, Object> body) {
        if (Boolean.TRUE.equals(body.get("reset"))) {
            faults.reset();
        }
        if (body.get("failAfterBytes") instanceof Number n) {
            faults.setFailAfterBytes(n.longValue());
        }
        if (body.get("failInstallAfterBytes") instanceof Number n) {
            faults.setFailInstallAfterBytes(n.longValue());
        }
        if (body.get("forceSelfTestFailure") instanceof Boolean b) {
            faults.setForceSelfTestFailure(b);
        }
        if (body.get("forceSelfTestTimeout") instanceof Boolean b) {
            faults.setForceSelfTestTimeout(b);
        }
        return snapshot();
    }

    @GetMapping
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("failAfterBytes", faults.getFailAfterBytes());
        m.put("failInstallAfterBytes", faults.getFailInstallAfterBytes());
        m.put("forceSelfTestFailure", faults.isForceSelfTestFailure());
        m.put("forceSelfTestTimeout", faults.isForceSelfTestTimeout());
        m.put("lastBootDecision", bootCoordinator.lastBootDecision());
        return m;
    }

    /** 手动模拟“重新上电”，重新执行一次启动决策（自检/回滚）。 */
    @PostMapping("/reboot")
    public Map<String, Object> reboot() {
        bootCoordinator.simulateReboot();
        return snapshot();
    }
}
