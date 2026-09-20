package com.github.lechandonga.firmwareotajava.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.firmwareotajava.model.DeviceState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 设备持久状态存储。
 *
 * <p>写入采用“临时文件 + 原子 rename”：任意时刻掉电，state.json 要么是
 * 完整旧值、要么是完整新值，不会出现半写损坏。rename 在本地文件系统
 * 上是原子操作，这是掉电后能确定恢复的前提。
 */
@Component
public class StateStore {

    private static final Logger log = LoggerFactory.getLogger(StateStore.class);

    private final SlotLayout layout;
    private final ObjectMapper objectMapper;

    public StateStore(SlotLayout layout, ObjectMapper objectMapper) {
        this.layout = layout;
        this.objectMapper = objectMapper;
    }

    public DeviceState load() {
        Path file = layout.stateFile();
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return objectMapper.readValue(file.toFile(), DeviceState.class);
        } catch (IOException e) {
            // 原子写保证 state.json 不会半写；走到这里说明文件被外部破坏。
            // 不在这里猜测状态，交给上层明确报错，避免落到错误版本。
            throw new UncheckedIOException("设备状态文件损坏，无法安全恢复: " + file, e);
        }
    }

    public synchronized void save(DeviceState state) {
        Path file = layout.stateFile();
        Path tmp = layout.root().resolve("state.json.tmp." + Thread.currentThread().getId());
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), state);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("持久化设备状态失败", e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // tmp 残留不影响正确性（load 只读 state.json），仅留日志
                log.debug("清理状态临时文件失败: {}", tmp);
            }
        }
    }
}
