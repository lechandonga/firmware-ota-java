package com.github.lechandonga.firmwareotajava.storage;

import com.github.lechandonga.firmwareotajava.config.OtaProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 设备存储目录布局（本地目录模拟设备 flash 分区）：
 * <pre>
 * {storageDir}/
 *   state.json                 设备持久状态（原子写）
 *   history.json               append-only 升级历史
 *   slots/A/{payload.bin, manifest.json, VERSION}   A 槽
 *   slots/B/...                                    B 槽
 *   staging/{upgradeId}/chunks/NNNNNN.part         已校验分块
 *   staging/{upgradeId}/verified.json             已校验块位图
 *   staging/{upgradeId}/payload.assembled         组装完成的整包
 * </pre>
 */
@Component
public class SlotLayout {

    public static final String SLOT_A = "A";
    public static final String SLOT_B = "B";

    private final OtaProperties properties;
    private Path root;

    public SlotLayout(OtaProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() throws IOException {
        root = Path.of(properties.getStorageDir()).toAbsolutePath().normalize();
        Files.createDirectories(slotsDir());
        Files.createDirectories(slotA());
        Files.createDirectories(slotB());
        Files.createDirectories(root.resolve("staging"));
    }

    public Path root() { return root; }

    public Path slotsDir() { return root.resolve("slots"); }

    public Path slotDir(String slot) { return slotsDir().resolve(slot); }

    public Path slotA() { return slotDir(SLOT_A); }

    public Path slotB() { return slotDir(SLOT_B); }

    public Path slotPayload(String slot) { return slotDir(slot).resolve("payload.bin"); }

    public Path slotManifest(String slot) { return slotDir(slot).resolve("manifest.json"); }

    public Path slotVersionFile(String slot) { return slotDir(slot).resolve("VERSION"); }

    public Path stateFile() { return root.resolve("state.json"); }

    /**
     * “当前槽位”指针：指向 slots/A 或 slots/B 的符号链接，由 rename 原子替换。
     * 任意时刻进程/掉电重启，该链接唯一决定运行版本，不会指向半安装槽。
     */
    public Path currentLink() { return root.resolve("current"); }

    public Path historyFile() { return root.resolve("history.json"); }

    public Path stagingDir(String upgradeId) {
        return root.resolve("staging").resolve(safe(upgradeId));
    }

    public Path stagingChunksDir(String upgradeId) {
        return stagingDir(upgradeId).resolve("chunks");
    }

    public Path stagingVerifiedFile(String upgradeId) {
        return stagingDir(upgradeId).resolve("verified.json");
    }

    public Path stagingAssembled(String upgradeId) {
        return stagingDir(upgradeId).resolve("payload.assembled");
    }

    public Path otherSlot(String currentSlot) {
        return SLOT_A.equals(currentSlot) ? slotB() : slotA();
    }

    public String otherSlotName(String currentSlot) {
        return SLOT_A.equals(currentSlot) ? SLOT_B : SLOT_A;
    }

    private static String safe(String upgradeId) {
        return upgradeId == null ? "_" : upgradeId.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
