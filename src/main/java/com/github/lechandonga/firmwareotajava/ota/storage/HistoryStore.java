package com.github.lechandonga.firmwareotajava.ota.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.lechandonga.firmwareotajava.ota.model.UpgradeRecord;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 追加式升级历史存储。条目永不删除（同一条目内部状态可被更新），
 * 回滚记录不会因后续升级而丢失。
 */
public class HistoryStore {

    private final DeviceStorage storage;
    private final JsonFileStore json;

    public HistoryStore(DeviceStorage storage, JsonFileStore json) {
        this.storage = storage;
        this.json = json;
    }

    public synchronized void append(UpgradeRecord record) {
        List<UpgradeRecord> records = load();
        records.add(record);
        save(records);
    }

    public synchronized void update(UpgradeRecord record) {
        List<UpgradeRecord> records = load();
        boolean replaced = false;
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).getUpgradeId().equals(record.getUpgradeId())) {
                records.set(i, record);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            records.add(record);
        }
        save(records);
    }

    public synchronized List<UpgradeRecord> all() {
        return List.copyOf(load());
    }

    public synchronized UpgradeRecord byId(String upgradeId) {
        return load().stream()
                .filter(r -> r.getUpgradeId().equals(upgradeId))
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private List<UpgradeRecord> load() {
        if (!java.nio.file.Files.exists(storage.historyFile())) {
            return new ArrayList<>();
        }
        try {
            List<UpgradeRecord> records = json.getMapper()
                    .readValue(storage.historyFile().toFile(), new TypeReference<List<UpgradeRecord>>() {
                    });
            return records == null ? new ArrayList<>() : records;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read upgrade history", e);
        }
    }

    private void save(List<UpgradeRecord> records) {
        json.write(storage.historyFile(), records);
    }
}
