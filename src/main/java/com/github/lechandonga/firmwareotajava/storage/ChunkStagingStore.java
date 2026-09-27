package com.github.lechandonga.firmwareotajava.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.firmwareotajava.model.FirmwareManifest;
import com.github.lechandonga.firmwareotajava.pkg.HashUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;

/**
 * 分块暂存区（断点续传的核心）。
 *
 * <p>每个块的生命周期：写入 {@code NNNNNN.wr} 临时文件 -> 计算 SHA-256 与清单
 * 比对 -> 通过则原子 rename 为 {@code NNNNNN.part}，并把该块写入
 * {@code verified.json} 位图（原子写）。只有位图中存在的块才算“已通过校验”。
 *
 * <p>因此：
 * <ul>
 *   <li>写入中断：.wr 残留不被承认，重启后重传该块；</li>
 *   <li>校验失败：块不会进入位图，不被当作有效数据；</li>
 *   <li>重新升级：从位图得知已校验块，只处理缺失块。</li>
 * </ul>
 */
@Component
public class ChunkStagingStore {

    private static final Logger log = LoggerFactory.getLogger(ChunkStagingStore.class);

    private final SlotLayout layout;
    private final ObjectMapper objectMapper;

    public ChunkStagingStore(SlotLayout layout, ObjectMapper objectMapper) {
        this.layout = layout;
        this.objectMapper = objectMapper;
    }

    private Path chunkFile(String upgradeId, int index) {
        return layout.stagingChunksDir(upgradeId)
                .resolve(String.format("%06d.part", index));
    }

    private Path wrFile(String upgradeId, int index) {
        return layout.stagingChunksDir(upgradeId)
                .resolve(String.format("%06d.wr", index));
    }

    /** 该块是否已通过校验（以位图为准，而非文件是否存在）。 */
    public synchronized boolean isChunkVerified(String upgradeId, int index) {
        return loadBitmap(upgradeId).containsKey(index);
    }

    public synchronized int verifiedChunkCount(String upgradeId) {
        return loadBitmap(upgradeId).size();
    }

    /**
     * 暂存一个块。
     *
     * @return true 表示哈希校验通过且已标记；false 表示校验未通过（临时数据已删除）
     */
    public synchronized boolean stageChunk(String upgradeId, int chunkIndex,
                                           byte[] data, String expectedSha256) {
        try {
            Files.createDirectories(layout.stagingChunksDir(upgradeId));
            Path wr = wrFile(upgradeId, chunkIndex);
            Path part = chunkFile(upgradeId, chunkIndex);
            Files.write(wr, data);
            String actual = HashUtil.sha256Hex(wr);
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                // 未通过校验：删除脏数据，绝不标记位图
                Files.deleteIfExists(wr);
                log.warn("块 {} 哈希校验失败，丢弃该块 upgradeId={}", chunkIndex, upgradeId);
                return false;
            }
            try {
                Files.move(wr, part, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.move(wr, part, StandardCopyOption.REPLACE_EXISTING);
            }
            Map<Integer, String> bitmap = loadBitmap(upgradeId);
            bitmap.put(chunkIndex, actual);
            saveBitmap(upgradeId, bitmap);
            log.debug("块 {} 校验通过并暂存 upgradeId={}", chunkIndex, upgradeId);
            return true;
        } catch (IOException e) {
            throw new IllegalStateException("暂存块失败 index=" + chunkIndex, e);
        }
    }

    /**
     * 续传前复核：逐块重新计算位图中每个已校验块的实际哈希。
     *
     * <p>设备存储不可靠时（闪存老化、坏块、半截写入、翻位），位图声称已校验的
     * 块内容可能已损坏。本方法把实际内容与位图记录不符（或文件缺失/不可读）的
     * 块从位图中剔除并删除其数据文件，使续传只重新处理这些真正有问题的块，
     * 未受影响的块保持有效、不重复传输。
     *
     * @return 被剔除（判定不可用）的块下标列表，按升序
     */
    public synchronized java.util.List<Integer> evictCorruptedChunks(String upgradeId) {
        Map<Integer, String> bitmap = loadBitmap(upgradeId);
        if (bitmap.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<Integer> evicted = new java.util.ArrayList<>();
        for (Map.Entry<Integer, String> entry : bitmap.entrySet()) {
            Path part = chunkFile(upgradeId, entry.getKey());
            boolean usable = false;
            try {
                usable = Files.isRegularFile(part)
                        && HashUtil.sha256Hex(part).equalsIgnoreCase(entry.getValue());
            } catch (IOException e) {
                usable = false;
            }
            if (!usable) {
                evicted.add(entry.getKey());
            }
        }
        if (evicted.isEmpty()) {
            return evicted;
        }
        for (Integer idx : evicted) {
            bitmap.remove(idx);
            try {
                Files.deleteIfExists(chunkFile(upgradeId, idx));
            } catch (IOException e) {
                log.warn("删除损坏暂存块失败 upgradeId={} index={}", upgradeId, idx, e);
            }
        }
        saveBitmap(upgradeId, bitmap);
        log.warn("暂存数据复核：{} 个已校验块实际不可用，已剔除并将重新处理 upgradeId={} chunks={}",
                evicted.size(), upgradeId, evicted);
        return evicted;
    }

    public synchronized long verifiedBytes(String upgradeId, FirmwareManifest manifest) {
        Map<Integer, String> bitmap = loadBitmap(upgradeId);
        long bytes = 0;
        for (Integer idx : bitmap.keySet()) {
            if (idx == manifest.chunkSha256().size() - 1) {
                // 最后一块按实际长度计算
                long rem = manifest.payloadSize() % manifest.chunkSize();
                bytes += rem == 0 ? manifest.chunkSize() : rem;
            } else {
                bytes += manifest.chunkSize();
            }
        }
        return Math.min(bytes, manifest.payloadSize());
    }

    public Path chunkFilePath(String upgradeId, int chunkIndex) {
        return chunkFile(upgradeId, chunkIndex);
    }

    /**
     * 按序组装所有已校验块到 assembled 文件（临时文件 + 原子 rename），
     * 并校验总大小与整体哈希，防止残留/拼接错误进入槽位。
     */
    public synchronized Path assemble(String upgradeId, FirmwareManifest manifest) {
        int total = manifest.chunkSha256().size();
        Map<Integer, String> bitmap = loadBitmap(upgradeId);
        if (bitmap.size() != total) {
            throw new IllegalStateException("已校验块不完整，无法组装: "
                    + bitmap.size() + "/" + total);
        }
        Path out = layout.stagingAssembled(upgradeId);
        Path tmp = layout.stagingDir(upgradeId).resolve("payload.assembled.tmp");
        try {
            long written = 0;
            try (OutputStream os = Files.newOutputStream(tmp)) {
                for (int i = 0; i < total; i++) {
                    if (!bitmap.containsKey(i)) {
                        throw new IllegalStateException("缺少已校验块 " + i);
                    }
                    byte[] data = Files.readAllBytes(chunkFile(upgradeId, i));
                    if (!HashUtil.sha256Hex(data).equalsIgnoreCase(bitmap.get(i))) {
                        throw new IllegalStateException("块 " + i + " 复核哈希失败");
                    }
                    os.write(data);
                    written += data.length;
                }
            }
            if (written != manifest.payloadSize()) {
                Files.deleteIfExists(tmp);
                throw new IllegalStateException("组装大小不符: " + written
                        + " != " + manifest.payloadSize());
            }
            String overall = HashUtil.sha256Hex(tmp);
            if (!overall.equalsIgnoreCase(manifest.payloadSha256())) {
                Files.deleteIfExists(tmp);
                throw new IllegalStateException("组装后整体哈希校验失败（数据被篡改）");
            }
            try {
                Files.move(tmp, out, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("分块组装完成并通过整体哈希校验 upgradeId={}", upgradeId);
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("组装载荷失败", e);
        }
    }

    public synchronized void cleanup(String upgradeId) {
        Path dir = layout.stagingDir(upgradeId);
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("清理临时资源失败: {}", p);
                        }
                    });
            log.info("已释放升级临时资源 upgradeId={}", upgradeId);
        } catch (IOException e) {
            log.warn("清理暂存目录失败: {}", dir, e);
        }
    }

    private Map<Integer, String> loadBitmap(String upgradeId) {
        Path file = layout.stagingVerifiedFile(upgradeId);
        if (!Files.exists(file)) {
            return new TreeMap<>();
        }
        try {
            Map<Integer, String> map = objectMapper.readValue(file.toFile(),
                    new TypeReference<TreeMap<Integer, String>>() {
                    });
            return map == null ? new TreeMap<>() : map;
        } catch (IOException e) {
            // 位图自身原子写，不应损坏；损坏时保守地视为无已校验块
            log.warn("校验位图读取失败，按无已校验块处理: {}", file, e);
            return new TreeMap<>();
        }
    }

    private void saveBitmap(String upgradeId, Map<Integer, String> bitmap) {
        Path file = layout.stagingVerifiedFile(upgradeId);
        Path tmp = layout.stagingDir(upgradeId).resolve("verified.json.tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), bitmap);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("保存校验位图失败", e);
        }
    }
}
