package com.github.lechandonga.firmwareotajava.web;

import com.github.lechandonga.firmwareotajava.model.UpgradeRecord;
import com.github.lechandonga.firmwareotajava.model.UpgradeState;
import com.github.lechandonga.firmwareotajava.storage.UpgradeHistory;
import com.github.lechandonga.firmwareotajava.upgrade.UpgradeService;
import com.github.lechandonga.firmwareotajava.web.dto.StateResponse;
import com.github.lechandonga.firmwareotajava.web.dto.UpgradeRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OTA REST 接口：
 * <ul>
 *   <li>POST /api/ota/upgrades        发起/断点续传升级（幂等）</li>
 *   <li>GET  /api/ota/state           当前状态（阶段/进度/最近拒绝/待回滚原因）</li>
 *   <li>GET  /api/ota/history         升级与回滚历史（append-only）</li>
 *   <li>POST /api/ota/rollback        手动回滚到最近一次可正常启动版本</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ota")
public class UpgradeController {

    private final UpgradeService upgradeService;
    private final UpgradeHistory history;

    public UpgradeController(UpgradeService upgradeService, UpgradeHistory history) {
        this.upgradeService = upgradeService;
        this.history = history;
    }

    @PostMapping("/upgrades")
    public ResponseEntity<Map<String, Object>> upgrade(@RequestBody UpgradeRequest request) {
        if (request == null || request.getPackageName() == null
                || request.getPackageName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "result", "REJECTED", "rejectReason", "BAD_REQUEST"));
        }
        UpgradeRecord record = upgradeService.upgradeFromPackage(request.getPackageName());
        Map<String, Object> body = new LinkedHashMap<>();
        if (record == null) {
            // 进行中（重复请求幂等）、传输中断、或被版本策略拒绝
            UpgradeState state = upgradeService.currentState();
            body.put("result", state.phase().name());
            body.put("state", toResponse(state));
            if (state.lastRejectReason() != null) {
                body.put("rejectReason", state.lastRejectReason().name());
            }
            return ResponseEntity.ok(body);
        }
        body.put("result", record.outcome());
        body.put("record", record);
        HttpStatus status = "REJECTED".equals(record.outcome())
                ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.OK;
        return ResponseEntity.status(status).body(body);
    }

    @GetMapping("/state")
    public StateResponse state() {
        return toResponse(upgradeService.currentState());
    }

    @GetMapping("/history")
    public List<UpgradeRecord> history() {
        return history.all();
    }

    @PostMapping("/rollback")
    public Map<String, Object> rollback() {
        UpgradeRecord record = upgradeService.rollback();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", "ROLLED_BACK");
        body.put("record", record);
        return body;
    }

    static StateResponse toResponse(UpgradeState s) {
        StateResponse r = new StateResponse();
        r.phase = s.phase().name();
        r.runningVersion = str(s.currentRunningVersion());
        r.lastGoodVersion = str(s.lastGoodVersion());
        r.candidateVersion = str(s.candidateVersion());
        r.activeSlot = s.activeSlot();
        r.candidateSlot = s.candidateSlot();
        r.verifiedChunks = s.verifiedChunks();
        r.totalChunks = s.totalChunks();
        r.progressPercent = s.progressPercent();
        r.activeUpgradeId = s.activeUpgradeId();
        r.lastRejectedUpgradeId = s.lastRejectedUpgradeId();
        r.lastRejectReason = s.lastRejectReason() == null
                ? null : s.lastRejectReason().name();
        r.pendingRollbackReason = s.pendingRollbackReason() == null
                ? null : s.pendingRollbackReason().name();
        r.repairAttempts = s.repairAttempts();
        r.repairedChunks = s.repairedChunks();
        return r;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
