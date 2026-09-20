package com.github.lechandonga.firmwareotajava.ota.slots;

import com.github.lechandonga.firmwareotajava.ota.fault.FaultInjection;
import com.github.lechandonga.firmwareotajava.ota.model.ImageInfo;
import com.github.lechandonga.firmwareotajava.ota.model.SlotLayout;
import com.github.lechandonga.firmwareotajava.ota.model.StagingState;
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
import java.nio.file.StandardCopyOption;

/**
 * A/B 槽位管理与原子切换。
 * 当前活动槽位在整个安装过程中保持可运行；新镜像先完整写入非活动槽，
 * 校验通过并写入 image.json 后，才以一次原子 slot-layout.json 替换完成版本切换。
 * 任意时刻掉电：layout 未切换 -> 旧版本继续在位；layout 已切换 -> 新版本完整可启动。
 */
public class SlotManager {

    private static final Logger log = LoggerFactory.getLogger(SlotManager.class);
    private static final int COPY_BUFFER = 4096;

    public static final String SLOT_A = "a";
    public static final String SLOT_B = "b";

    private final DeviceStorage storage;
    private final JsonFileStore json;
    private final FaultInjection faults;

    public SlotManager(DeviceStorage storage, JsonFileStore json, FaultInjection faults) {
        this.storage = storage;
        this.json = json;
        this.faults = faults;
    }

    /** 空存储时预置出厂镜像到 A 槽并将其设为活动/已确认槽。 */
    public synchronized void provisionFactoryIfEmpty(String factoryVersion, byte[] factoryPayload) {
        SlotLayout existing = json.readOrNull(storage.slotLayoutFile(), SlotLayout.class);
        if (existing != null) {
            return;
        }
        Path payload = storage.slotPayload(SLOT_A);
        try {
            Files.createDirectories(storage.slotDir(SLOT_A));
            Files.write(payload, factoryPayload);
        } catch (IOException e) {
            throw new UncheckedIOException("写入出厂镜像失败", e);
        }
        ImageInfo info = new ImageInfo();
        info.setVersion(factoryVersion);
        info.setPayloadSize(factoryPayload.length);
        info.setPayloadSha256(Hashes.sha256Hex(factoryPayload));
        info.setHardwareModel(null);
        info.setHardwareVersion(null);
        info.setMinCompatibleVersion(null);
        info.setSelfTest(com.github.lechandonga.firmwareotajava.ota.model.SelfTestSpec.alwaysOk());
        json.write(storage.slotImage(SLOT_A), info);

        SlotLayout layout = new SlotLayout();
        layout.setActiveSlot(SLOT_A);
        layout.setCommittedSlot(SLOT_A);
        layout.setPendingSlot(null);
        layout.setBootCount(0);
        json.write(storage.slotLayoutFile(), layout);
        log.info("出厂镜像 {} 已预置到槽位 A 并设为活动版本", factoryVersion);
    }

    public synchronized SlotLayout layout() {
        SlotLayout layout = json.readOrNull(storage.slotLayoutFile(), SlotLayout.class);
        if (layout == null) {
            throw new IllegalStateException("slot-layout 不存在，设备未初始化");
        }
        return layout;
    }

    public synchronized void writeLayout(SlotLayout layout) {
        json.write(storage.slotLayoutFile(), layout);
    }

    public synchronized ImageInfo imageInfo(String slot) {
        return json.readOrNull(storage.slotImage(slot), ImageInfo.class);
    }

    public synchronized ImageInfo activeImage() {
        return imageInfo(layout().getActiveSlot());
    }

    public synchronized ImageInfo committedImage() {
        return imageInfo(layout().getCommittedSlot());
    }

    /** 选择非活动槽位（安装目标）。 */
    public synchronized String inactiveSlot() {
        return otherSlot(layout().getActiveSlot());
    }

    /**
     * 将已终验的 staging 负载完整写入目标槽位（按分块顺序拼接），
     * 拷贝期间掉电注入会中断：此时 payload 残缺且 image.json 尚未更新，
     * 该槽位不会被启动决策信任。成功后重新校验 SHA-256 再原子落 image.json。
     */
    public synchronized void installToSlot(String targetSlot, StagingState state, ImageInfo info) {
        if (!SLOT_A.equals(targetSlot) && !SLOT_B.equals(targetSlot)) {
            throw new IllegalArgumentException("非法槽位: " + targetSlot);
        }
        Path targetPayload = storage.slotPayload(targetSlot);
        Path targetImage = storage.slotImage(targetSlot);
        Path tmpPayload = storage.slotDir(targetSlot).resolve("payload.incoming");
        try {
            Files.createDirectories(storage.slotDir(targetSlot));
            Files.deleteIfExists(tmpPayload);
            long total = 0L;
            try (OutputStream out = Files.newOutputStream(tmpPayload)) {
                for (int i = 0; i < state.getChunksVerified().size(); i++) {
                    Path chunk = storage.chunkFile(state.getUpgradeId(), i);
                    try (InputStream in = Files.newInputStream(chunk)) {
                        byte[] buffer = new byte[COPY_BUFFER];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                            total += read;
                            faults.checkInstallProgress(total);
                        }
                    }
                }
                out.flush();
            }
            String actualHash = Hashes.sha256Hex(tmpPayload);
            if (total != state.getPayloadSize()
                    || !actualHash.equalsIgnoreCase(state.getPayloadSha256())) {
                Files.deleteIfExists(tmpPayload);
                throw new IllegalStateException("写入槽位 " + targetSlot + " 的镜像校验失败，已丢弃");
            }
            try {
                Files.move(tmpPayload, targetPayload,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmpPayload, targetPayload, StandardCopyOption.REPLACE_EXISTING);
            }
            // image.json 最后写入：它的存在性与一致性是“该槽位可信任”的标志
            json.write(targetImage, info);
            log.info("镜像 {} 已完整写入槽位 {}（{} 字节），尚未切换活动槽",
                    info.getVersion(), targetSlot, total);
        } catch (IOException e) {
            deleteQuietly(tmpPayload);
            throw new UncheckedIOException("安装镜像到槽位 " + targetSlot + " 失败", e);
        } catch (RuntimeException e) {
            deleteQuietly(tmpPayload);
            throw e;
        }
    }

    /**
     * 原子切换：活动 -> 目标槽，旧活动槽记为 committed（回滚目标），目标槽记为 pending。
     * 前置条件：目标槽 payload 与 image.json 完整一致。
     */
    public synchronized void activatePending(String targetSlot, String upgradeId) {
        if (!verifySlot(targetSlot)) {
            throw new IllegalStateException("目标槽位 " + targetSlot + " 不完整，禁止切换");
        }
        SlotLayout layout = layout();
        String previousActive = layout.getActiveSlot();
        SlotLayout next = new SlotLayout();
        next.setActiveSlot(targetSlot);
        next.setCommittedSlot(previousActive);
        next.setPendingSlot(targetSlot);
        next.setPendingUpgradeId(upgradeId);
        // 切换期间保留“上一个好版本”指针：旧 committed 仍是已确认好版本
        next.setPreviousCommittedSlot(layout.getCommittedSlot());
        next.setBootCount(layout.getBootCount());
        next.setSelfTestAttempts(0);
        json.write(storage.slotLayoutFile(), next);
        log.info("槽位原子切换：{}({}) -> {}，等待新版本启动自检",
                previousActive, safeVersion(previousActive), targetSlot);
    }

    /** 自检通过：pending 转正为 committed，活动槽保持不变。 */
    public synchronized void markCommitted() {
        SlotLayout layout = layout();
        if (layout.getPendingSlot() == null) {
            return;
        }
        SlotLayout next = new SlotLayout();
        next.setActiveSlot(layout.getActiveSlot());
        next.setCommittedSlot(layout.getActiveSlot());
        next.setPendingSlot(null);
        next.setPendingUpgradeId(null);
        // 原 committed（切换前的在位版本）成为“上一个好版本”，供手动回滚
        next.setPreviousCommittedSlot(layout.getCommittedSlot());
        next.setBootCount(layout.getBootCount());
        next.setSelfTestAttempts(layout.getSelfTestAttempts());
        json.write(storage.slotLayoutFile(), next);
        log.info("版本 {} 自检通过，已转正为 committed；上一个好版本保留在槽位 {}",
                safeVersion(layout.getActiveSlot()), layout.getCommittedSlot());
    }

    /** 回滚：active 原子切回 committed，清空 pending。 */
    public synchronized void rollbackToCommitted() {
        SlotLayout layout = layout();
        String badSlot = layout.getActiveSlot();
        String goodSlot = layout.getCommittedSlot();
        if (goodSlot == null || goodSlot.equals(badSlot) || !verifySlot(goodSlot)) {
            throw new IllegalStateException("没有完整可用的已确认槽位，无法回滚");
        }
        SlotLayout next = new SlotLayout();
        next.setActiveSlot(goodSlot);
        next.setCommittedSlot(goodSlot);
        next.setPendingSlot(null);
        next.setPendingUpgradeId(null);
        next.setPreviousCommittedSlot(null);
        next.setBootCount(layout.getBootCount());
        next.setSelfTestAttempts(0);
        json.write(storage.slotLayoutFile(), next);
        log.warn("回滚：放弃槽位 {}，恢复到槽位 {}（版本 {}）",
                badSlot, goodSlot, safeVersion(goodSlot));
    }

    /** 是否存在与当前活动版本不同的、完整的已确认好版本（用于手动回滚）。 */
    public synchronized boolean hasRollbackTarget() {
        SlotLayout layout = layout();
        String target = layout.getPreviousCommittedSlot();
        if (target == null || target.equals(layout.getActiveSlot())) {
            return false;
        }
        return verifySlot(target);
    }

    /**
     * 手动回滚到上一个已确认好版本（转正后仍保留在另一槽位）。
     */
    public synchronized void rollbackToPreviousGood() {
        SlotLayout layout = layout();
        String target = layout.getPreviousCommittedSlot();
        if (target == null || target.equals(layout.getActiveSlot()) || !verifySlot(target)) {
            throw new IllegalStateException("没有完整可用的上一好版本，无法回滚");
        }
        String current = layout.getActiveSlot();
        SlotLayout next = new SlotLayout();
        next.setActiveSlot(target);
        next.setCommittedSlot(target);
        next.setPendingSlot(null);
        next.setPendingUpgradeId(null);
        next.setPreviousCommittedSlot(null);
        next.setBootCount(layout.getBootCount());
        next.setSelfTestAttempts(0);
        json.write(storage.slotLayoutFile(), next);
        log.warn("手动回滚：放弃槽位 {}（版本 {}），恢复到槽位 {}（版本 {}）",
                current, safeVersion(current), target, safeVersion(target));
    }

    /** 校验某槽位负载与 image.json 是否完整一致。 */
    public synchronized boolean verifySlot(String slot) {
        ImageInfo info = imageInfo(slot);
        Path payload = storage.slotPayload(slot);
        if (info == null || !Files.isRegularFile(payload)) {
            return false;
        }
        try {
            if (Files.size(payload) != info.getPayloadSize()) {
                return false;
            }
            return Hashes.sha256Hex(payload).equalsIgnoreCase(info.getPayloadSha256());
        } catch (IOException e) {
            log.warn("校验槽位 {} 时发生 IO 异常: {}", slot, e.getMessage());
            return false;
        }
    }

    public String otherSlot(String slot) {
        return SLOT_A.equals(slot) ? SLOT_B : SLOT_A;
    }

    private String safeVersion(String slot) {
        if (slot == null) {
            return "?";
        }
        ImageInfo info = imageInfo(slot);
        return info == null ? "?" : info.getVersion();
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
