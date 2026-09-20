package com.github.lechandonga.firmwareotajava.storage;

import com.github.lechandonga.firmwareotajava.config.OtaProperties;
import com.github.lechandonga.firmwareotajava.model.FirmwareManifest;
import com.github.lechandonga.firmwareotajava.model.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 出厂固件安装器（首次启动 / 存储完全为空时使用）。
 * 在 A 槽写入一份完整、可校验、可自检通过的基线固件，并建立 current 指针。
 */
@Component
public class FactoryInstaller {

    private static final Logger log = LoggerFactory.getLogger(FactoryInstaller.class);

    private final OtaProperties properties;
    private final SlotLayout layout;
    private final SlotInstaller installer;
    private final SlotSwitcher switcher;

    public FactoryInstaller(OtaProperties properties, SlotLayout layout,
                            SlotInstaller installer, SlotSwitcher switcher) {
        this.properties = properties;
        this.layout = layout;
        this.installer = installer;
        this.switcher = switcher;
    }

    /** 若设备尚未安装任何版本，则安装出厂固件；返回出厂版本。 */
    public Version installIfAbsent() {
        Version factory = Version.parse(properties.getFactoryVersion());
        if (installer.isSlotComplete(SlotLayout.SLOT_A)
                && switcher.currentSlotName() != null) {
            return factory;
        }
        byte[] payload = ("FACTORY FIRMWARE " + factory + "\n")
                .getBytes(StandardCharsets.UTF_8);
        String hash = com.github.lechandonga.firmwareotajava.pkg.HashUtil.sha256Hex(payload);
        FirmwareManifest manifest = new FirmwareManifest(
                "factory-" + factory,
                factory,
                properties.getDeviceModel(),
                properties.getHardwareVersion(),
                payload.length,
                hash,
                payload.length,
                List.of(hash),
                "SUCCESS",
                0L);

        Path staged = layout.stagingDir("factory").resolve("payload.assembled");
        try {
            Files.createDirectories(staged.getParent());
            Files.write(staged, payload);
            installer.eraseSlot(SlotLayout.SLOT_A);
            installer.install(SlotLayout.SLOT_A, manifest, staged);
            switcher.pointToInitial(SlotLayout.SLOT_A);
            Files.deleteIfExists(staged);
            log.info("出厂固件安装完成 version={} slot=A", factory);
        } catch (Exception e) {
            throw new IllegalStateException("出厂固件安装失败，设备无可用版本", e);
        }
        return factory;
    }
}
