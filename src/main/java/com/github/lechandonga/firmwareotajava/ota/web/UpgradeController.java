package com.github.lechandonga.firmwareotajava.ota.web;

import com.github.lechandonga.firmwareotajava.ota.model.DeviceStatus;
import com.github.lechandonga.firmwareotajava.ota.model.UpgradeRecord;
import com.github.lechandonga.firmwareotajava.ota.upgrade.UpgradeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 设备侧 OTA REST 接口。
 */
@RestController
@RequestMapping("/api/ota")
public class UpgradeController {

    private final UpgradeService upgradeService;

    public UpgradeController(UpgradeService upgradeService) {
        this.upgradeService = upgradeService;
    }

    /** 发起/恢复升级：{"upgradeId":"...","packageDir":"/path/to/pkg"} */
    @PostMapping("/upgrades")
    public UpgradeRecord start(@RequestBody Map<String, String> request) {
        String upgradeId = require(request, "upgradeId");
        String packageDir = require(request, "packageDir");
        return upgradeService.startUpgrade(upgradeId, Path.of(packageDir));
    }

    @GetMapping("/upgrades/{upgradeId}")
    public UpgradeRecord get(@PathVariable String upgradeId) {
        return upgradeService.getUpgrade(upgradeId);
    }

    @GetMapping("/status")
    public DeviceStatus status() {
        return upgradeService.status();
    }

    @GetMapping("/history")
    public List<UpgradeRecord> history() {
        return upgradeService.history();
    }

    /** 手动回滚：{"upgradeId":"..."}（可为空，服务端生成） */
    @PostMapping("/rollback")
    public UpgradeRecord rollback(@RequestBody(required = false) Map<String, String> request) {
        String upgradeId = request == null ? null : request.get("upgradeId");
        return upgradeService.rollback(upgradeId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    private static String require(Map<String, String> request, String key) {
        String value = request == null ? null : request.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少参数: " + key);
        }
        return value;
    }
}
