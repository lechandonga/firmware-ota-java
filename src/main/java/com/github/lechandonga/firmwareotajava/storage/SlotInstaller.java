package com.github.lechandonga.firmwareotajava.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.firmwareotajava.model.FirmwareManifest;
import com.github.lechandonga.firmwareotajava.model.Version;
import com.github.lechandonga.firmwareotajava.pkg.HashUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 槽位安装器。
 *
 * <p>安装目标永远是“非活动槽”，活动槽（当前可运行版本）在整个安装阶段
 * 不会被打开写入。安装步骤全部是“先写临时文件、校验、再原子 rename”：
 * <ol>
 *   <li>建立 {@code INSTALLING} 标记；</li>
 *   <li>拷贝 payload 到 {@code payload.tmp}，哈希通过后 rename 为 payload.bin；</li>
 *   <li>写 manifest.json（原子）；</li>
 *   <li>最后写 VERSION 标记文件（原子）——它的存在代表该槽“完整可用”，
 *       并删除 INSTALLING 标记。</li>
 * </ol>
 * 掉电若发生在 VERSION 落盘之前，该槽被视为未安装，绝不可被切换指向。
 */
@Component
public class SlotInstaller {

    public static final String INSTALLING_MARKER = "INSTALLING";

    private static final Logger log = LoggerFactory.getLogger(SlotInstaller.class);

    private final SlotLayout layout;
    private final ObjectMapper objectMapper;

    /**
     * 测试钩子：true 时在候选槽 payload 已写入、但 VERSION 提交标记落盘前
     * 抛出异常，模拟安装阶段掉电（此时该槽不完整，绝不可被切换指向）。
     */
    private volatile boolean crashBeforeCommit = false;

    public void setCrashBeforeCommit(boolean crashBeforeCommit) {
        this.crashBeforeCommit = crashBeforeCommit;
    }

    public SlotInstaller(SlotLayout layout, ObjectMapper objectMapper) {
        this.layout = layout;
        this.objectMapper = objectMapper;
    }

    public void install(String slot, FirmwareManifest manifest, Path stagedPayload) {
        Path slotDir = layout.slotDir(slot);
        try {
            Files.createDirectories(slotDir);
            Path installing = slotDir.resolve(INSTALLING_MARKER);
            Files.writeString(installing, manifest.upgradeId());

            // 1) payload：复制 + 哈希校验 + 原子替换
            Path tmpPayload = slotDir.resolve("payload.tmp");
            Files.copy(stagedPayload, tmpPayload, StandardCopyOption.REPLACE_EXISTING);
            String hash = HashUtil.sha256Hex(tmpPayload);
            if (!hash.equalsIgnoreCase(manifest.payloadSha256())) {
                Files.deleteIfExists(tmpPayload);
                throw new IllegalStateException("写入槽位后哈希校验失败 slot=" + slot);
            }
            atomicMove(tmpPayload, layout.slotPayload(slot));

            // 2) manifest 原子写
            Path tmpManifest = slotDir.resolve("manifest.json.tmp");
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(tmpManifest.toFile(), manifest);
            atomicMove(tmpManifest, layout.slotManifest(slot));

            if (crashBeforeCommit) {
                // 模拟安装阶段掉电：payload/manifest 已写，但 VERSION 提交标记未落盘
                throw new IllegalStateException("模拟安装阶段掉电（VERSION 提交前）");
            }

            // 3) VERSION 最后落盘：槽位“完整可用”的提交点
            Path tmpVersion = slotDir.resolve("VERSION.tmp");
            Files.writeString(tmpVersion, manifest.version().toString());
            atomicMove(tmpVersion, layout.slotVersionFile(slot));

            Files.deleteIfExists(installing);
            log.info("槽位 {} 安装完成 version={}（未触碰活动槽）", slot, manifest.version());
        } catch (IOException e) {
            throw new IllegalStateException("安装槽位失败 slot=" + slot, e);
        }
    }

    /** 槽位是否完整（VERSION 标记存在且 payload 哈希与槽内 manifest 一致）。 */
    public boolean isSlotComplete(String slot) {
        if (!Files.exists(layout.slotVersionFile(slot))
                || Files.exists(layout.slotDir(slot).resolve(INSTALLING_MARKER))) {
            return false;
        }
        try {
            FirmwareManifest m = readManifest(slot);
            return m != null && verifySlot(slot, m);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public Version installedVersion(String slot) {
        Path v = layout.slotVersionFile(slot);
        try {
            return Files.exists(v) ? Version.parse(Files.readString(v).trim()) : null;
        } catch (IOException e) {
            return null;
        }
    }

    public FirmwareManifest readManifest(String slot) {
        Path m = layout.slotManifest(slot);
        if (!Files.exists(m)) {
            return null;
        }
        try {
            return objectMapper.readValue(m.toFile(), FirmwareManifest.class);
        } catch (IOException e) {
            log.warn("槽位 {} 清单无法读取", slot);
            return null;
        }
    }

    public boolean verifySlot(String slot, FirmwareManifest manifest) {
        Path payload = layout.slotPayload(slot);
        if (!Files.exists(payload)) {
            return false;
        }
        try {
            if (Files.size(payload) != manifest.payloadSize()) {
                return false;
            }
            return HashUtil.sha256Hex(payload).equalsIgnoreCase(manifest.payloadSha256());
        } catch (IOException e) {
            return false;
        }
    }

    public void eraseSlot(String slot) {
        Path dir = layout.slotDir(slot);
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("擦除槽位文件失败: {}", p);
                        }
                    });
            Files.createDirectories(dir);
            log.info("槽位 {} 已擦除", slot);
        } catch (IOException e) {
            log.warn("擦除槽位失败 slot={}", slot, e);
        }
    }

    private static void atomicMove(Path src, Path target) throws IOException {
        try {
            Files.move(src, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(src, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
