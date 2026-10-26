package io.timewheel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DurableSchedulerTest {

    /** Wall-clock time is shared by every simulated process: it is the real world. */
    private final ManualClock wall = new ManualClock(1_000_000);
    private final InMemoryTaskStore store = new InMemoryTaskStore();

    /**
     * One process lifetime: a fresh timer on its OWN monotonic clock (a restart resets that
     * clock, which is why deadlines must be persisted as wall-clock time) and a direct executor
     * so handlers run inline and the tests stay deterministic.
     */
    private final class Process {
        final ManualClock mono = new ManualClock(0);
        final TimingWheelTimer timer = new TimingWheelTimer(1, 64, Runnable::run, mono);
        final List<String> handled = new ArrayList<>();
        final List<TaskRecord> records = new ArrayList<>();
        final DurableScheduler scheduler;

        Process(TaskStore taskStore) {
            this(taskStore, null);
        }

        Process(TaskStore taskStore, TaskHandler custom) {
            TaskHandler recording = record -> {
                records.add(record);
                handled.add(record.id());
            };
            TaskHandler handler = custom != null ? custom : recording;
            this.scheduler = new DurableScheduler(timer, taskStore, handler, wall);
        }

        void advance(long ms) throws InterruptedException {
            mono.set(mono.nowMs() + ms);
            wall.set(wall.nowMs() + ms);
            timer.advanceClock(0);
        }
    }

    private static List<String> idsIn(TaskStore store) {
        List<String> ids = new ArrayList<>();
        for (TaskRecord r : store.loadAll()) {
            ids.add(r.id());
        }
        ids.sort(String::compareTo);
        return ids;
    }

    @Test
    void scheduleWritesTheRecordBeforeAnythingFires() {
        Process p = new Process(store);
        p.scheduler.recover();
        p.scheduler.schedule("a", 1_000, new byte[] {7, 8});

        List<TaskRecord> stored = store.loadAll();
        assertEquals(1, stored.size());
        assertEquals("a", stored.get(0).id());
        assertEquals(1_001_000, stored.get(0).deadlineEpochMs());
        assertArrayEquals(new byte[] {7, 8}, stored.get(0).payload());
        assertTrue(p.handled.isEmpty());
    }

    @Test
    void handlerRunsAtTheDeadlineThenTheRecordIsRemoved() throws Exception {
        Process p = new Process(store);
        p.scheduler.recover();
        p.scheduler.schedule("a", 500, new byte[] {9});

        p.advance(499);
        assertTrue(p.handled.isEmpty());
        assertEquals(1, store.loadAll().size());

        p.advance(1);
        assertEquals(List.of("a"), p.handled);
        assertArrayEquals(new byte[] {9}, p.records.get(0).payload());
        assertTrue(store.loadAll().isEmpty());
    }

    @Test
    void cancelRemovesTheRecordAndTheTaskNeverRuns() throws Exception {
        Process p = new Process(store);
        p.scheduler.recover();
        p.scheduler.schedule("a", 500, null);

        assertTrue(p.scheduler.cancel("a"));
        assertTrue(store.loadAll().isEmpty());
        assertFalse(p.scheduler.cancel("a"));
        assertFalse(p.scheduler.cancel("never-existed"));

        p.advance(10_000);
        assertTrue(p.handled.isEmpty());
    }

    @Test
    void aDuplicateIdIsRejectedAndTheFirstTaskIsUntouched() throws Exception {
        Process p = new Process(store);
        p.scheduler.recover();
        p.scheduler.schedule("a", 500, new byte[] {1});
        assertThrows(IllegalArgumentException.class, () -> p.scheduler.schedule("a", 900, new byte[] {2}));

        assertEquals(1_000_500, store.loadAll().get(0).deadlineEpochMs());
        p.advance(500);
        assertEquals(List.of("a"), p.handled);
    }

    @Test
    void recoverMustComeFirstAndOnlyOnce() {
        Process p = new Process(store);
        assertThrows(IllegalStateException.class, () -> p.scheduler.schedule("a", 1, null));
        assertThrows(IllegalStateException.class, () -> p.scheduler.cancel("a"));
        assertEquals(0, p.scheduler.recover());
        assertThrows(IllegalStateException.class, p.scheduler::recover);
    }

    @Test
    void invalidArgumentsAreRejectedBeforeTouchingTheStore() {
        Process p = new Process(store);
        p.scheduler.recover();
        assertThrows(IllegalArgumentException.class, () -> p.scheduler.schedule("a", -1, null));
        assertThrows(IllegalArgumentException.class, () -> p.scheduler.schedule("", 1, null));
        assertThrows(IllegalArgumentException.class, () -> p.scheduler.schedule("a", Long.MAX_VALUE, null));
        assertTrue(store.loadAll().isEmpty());
    }

    /** The headline feature: kill the process, start a new one on the same store. */
    @Test
    void tasksSurviveACrashAndRunAtTheRightWallClockTime() throws Exception {
        Process before = new Process(store);
        before.scheduler.recover();
        before.scheduler.schedule("A", 1_000, null);
        before.scheduler.schedule("B", 5_000, null);
        before.scheduler.schedule("C", 60_000, null);
        // crash: `before` is dropped without ever firing; two seconds of downtime pass
        wall.set(wall.nowMs() + 2_000);

        Process after = new Process(store);
        assertEquals(3, after.scheduler.recover());
        assertEquals(List.of("A"), after.handled);           // overdue: runs immediately
        assertEquals(List.of("B", "C"), idsIn(store));

        after.advance(2_999);
        assertEquals(List.of("A"), after.handled);           // B has 3000 ms left, not yet
        after.advance(1);
        assertEquals(List.of("A", "B"), after.handled);
        assertEquals(List.of("C"), idsIn(store));
        assertTrue(before.handled.isEmpty());                 // the old process never ran anything
    }

    @Test
    void overdueTasksRunInDeadlineOrderOnRecovery() {
        Process before = new Process(store);
        before.scheduler.recover();
        before.scheduler.schedule("X", 3_000, null);
        before.scheduler.schedule("Y", 1_000, null);
        before.scheduler.schedule("Z", 2_000, null);
        wall.set(wall.nowMs() + 10_000);

        Process after = new Process(store);
        assertEquals(3, after.scheduler.recover());
        assertEquals(List.of("Y", "Z", "X"), after.handled);
        assertTrue(store.loadAll().isEmpty());
    }

    @Test
    void aFailingHandlerKeepsTheRecordAndItIsRedeliveredAfterRecovery() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        Process failing = new Process(store, record -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("boom");
        });
        failing.scheduler.recover();
        failing.scheduler.schedule("a", 100, null);
        failing.advance(100);
        assertEquals(1, attempts.get());
        assertEquals(List.of("a"), idsIn(store));              // not acknowledged
        failing.advance(10_000);
        assertEquals(1, attempts.get());                       // no retry inside this process (step 5)

        Process healthy = new Process(store);
        assertEquals(1, healthy.scheduler.recover());
        assertEquals(List.of("a"), healthy.handled);
        assertTrue(store.loadAll().isEmpty());
    }

    /** Documents at-least-once: ran, but the acknowledgement was lost in the crash. */
    @Test
    void aTaskWhoseRecordCouldNotBeRemovedRunsAgainAfterRecovery() throws Exception {
        FlakyRemoveStore flaky = new FlakyRemoveStore(store);
        flaky.failRemoves = true;
        Process first = new Process(flaky);
        first.scheduler.recover();
        first.scheduler.schedule("a", 100, null);
        first.advance(100);
        assertEquals(List.of("a"), first.handled);
        assertEquals(List.of("a"), idsIn(store));

        Process second = new Process(store);
        second.scheduler.recover();
        assertEquals(List.of("a"), second.handled);             // delivered a second time
        assertTrue(store.loadAll().isEmpty());
    }

    @Test
    void cancellingAFailedTaskStopsItFromBeingRedelivered() throws Exception {
        Process failing = new Process(store, record -> {
            throw new IllegalStateException("boom");
        });
        failing.scheduler.recover();
        failing.scheduler.schedule("a", 100, null);
        failing.advance(100);
        assertEquals(List.of("a"), idsIn(store));

        assertTrue(failing.scheduler.cancel("a"));

        Process after = new Process(store);
        assertEquals(0, after.scheduler.recover());
        assertTrue(after.handled.isEmpty());
    }

    @Test
    void anIdCanBeReusedOnceItsTaskHasCompleted() throws Exception {
        Process p = new Process(store);
        p.scheduler.recover();
        p.scheduler.schedule("a", 0, null);                    // runs inline
        p.scheduler.schedule("a", 100, null);
        p.advance(100);
        assertEquals(List.of("a", "a"), p.handled);
        assertTrue(store.loadAll().isEmpty());
    }

    @Test
    void noTaskStaysRegisteredInMemoryAfterItHasRun() throws Exception {
        Process p = new Process(store);
        p.scheduler.recover();
        p.scheduler.schedule("inline", 0, null);               // fires before schedule() returns
        assertEquals(0, p.scheduler.armedCount());

        p.scheduler.schedule("later", 100, null);
        p.scheduler.schedule("cancelled", 100, null);
        assertEquals(2, p.scheduler.armedCount());
        p.scheduler.cancel("cancelled");
        assertEquals(1, p.scheduler.armedCount());
        p.advance(100);
        assertEquals(0, p.scheduler.armedCount());
    }

    @Test
    void aTaskThatCannotBeArmedIsNotLeftBehindInTheStore() {
        Process p = new Process(store);
        p.scheduler.recover();
        long absurdDelay = Long.MAX_VALUE - wall.nowMs();      // deadline fits a long, the wheel cannot hold it
        assertThrows(IllegalArgumentException.class, () -> p.scheduler.schedule("big", absurdDelay, null));
        assertTrue(store.loadAll().isEmpty());
        p.scheduler.schedule("big", 10, null);                  // the id was released
    }

    @Test
    void recoverySkipsARecordItCannotArmInsteadOfFailing() {
        store.putIfAbsent(new TaskRecord("forever", Long.MAX_VALUE, null));
        store.putIfAbsent(new TaskRecord("normal", wall.nowMs() + 50, null));

        Process p = new Process(store);
        assertEquals(1, p.scheduler.recover());
        p.scheduler.schedule("after", 10, null);                // the scheduler is usable afterwards
    }

    @Test
    void endToEndOnTheRealClockWithAThreadPool() throws Exception {
        //total task
        final int n = 200;
        //as earlies the task could be possiblely executed
        ConcurrentHashMap<String, Long> earliest = new ConcurrentHashMap<>();
        //count of ran
        ConcurrentHashMap<String, AtomicInteger> runs = new ConcurrentHashMap<>();
        AtomicInteger early = new AtomicInteger();
        //count how many task been executed
        CountDownLatch all = new CountDownLatch(n);

        //executor tread pool
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor(4, 1024);

        try (TimingWheelTimer timer = new TimingWheelTimer(1, 64, executor)) {
            timer.start();
            DurableScheduler scheduler = new DurableScheduler(timer, store, record -> {
                if (Clock.SYSTEM.nowMs() < earliest.get(record.id())) {
                    early.incrementAndGet();
                }
                runs.computeIfAbsent(record.id(), k -> new AtomicInteger()).incrementAndGet();
                all.countDown();
            });
            scheduler.recover();
            for (int i = 0; i < n; i++) {
                long delay = 20 + (i % 180);
                earliest.put("t" + i, Clock.SYSTEM.nowMs() + delay);
                scheduler.schedule("t" + i, delay, null);
            }
            assertTrue(all.await(5, TimeUnit.SECONDS), "tasks were lost");
        } finally {
            executor.close();
        }
        assertEquals(0, early.get());
        assertEquals(n, runs.size());
        for (AtomicInteger count : runs.values()) {
            assertEquals(1, count.get());
        }
        assertTrue(store.loadAll().isEmpty());
    }

    /** Delegating store whose remove() can be made to fail, like a disk that dies after the handler ran. */
    private static final class FlakyRemoveStore implements TaskStore {
        private final TaskStore inner;
        volatile boolean failRemoves;

        FlakyRemoveStore(TaskStore inner) {
            this.inner = inner;
        }

        @Override
        public boolean putIfAbsent(TaskRecord record) {
            return inner.putIfAbsent(record);
        }

        @Override
        public boolean remove(String id) {
            if (failRemoves) {
                throw new TaskStoreException("simulated disk failure");
            }
            return inner.remove(id);
        }

        @Override
        public List<TaskRecord> loadAll() {
            return inner.loadAll();
        }

        @Override
        public void close() {
            inner.close();
        }
    }
}
