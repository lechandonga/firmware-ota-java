package com.github.lechandonga.firmwareotajava.web.dto;

/** 状态查询响应。 */
public class StateResponse {
    public String phase;
    public String runningVersion;
    public String lastGoodVersion;
    public String candidateVersion;
    public String activeSlot;
    public String candidateSlot;
    public long verifiedChunks;
    public long totalChunks;
    public int progressPercent;
    public String activeUpgradeId;
    public String lastRejectedUpgradeId;
    public String lastRejectReason;
    public String pendingRollbackReason;
}
