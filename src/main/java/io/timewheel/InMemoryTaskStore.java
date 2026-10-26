package io.timewheel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * keey task in memory, not persisted to disk, just for test
 * or the caller who want scheduler API without persistence
 * a test can simulate a crach of timer and shceduler but keeping this store though it is in memory
 */
public final class InMemoryTaskStore implements TaskStore{

    private final ConcurrentHashMap<String, TaskRecord> records = new ConcurrentHashMap<>();

    @Override
    public boolean putIfAbsent(TaskRecord record) {
        return records.putIfAbsent(record.id(), record) == null;
    }

    @Override
    public boolean remove(String Id) {
        return records.remove(Id) != null;
    }

    @Override
    public List<TaskRecord> loadAll() {
        return new ArrayList<>(records.values());
    }

    @Override
    public void close() {
    }
}
