package com.github.lechandonga.firmwareotajava.ota.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 设备存储布局：
 * <pre>
 * root/
 *   slots/slot-layout.json
 *   slots/a/{payload.bin, image.json}
 *   slots/b/{payload.bin, image.json}
 *   staging/&lt;upgradeId&gt;/{chunks/, staging-state.json}
 *   history/upgrade-history.json
 * </pre>
 */
public class DeviceStorage {

    private final Path root;

    public DeviceStorage(String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        ensureDirectories();
    }

    private void ensureDirectories() {
        try {
            Files.createDirectories(slotsDir());
            Files.createDirectories(slotDir("a"));
            Files.createDirectories(slotDir("b"));
            Files.createDirectories(stagingDir());
            Files.createDirectories(historyFile().getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot initialize device storage at " + root, e);
        }
    }

    public Path root() {
        return root;
    }

    public Path slotsDir() {
        return root.resolve("slots");
    }

    public Path slotDir(String slot) {
        return slotsDir().resolve(slot);
    }

    public Path slotPayload(String slot) {
        return slotDir(slot).resolve("payload.bin");
    }

    public Path slotImage(String slot) {
        return slotDir(slot).resolve("image.json");
    }

    public Path slotLayoutFile() {
        return slotsDir().resolve("slot-layout.json");
    }

    public Path stagingDir() {
        return root.resolve("staging");
    }

    public Path upgradeDir(String upgradeId) {
        return stagingDir().resolve(upgradeId);
    }

    public Path chunkDir(String upgradeId) {
        return upgradeDir(upgradeId).resolve("chunks");
    }

    public Path chunkFile(String upgradeId, int index) {
        return chunkDir(upgradeId).resolve("chunk-" + index + ".bin");
    }

    public Path stagingStateFile(String upgradeId) {
        return upgradeDir(upgradeId).resolve("staging-state.json");
    }

    public Path historyFile() {
        return root.resolve("history").resolve("upgrade-history.json");
    }

    /** 当前存储已占用字节（slots + staging + history）。 */
    public long totalUsedBytes() {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot measure storage usage", e);
        }
    }
}
