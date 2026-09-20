package com.github.lechandonga.firmwareotajava.ota.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * JSON 文件原子读写：先写同目录临时文件并 fsync，再 ATOMIC_MOVE 替换。
 * 任意时刻掉电后读到的要么是旧内容要么是新内容，不会读到半文件。
 */
public class JsonFileStore {

    private final ObjectMapper mapper;

    public JsonFileStore() {
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public <T> T read(Path file, Class<T> type) {
        try {
            return mapper.readValue(file.toFile(), type);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + file, e);
        }
    }

    public <T> T readOrNull(Path file, Class<T> type) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return mapper.readValue(file.toFile(), type);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + file, e);
        }
    }

    public void write(Path file, Object value) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
            try {
                mapper.writeValue(tmp.toFile(), value);
                try (var ch = java.nio.channels.FileChannel.open(tmp,
                        java.nio.file.StandardOpenOption.WRITE)) {
                    ch.force(true);
                }
                try {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write " + file, e);
        }
    }

    public ObjectMapper getMapper() {
        return mapper;
    }
}
