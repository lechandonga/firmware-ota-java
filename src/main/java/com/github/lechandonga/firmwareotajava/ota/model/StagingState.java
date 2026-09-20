package com.github.lechandonga.firmwareotajava.ota.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 断点续传状态（staging/<upgradeId>/staging-state.json）。
 * chunksVerified[i] 表示第 i 个分块是否已通过校验；续传时只重新处理未通过的分块。
 */
public class StagingState {

    private String upgradeId;
    private String targetVersion;
    private String payloadSha256;
    private long payloadSize;
    private int chunkSize;
    private List<String> chunkSha256 = new ArrayList<>();
    private List<Boolean> chunksVerified = new ArrayList<>();
    private boolean complete;

    public StagingState() {
    }

    public String getUpgradeId() {
        return upgradeId;
    }

    public void setUpgradeId(String upgradeId) {
        this.upgradeId = upgradeId;
    }

    public String getTargetVersion() {
        return targetVersion;
    }

    public void setTargetVersion(String targetVersion) {
        this.targetVersion = targetVersion;
    }

    public String getPayloadSha256() {
        return payloadSha256;
    }

    public void setPayloadSha256(String payloadSha256) {
        this.payloadSha256 = payloadSha256;
    }

    public long getPayloadSize() {
        return payloadSize;
    }

    public void setPayloadSize(long payloadSize) {
        this.payloadSize = payloadSize;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public List<String> getChunkSha256() {
        return chunkSha256;
    }

    public void setChunkSha256(List<String> chunkSha256) {
        this.chunkSha256 = chunkSha256;
    }

    public List<Boolean> getChunksVerified() {
        return chunksVerified;
    }

    public void setChunksVerified(List<Boolean> chunksVerified) {
        this.chunksVerified = chunksVerified;
    }

    public boolean isComplete() {
        return complete;
    }

    public void setComplete(boolean complete) {
        this.complete = complete;
    }
}
