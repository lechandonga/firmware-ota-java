package com.github.lechandonga.firmwareotajava.ota.staging;

import com.github.lechandonga.firmwareotajava.ota.fault.FaultInjection;
import com.github.lechandonga.firmwareotajava.ota.model.FirmwareManifest;
import com.github.lechandonga.firmwareotajava.ota.model.PackageException;
import com.github.lechandonga.firmwareotajava.ota.model.RejectReason;
import com.github.lechandonga.firmwareotajava.ota.model.StagingState;
import com.github.lechandonga.firmwareotajava.ota.packageinfo.FirmwarePackageReader;
import com.github.lechandonga.firmwareotajava.ota.storage.DeviceStorage;
import com.github.lechandonga.firmwareotajava.ota.storage.JsonFileStore;
import com.github.lechandonga.firmwareotajava.ota.util.Hashes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 断点续传：按分块接收负载，每块写入后立即校验 SHA-256，
 * 只有通过校验才在 staging-state 中标记 verified 并落盘；未通过的分块删除重传。
 * 中断后续传只处理 verified=false 的分块，已写入但未通过校验的数据绝不使用。
 */
public class StagingService {

    private static final Logger log = LoggerFactory.getLogger(StagingService.class);
    private static final int WRITE_BUFFER = 4096;

    private final DeviceStorage storage;
    private final JsonFileStore json;
    private final FaultInjection faults;

    public StagingService(DeviceStorage storage, JsonFileStore json, FaultInjection faults) {
        this.storage = storage;
        this.json = json;
        this.faults = faults;
    }

    /** 创建或恢复 staging 状态（同一 upgradeId 幂等）。恢复时重新核验所有已存分块。 */
    public StagingState initOrResume(String upgradeId, FirmwareManifest manifest) {
        Path stateFile = storage.stagingStateFile(upgradeId);
        StagingState state = json.readOrNull(stateFile, StagingState.class);
        if (state == null) {
            state = newState(upgradeId, manifest);
            try {
                Files.createDirectories(storage.chunkDir(upgradeId));
            } catch (IOException e) {
                throw new UncheckedIOException("创建 staging 目录失败", e);
            }
            json.write(stateFile, state);
            log.info("staging[{}] 初始化，共 {} 个分块", upgradeId, state.getChunksVerified().size());
            return state;
        }
        if (!state.getPayloadSha256().equalsIgnoreCase(manifest.payloadSha256())
                || !state.getTargetVersion().equals(manifest.version())) {
            throw new IllegalArgumentException(
                    "upgradeId " + upgradeId + " 已绑定另一个升级包，拒绝复用");
        }
        revalidateChunks(state);
        json.write(stateFile, state);
        long verified = state.getChunksVerified().stream().filter(Boolean::booleanValue).count();
        log.info("staging[{}] 断点恢复：{}/{} 个分块已通过校验",
                upgradeId, verified, state.getChunksVerified().size());
        return state;
    }

    private StagingState newState(String upgradeId, FirmwareManifest manifest) {
        StagingState state = new StagingState();
        state.setUpgradeId(upgradeId);
        state.setTargetVersion(manifest.version());
        state.setPayloadSha256(manifest.payloadSha256());
        state.setPayloadSize(manifest.payloadSize());
        state.setChunkSize(manifest.chunkSize());
        state.setChunkSha256(new ArrayList<>(manifest.chunkSha256()));
        state.setChunksVerified(new ArrayList<>(
                manifest.chunkSha256().stream().map(h -> Boolean.FALSE).toList()));
        state.setComplete(false);
        return state;
    }

    /** 对磁盘上已有的分块重新做 SHA-256 核验，不通过的清除标记。 */
    private void revalidateChunks(StagingState state) {
        for (int i = 0; i < state.getChunksVerified().size(); i++) {
            Path chunk = storage.chunkFile(state.getUpgradeId(), i);
            boolean ok = false;
            if (Files.isRegularFile(chunk)) {
                try {
                    ok = Hashes.sha256Hex(chunk).equalsIgnoreCase(state.getChunkSha256().get(i));
                } catch (IOException e) {
                    ok = false;
                }
            }
            if (!ok && Boolean.TRUE.equals(state.getChunksVerified().get(i))) {
                log.warn("staging[{}] 分块 {} 落盘数据未通过复核，标记为需重传",
                        state.getUpgradeId(), i);
            }
            if (!ok) {
                state.getChunksVerified().set(i, Boolean.FALSE);
                deleteQuietly(chunk);
            }
        }
    }

    /**
     * 从包负载读取并写入所有尚未通过校验的分块。
     * 返回本次新处理的字节数。中断抛 TransferInterruptedException，已完成分块状态已落盘。
     */
    public long transferMissingChunks(String upgradeId, Path packageDir, FirmwareManifest manifest) {
        StagingState state = state(upgradeId);
        long processed = 0L;
        long skipped = 0L;
        try (InputStream in = new FirmwarePackageReader(json).openPayload(packageDir)) {
            for (int i = 0; i < state.getChunksVerified().size(); i++) {
                int expectedLen = chunkLength(state, i);
                if (Boolean.TRUE.equals(state.getChunksVerified().get(i))) {
                    in.skipNBytes(expectedLen);
                    skipped += expectedLen;
                    continue;
                }
                processed += writeOneChunk(in, state, i, expectedLen);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取升级包负载失败", e);
        }
        log.info("staging[{}] 本轮新写入 {} 字节（跳过已校验 {} 字节）", upgradeId, processed, skipped);
        return processed;
    }

    private long writeOneChunk(InputStream in, StagingState state, int index, int expectedLen)
            throws IOException {
        byte[] buffer = new byte[expectedLen];
        readFully(in, buffer);

        String upgradeId = state.getUpgradeId();
        Path chunkFile = storage.chunkFile(upgradeId, index);
        Files.deleteIfExists(chunkFile);

        long written = 0L;
        try (OutputStream out = Files.newOutputStream(chunkFile)) {
            int off = 0;
            while (off < buffer.length) {
                int n = Math.min(WRITE_BUFFER, buffer.length - off);
                out.write(buffer, off, n);
                off += n;
                written += n;
                faults.checkWriteProgress(verifiedBytesBefore(state, index) + written);
            }
            out.flush();
        } catch (RuntimeException e) {
            // 中断/掉电：当前分块未完成，删除残片，状态保持 unverified
            deleteQuietly(chunkFile);
            throw e;
        }

        String actualHash = Hashes.sha256Hex(chunkFile);
        if (!actualHash.equalsIgnoreCase(state.getChunkSha256().get(index))) {
            Files.deleteIfExists(chunkFile);
            throw new PackageException(RejectReason.PAYLOAD_CHECKSUM_MISMATCH,
                    "分块 " + index + " 校验失败，已丢弃该分块（需重传）");
        }
        state.getChunksVerified().set(index, Boolean.TRUE);
        json.write(storage.stagingStateFile(upgradeId), state);
        log.info("staging[{}] 分块 {}/{} 校验通过", upgradeId, index + 1,
                state.getChunksVerified().size());
        return written;
    }

    private long verifiedBytesBefore(StagingState state, int currentIndex) {
        long total = 0L;
        for (int i = 0; i < currentIndex; i++) {
            if (Boolean.TRUE.equals(state.getChunksVerified().get(i))) {
                total += chunkLength(state, i);
            }
        }
        return total;
    }

    private int chunkLength(StagingState state, int index) {
        long start = (long) index * state.getChunkSize();
        return (int) Math.min(state.getChunkSize(), state.getPayloadSize() - start);
    }

    private static void readFully(InputStream in, byte[] buffer) throws IOException {
        int off = 0;
        while (off < buffer.length) {
            int read = in.read(buffer, off, buffer.length - off);
            if (read == -1) {
                throw new IOException("负载提前结束，期望 " + buffer.length + " 字节，实际 " + off);
            }
            off += read;
        }
    }

    /** 分块全部通过后做整包 SHA-256 终验（按分块顺序流式拼接）并标记 complete。 */
    public void finalizeStaging(String upgradeId, FirmwareManifest manifest) {
        StagingState state = state(upgradeId);
        for (int i = 0; i < state.getChunksVerified().size(); i++) {
            if (!Boolean.TRUE.equals(state.getChunksVerified().get(i))) {
                throw new IllegalStateException("分块 " + i + " 尚未通过校验，不能终验");
            }
        }
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        long total = 0L;
        try {
            for (int i = 0; i < state.getChunksVerified().size(); i++) {
                byte[] data = Files.readAllBytes(storage.chunkFile(upgradeId, i));
                md.update(data);
                total += data.length;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("终验读取分块失败", e);
        }
        String assembled = HexFormat.of().formatHex(md.digest());
        if (total != manifest.payloadSize()
                || !assembled.equalsIgnoreCase(manifest.payloadSha256())) {
            throw new PackageException(RejectReason.PAYLOAD_CHECKSUM_MISMATCH,
                    "staging 终验失败：拼接后负载大小/校验和与清单不一致");
        }
        state.setComplete(true);
        json.write(storage.stagingStateFile(upgradeId), state);
        log.info("staging[{}] 全部 {} 分块终验通过，负载完整", upgradeId,
                state.getChunksVerified().size());
    }

    public StagingState state(String upgradeId) {
        StagingState state = json.readOrNull(storage.stagingStateFile(upgradeId), StagingState.class);
        if (state == null) {
            throw new IllegalStateException("staging 状态不存在: " + upgradeId);
        }
        return state;
    }

    /** 释放某次升级占用的临时资源。 */
    public void cleanup(String upgradeId) {
        Path dir = storage.upgradeDir(upgradeId);
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            log.info("staging[{}] 临时资源已释放", upgradeId);
        } catch (IOException e) {
            throw new UncheckedIOException("清理 staging 失败: " + dir, e);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
