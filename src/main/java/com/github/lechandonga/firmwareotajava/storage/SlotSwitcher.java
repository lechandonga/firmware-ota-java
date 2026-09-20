package com.github.lechandonga.firmwareotajava.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 槽位原子切换器。
 *
 * <p>通过 {@code current} 符号链接（相对指向 slots/A 或 slots/B）表示“当前运行槽”。
 * 切换 = 先建新临时链接再 rename 覆盖：rename 原子，掉电只会看到旧链接或新链接，
 * 因此重启后能确定地落在一份完整版本上。切换前必须确认目标槽完整可用。
 */
@Component
public class SlotSwitcher {

    private static final Logger log = LoggerFactory.getLogger(SlotSwitcher.class);

    private final SlotLayout layout;
    private final SlotInstaller installer;

    public SlotSwitcher(SlotLayout layout, SlotInstaller installer) {
        this.layout = layout;
        this.installer = installer;
    }

    /** 首次创建 current 链接（无既存链接时）。 */
    public synchronized void pointToInitial(String slot) {
        Path link = layout.currentLink();
        if (Files.exists(link) || Files.isSymbolicLink(link)) {
            return;
        }
        createLink(slot, link);
        log.info("当前槽位指针初始化为 {}", slot);
    }

    /** 原子切换到目标槽；目标槽不完整则拒绝切换（保证不落到坏版本）。 */
    public synchronized void switchTo(String slot) {
        if (!installer.isSlotComplete(slot)) {
            throw new IllegalStateException("目标槽不完整，拒绝切换: " + slot);
        }
        Path link = layout.currentLink();
        Path tmp = layout.root().resolve("current.next." + slot);
        try {
            Files.deleteIfExists(tmp);
            // 相对路径符号链接，目录整体搬迁后仍然有效
            Files.createSymbolicLink(tmp, Path.of("slots").resolve(slot));
            try {
                Files.move(tmp, link,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.move(tmp, link, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("原子切换槽位失败: " + slot, e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 临时链接残留不影响正确性
            }
        }
        log.info("槽位原子切换完成 -> {}", slot);
    }

    /** 读取 current 链接指向的槽名；无法解析返回 null。 */
    public String currentSlotName() {
        Path link = layout.currentLink();
        try {
            if (!Files.isSymbolicLink(link)) {
                return null;
            }
            Path target = Files.readSymbolicLink(link);
            String name = target.getFileName().toString();
            return SlotLayout.SLOT_A.equals(name) || SlotLayout.SLOT_B.equals(name)
                    ? name : null;
        } catch (IOException e) {
            return null;
        }
    }

    private void createLink(String slot, Path link) {
        try {
            Files.createSymbolicLink(link, Path.of("slots").resolve(slot));
        } catch (IOException e) {
            throw new IllegalStateException("创建当前槽位链接失败: " + slot, e);
        }
    }
}
