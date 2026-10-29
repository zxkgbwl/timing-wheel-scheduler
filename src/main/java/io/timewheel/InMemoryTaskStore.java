package io.timewheel;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * keey task in memory, not persisted to disk, just for test
 * or the caller who want scheduler API without persistence
 * a test can simulate a crach of timer and shceduler but keeping this store though it is in memory
 *
 * One lock guards both maps, which is what makes moves between them atomic.
 */
public final class InMemoryTaskStore implements TaskStore {

    private final Map<String, TaskRecord> active = new HashMap<>();
    private final Map<String, TaskRecord> dead = new HashMap<>();

    @Override
    public synchronized boolean putIfAbsent(TaskRecord record) {
        return active.putIfAbsent(record.id(), record) == null;
    }

    @Override
    public synchronized boolean replace(TaskRecord record) {
        return active.replace(record.id(), record) != null;
    }

    @Override
    public synchronized boolean remove(String id) {
        return active.remove(id) != null;
    }

    @Override
    public synchronized boolean contains(String id) {
        return active.containsKey(id);
    }

    @Override
    public synchronized List<TaskRecord> loadAll() {
        return new ArrayList<>(active.values());
    }

    @Override
    public synchronized boolean deadLetter(TaskRecord finalState) {
        if (active.remove(finalState.id()) == null) {
            return false;
        }
        dead.put(finalState.id(), finalState);
        return true;
    }

    @Override
    public synchronized List<TaskRecord> loadDeadLetters() {
        return new ArrayList<>(dead.values());
    }

    @Override
    public synchronized Optional<TaskRecord> requeueDeadLetter(String id, long deadlineEpochMs) {
        TaskRecord letter = dead.get(id);
        if (letter == null || active.containsKey(id)) {
            return Optional.empty();
        }
        dead.remove(id);
        TaskRecord fresh = letter.restarted(deadlineEpochMs);
        active.put(id, fresh);
        return Optional.of(fresh);
    }

    @Override
    public synchronized boolean removeDeadLetter(String id) {
        return dead.remove(id) != null;
    }

    @Override
    public void close() {
        // nothing to release
    }
}

