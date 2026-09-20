package com.github.lechandonga.firmwareotajava.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.lechandonga.firmwareotajava.model.UpgradeRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 升级历史（history.json）。
 *
 * <p>仅做 append，从不重写或删除既有记录：回滚记录（ROLLED_BACK）一旦写入，
 * 后续任何升级/回滚都不会覆盖它。整文件更新同样采用临时文件 + 原子 rename，
 * 掉电不会产生半截历史。策略类拒绝（降级/最低版本）按需求不写历史。
 */
@Component
public class UpgradeHistory {

    private static final Logger log = LoggerFactory.getLogger(UpgradeHistory.class);

    private final SlotLayout layout;
    private final ObjectMapper objectMapper;

    public UpgradeHistory(SlotLayout layout) {
        this.layout = layout;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    public synchronized List<UpgradeRecord> all() {
        Path file = layout.historyFile();
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            List<UpgradeRecord> records = objectMapper.readValue(file.toFile(),
                    new TypeReference<List<UpgradeRecord>>() {
                    });
            return records == null ? new ArrayList<>() : records;
        } catch (IOException e) {
            throw new IllegalStateException("升级历史文件无法读取: " + file, e);
        }
    }

    public synchronized UpgradeRecord append(UpgradeRecord record) {
        List<UpgradeRecord> records = all();
        records.add(record);
        Path file = layout.historyFile();
        Path tmp = layout.root().resolve("history.json.tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), records);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("写入升级历史失败", e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 忽略临时文件清理失败
            }
        }
        log.info("升级历史追加记录 upgradeId={} outcome={} reject={} failure={} rollbackTo={}",
                record.upgradeId(), record.outcome(), record.rejectReason(),
                record.failureReason(), record.rolledBackTo());
        return record;
    }
}
