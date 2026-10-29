package io.timewheel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    void replaceOverwritesAnActiveRecordButNeverCreatesOne() {
        TaskStore store = create();
        try {
            assertFalse(store.replace(record("a", 1)), "replace must not create a record");
            assertTrue(store.loadAll().isEmpty());

            assertTrue(store.putIfAbsent(record("a", 1)));
            TaskRecord retried = new TaskRecord("a", 9_000, new byte[] {1, 2, 3}, 2, "boom");
            assertTrue(store.replace(retried));
            List<TaskRecord> all = store.loadAll();
            assertEquals(1, all.size());
            assertEquals(retried, all.get(0));
        } finally {
            store.close();
        }
    }

    @Test
    void containsReportsActiveRecordsOnly() {
        TaskStore store = create();
        try {
            assertFalse(store.contains("a"));
            assertTrue(store.putIfAbsent(record("a", 1)));
            assertTrue(store.contains("a"));
            assertTrue(store.deadLetter(new TaskRecord("a", 1, null, 3, "boom")));
            assertFalse(store.contains("a"), "a dead letter is not active");
            assertFalse(store.contains("never"));
        } finally {
            store.close();
        }
    }

    @Test
    void deadLetterMovesTheRecordOutOfTheActiveSet() {
        TaskStore store = create();
        try {
            assertTrue(store.putIfAbsent(record("a", 1)));
            assertTrue(store.putIfAbsent(record("b", 2)));
            TaskRecord finalState = new TaskRecord("a", 1, new byte[] {1, 2, 3}, 5, "IllegalStateException: boom");
            assertTrue(store.deadLetter(finalState));

            assertEquals(List.of("b"), idsOf(store.loadAll()));
            List<TaskRecord> dead = store.loadDeadLetters();
            assertEquals(1, dead.size());
            assertEquals(finalState, dead.get(0));
            assertTrue(store.putIfAbsent(record("a", 7)), "the id is free again once its task is dead-lettered");
        } finally {
            store.close();
        }
    }

    @Test
    void deadLetteringSomethingThatIsNotActiveChangesNothing() {
        TaskStore store = create();
        try {
            assertFalse(store.deadLetter(new TaskRecord("ghost", 1, null, 1, "x")));
            assertTrue(store.loadDeadLetters().isEmpty());
            assertTrue(store.loadAll().isEmpty());
        } finally {
            store.close();
        }
    }

    @Test
    void aNewDeadLetterReplacesAnOlderOneWithTheSameId() {
        TaskStore store = create();
        try {
            assertTrue(store.putIfAbsent(record("a", 1)));
            assertTrue(store.deadLetter(new TaskRecord("a", 1, null, 3, "first")));
            assertTrue(store.putIfAbsent(record("a", 2)));
            assertTrue(store.deadLetter(new TaskRecord("a", 2, null, 5, "second")));

            List<TaskRecord> dead = store.loadDeadLetters();
            assertEquals(1, dead.size());
            assertEquals("second", dead.get(0).lastError());
        } finally {
            store.close();
        }
    }

    @Test
    void requeueMovesADeadLetterBackWithAFreshStart() {
        TaskStore store = create();
        try {
            assertTrue(store.putIfAbsent(record("a", 1)));
            assertTrue(store.deadLetter(new TaskRecord("a", 1, new byte[] {1, 2, 3}, 5, "boom")));

            Optional<TaskRecord> revived = store.requeueDeadLetter("a", 12_345);
            assertTrue(revived.isPresent());
            assertEquals(0, revived.get().attempts());
            assertEquals(null, revived.get().lastError());
            assertEquals(12_345, revived.get().deadlineEpochMs());
            assertArrayEquals(new byte[] {1, 2, 3}, revived.get().payload());

            assertTrue(store.loadDeadLetters().isEmpty());
            assertEquals(revived.get(), store.loadAll().get(0));
        } finally {
            store.close();
        }
    }

    @Test
    void requeueRefusesWhenThereIsNoDeadLetterOrTheIdIsActiveAgain() {
        TaskStore store = create();
        try {
            assertFalse(store.requeueDeadLetter("missing", 1).isPresent());

            assertTrue(store.putIfAbsent(record("a", 1)));
            assertTrue(store.deadLetter(new TaskRecord("a", 1, null, 3, "boom")));
            assertTrue(store.putIfAbsent(record("a", 2)));        // the id was reused
            assertFalse(store.requeueDeadLetter("a", 9).isPresent());
            assertEquals(1, store.loadDeadLetters().size(), "the dead letter must be left alone");
            assertEquals(2, store.loadAll().get(0).deadlineEpochMs());
        } finally {
            store.close();
        }
    }

    @Test
    void removeDeadLetterDiscardsIt() {
        TaskStore store = create();
        try {
            assertFalse(store.removeDeadLetter("a"));
            assertTrue(store.putIfAbsent(record("a", 1)));
            assertTrue(store.deadLetter(new TaskRecord("a", 1, null, 3, "boom")));
            assertTrue(store.removeDeadLetter("a"));
            assertFalse(store.removeDeadLetter("a"));
            assertTrue(store.loadDeadLetters().isEmpty());
        } finally {
            store.close();
        }
    }

    private static List<String> idsOf(List<TaskRecord> records) {
        List<String> ids = new java.util.ArrayList<>();
        for (TaskRecord r : records) {
            ids.add(r.id());
        }
        ids.sort(String::compareTo);
        return ids;
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
