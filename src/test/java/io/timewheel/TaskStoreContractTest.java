package io.timewheel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Behaviour every {@link TaskStore} must have. Implementations only say how to build one. */
abstract class TaskStoreContractTest {

    abstract TaskStore create();

    static TaskRecord record(String id, long deadline) {

        return new TaskRecord(id, deadline, new byte[] {1, 2, 3});
    }

    private static Map<String, TaskRecord> byId(List<TaskRecord> records) {
        Map<String, TaskRecord> map = new HashMap<>();
        for (TaskRecord r : records) {
            assertTrue(map.put(r.id(), r) == null, "duplicate id in loadAll: " + r.id());
        }
        return map;
    }

    @Test
    void aNewStoreIsEmpty() {
        TaskStore store = create();
        try {
            assertTrue(store.loadAll().isEmpty());
        } finally {
            store.close();
        }
    }

    @Test
    void storedRecordsComeBackIntact() {
        TaskStore store = create();
        try {
            byte[] binary = new byte[256];
            for (int i = 0; i < 256; i++) {
                binary[i] = (byte) i;
            }
            TaskRecord a = new TaskRecord("a", 100, binary);
            TaskRecord b = new TaskRecord("订单-2", 200, new byte[0]);
            assertTrue(store.putIfAbsent(a));
            assertTrue(store.putIfAbsent(b));

            Map<String, TaskRecord> loaded = byId(store.loadAll());
            assertEquals(2, loaded.size());
            assertEquals(a, loaded.get("a"));
            assertEquals(b, loaded.get("订单-2"));
            assertArrayEquals(binary, loaded.get("a").payload());
        } finally {
            store.close();
        }
    }

    @Test
    void aDuplicateIdIsRejectedAndTheOriginalKept() {
        TaskStore store = create();
        try {
            assertTrue(store.putIfAbsent(new TaskRecord("a", 100, new byte[] {1})));
            assertFalse(store.putIfAbsent(new TaskRecord("a", 999, new byte[] {2})));
            List<TaskRecord> all = store.loadAll();
            assertEquals(1, all.size());
            assertEquals(100, all.get(0).deadlineEpochMs());
            assertArrayEquals(new byte[] {1}, all.get(0).payload());
        } finally {
            store.close();
        }
    }

    @Test
    void removeReportsWhetherSomethingWasRemovedAndFreesTheId() {
        TaskStore store = create();
        try {
            assertFalse(store.remove("missing"));
            assertTrue(store.putIfAbsent(record("a", 1)));
            assertTrue(store.remove("a"));
            assertFalse(store.remove("a"));
            assertTrue(store.loadAll().isEmpty());
            assertTrue(store.putIfAbsent(record("a", 2)), "id should be reusable after removal");
        } finally {
            store.close();
        }
    }

    @Test
    void loadAllIsASnapshotNotALiveView() {
        TaskStore store = create();
        try {
            assertTrue(store.putIfAbsent(record("a", 1)));
            List<TaskRecord> snapshot = store.loadAll();
            assertTrue(store.putIfAbsent(record("b", 2)));
            assertTrue(store.remove("a"));
            assertEquals(1, snapshot.size());
            assertEquals("a", snapshot.get(0).id());
        } finally {
            store.close();
        }
    }

    @Test
    void closeIsIdempotent() {
        TaskStore store = create();
        store.close();
        store.close();
    }

    @Test
    void exactlyOneOfManyConcurrentWritersOfTheSameIdWins() throws Exception {
        TaskStore store = create();
        try {
            int threads = 8;
            AtomicInteger winners = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            for (int i = 0; i < threads; i++) {
                final int n = i;
                new Thread(() -> {
                    try {
                        start.await();
                        if (store.putIfAbsent(record("same", n))) {
                            winners.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }).start();
            }
            start.countDown();
            done.await();
            assertEquals(1, winners.get());
            assertEquals(1, store.loadAll().size());
        } finally {
            store.close();
        }
    }

    @Test
    void concurrentWritersOfDifferentIdsLoseNothing() throws Exception {
        TaskStore store = create();
        try {
            int threads = 4;
            int perThread = 250;
            CountDownLatch done = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                final int base = t * perThread;
                new Thread(() -> {
                    for (int i = 0; i < perThread; i++) {
                        assertTrue(store.putIfAbsent(record("id-" + (base + i), base + i)));
                    }
                    done.countDown();
                }).start();
            }
            done.await();
            assertEquals(threads * perThread, byId(store.loadAll()).size());
        } finally {
            store.close();
        }
    }
}
