package io.timewheel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DurableSchedulerTest {

    /** Three attempts, 100 ms then 200 ms between them, no jitter. */
    private static final RetryPolicy THREE_TRIES = new RetryPolicy(3, 100, 2.0, 10_000, 0.0);

    /** Wall-clock time is shared by every simulated process: it is the real world. */
    private final ManualClock wall = new ManualClock(1_000_000);
    private final InMemoryTaskStore store = new InMemoryTaskStore();

    /**
     * One process lifetime: a fresh timer on its OWN monotonic clock (a restart resets that
     * clock, which is why deadlines must be persisted as wall-clock time) and, by default, a
     * direct executor so handlers run inline and the tests stay deterministic.
     */
    private final class Process {
        final ManualClock mono = new ManualClock(0);
        final TimingWheelTimer timer;
        final List<String> handled = new ArrayList<>();
        final List<TaskRecord> records = new ArrayList<>();
        final List<TaskRecord> deadLettered = new ArrayList<>();
        final DurableScheduler scheduler;

        Process(TaskStore taskStore) {
            this(taskStore, null, RetryPolicy.defaults(), new ThreadPoolTaskExecutor(Runnable::run));
        }

        Process(TaskStore taskStore, TaskHandler custom) {
            this(taskStore, custom, RetryPolicy.defaults(), new ThreadPoolTaskExecutor(Runnable::run));
        }

        Process(TaskStore taskStore, TaskHandler custom, RetryPolicy policy) {
            this(taskStore, custom, policy, new ThreadPoolTaskExecutor(Runnable::run));
        }

        Process(TaskStore taskStore, TaskHandler custom, RetryPolicy policy, TaskExecutor executor) {
            this.timer = new TimingWheelTimer(1, 64, executor, mono);
            TaskHandler recording = record -> {
                records.add(record);
                handled.add(record.id());
            };
            DurableScheduler.Options options = new DurableScheduler.Options()
                    .wallClock(wall)
                    .retryPolicy(policy)
                    .rejectedRetryDelayMs(50)
                    .random(() -> 0.5)                       // no jitter
                    .deadLetterListener(deadLettered::add);
            this.scheduler = new DurableScheduler(timer, taskStore, custom != null ? custom : recording, options);
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

    private static TaskHandler failing() {
        return record -> {
            throw new IllegalStateException("boom");
        };
    }

    // ------------------------------------------------------------------ scheduling and ack

    @Test
    void scheduleWritesTheRecordBeforeAnythingFires() {
        Process p = new Process(store);
        p.scheduler.recover();
        p.scheduler.schedule("a", 1_000, new byte[] {7, 8});

        List<TaskRecord> stored = store.loadAll();
        assertEquals(1, stored.size());
        assertEquals("a", stored.get(0).id());
        assertEquals(1_001_000, stored.get(0).deadlineEpochMs());
        assertEquals(0, stored.get(0).attempts());
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
        assertEquals(0, p.records.get(0).attempts());
        assertTrue(store.loadAll().isEmpty());
        assertTrue(store.loadDeadLetters().isEmpty());
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
    void aRecordRemovedFromTheStoreIsNotDeliveredEvenIfItsTimerStillFires() throws Exception {
        Process p = new Process(store);
        p.scheduler.recover();
        p.scheduler.schedule("a", 500, null);
        store.remove("a");                       // bypass cancel(): the wheel entry is still armed

        p.advance(500);
        assertTrue(p.handled.isEmpty(), "the handler must not start for a record that no longer exists");
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
        assertThrows(IllegalStateException.class, () -> p.scheduler.retryDeadLetter("a"));
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

    // ------------------------------------------------------------------ crash recovery

    /** Kill the process, start a new one on the same store. */
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

    // ------------------------------------------------------------------ retry

    @Test
    void aFailingTaskIsRetriedWithBackoffAndTheRetryStateIsPersisted() throws Exception {
        List<Integer> attemptsSeen = new ArrayList<>();
        Process p = new Process(store, record -> {
            attemptsSeen.add(record.attempts());
            if (record.attempts() < 2) {
                throw new IllegalStateException("boom");
            }
        }, new RetryPolicy(4, 1_000, 2.0, 60_000, 0.0));
        p.scheduler.recover();
        p.scheduler.schedule("a", 100, null);

        p.advance(100);                                      // attempt 1 fails
        assertEquals(List.of(0), attemptsSeen);
        TaskRecord waiting = store.loadAll().get(0);
        assertEquals(1, waiting.attempts());
        assertEquals("IllegalStateException: boom", waiting.lastError());
        assertEquals(wall.nowMs() + 1_000, waiting.deadlineEpochMs());   // next try in 1 s

        p.advance(999);
        assertEquals(List.of(0), attemptsSeen);
        p.advance(1);                                        // attempt 2 fails
        assertEquals(List.of(0, 1), attemptsSeen);
        assertEquals(2, store.loadAll().get(0).attempts());
        assertEquals(wall.nowMs() + 2_000, store.loadAll().get(0).deadlineEpochMs());   // then 2 s

        p.advance(1_999);
        assertEquals(List.of(0, 1), attemptsSeen);
        p.advance(1);                                        // attempt 3 succeeds
        assertEquals(List.of(0, 1, 2), attemptsSeen);
        assertTrue(store.loadAll().isEmpty());
        assertTrue(store.loadDeadLetters().isEmpty());
        assertTrue(p.deadLettered.isEmpty());
    }

    @Test
    void theAttemptCountSurvivesACrashSoAPoisonTaskCannotRetryForever() throws Exception {
        Process before = new Process(store, failing(), new RetryPolicy(5, 1_000, 2.0, 60_000, 0.0));
        before.scheduler.recover();
        before.scheduler.schedule("a", 100, null);
        before.advance(100);                                 // fails once; retry is due in 1 s
        assertEquals(1, store.loadAll().get(0).attempts());
        // crash during the wait, 400 ms of downtime
        wall.set(wall.nowMs() + 400);

        Process after = new Process(store);
        assertEquals(1, after.scheduler.recover());
        assertTrue(after.handled.isEmpty());
        after.advance(599);                                  // 1000 - 400 ms remained
        assertTrue(after.handled.isEmpty());
        after.advance(1);
        assertEquals(List.of("a"), after.handled);
        assertEquals(1, after.records.get(0).attempts());    // the handler knows it is a retry
        assertEquals("IllegalStateException: boom", after.records.get(0).lastError());
    }

    @Test
    void cancellingATaskThatIsWaitingForARetryStopsIt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Process p = new Process(store, record -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        }, THREE_TRIES);
        p.scheduler.recover();
        p.scheduler.schedule("a", 10, null);
        p.advance(10);
        assertEquals(1, calls.get());
        assertEquals(List.of("a"), idsIn(store));

        assertTrue(p.scheduler.cancel("a"));
        p.advance(10_000);
        assertEquals(1, calls.get());
        assertTrue(store.loadAll().isEmpty());
        assertTrue(store.loadDeadLetters().isEmpty());
    }

    // ------------------------------------------------------------------ dead letters

    @Test
    void afterTheLastAttemptTheTaskGoesToTheDeadLetterStore() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Process p = new Process(store, record -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        }, THREE_TRIES);
        p.scheduler.recover();
        p.scheduler.schedule("a", 10, new byte[] {4, 2});

        p.advance(10);                                       // attempt 1, retry in 100 ms
        p.advance(100);                                      // attempt 2, retry in 200 ms
        assertEquals(2, calls.get());
        assertTrue(p.deadLettered.isEmpty());
        p.advance(200);                                      // attempt 3: out of attempts
        assertEquals(3, calls.get());

        assertTrue(store.loadAll().isEmpty());
        assertEquals(1, store.loadDeadLetters().size());
        TaskRecord dead = p.scheduler.deadLetters().get(0);
        assertEquals("a", dead.id());
        assertEquals(3, dead.attempts());
        assertEquals("IllegalStateException: boom", dead.lastError());
        assertArrayEquals(new byte[] {4, 2}, dead.payload());
        assertEquals(1, p.deadLettered.size());               // the listener was told
        assertEquals("a", p.deadLettered.get(0).id());

        p.advance(60_000);
        assertEquals(3, calls.get());                         // nothing keeps retrying

        Process after = new Process(store, failing(), THREE_TRIES);
        assertEquals(0, after.scheduler.recover());           // recovery does not resurrect dead letters
        assertEquals(1, after.scheduler.deadLetters().size());
    }

    @Test
    void aNonRetryableFailureGoesStraightToTheDeadLetterStore() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Process p = new Process(store, record -> {
            calls.incrementAndGet();
            throw new NonRetryableException("payload is not valid JSON");
        });
        p.scheduler.recover();
        p.scheduler.schedule("a", 10, null);
        p.advance(10);

        assertEquals(1, calls.get());
        assertTrue(store.loadAll().isEmpty());
        TaskRecord dead = store.loadDeadLetters().get(0);
        assertEquals(1, dead.attempts());
        assertEquals("NonRetryableException: payload is not valid JSON", dead.lastError());
        p.advance(60_000);
        assertEquals(1, calls.get());
    }

    @Test
    void withNoRetryPolicyTheFirstFailureIsFinal() throws Exception {
        Process p = new Process(store, failing(), RetryPolicy.noRetry());
        p.scheduler.recover();
        p.scheduler.schedule("a", 10, null);
        p.advance(10);
        assertEquals(1, store.loadDeadLetters().size());
        assertTrue(store.loadAll().isEmpty());
    }

    @Test
    void aDeadLetterCanBeRetriedByAHumanAndThenSucceeds() throws Exception {
        AtomicBoolean broken = new AtomicBoolean(true);
        List<Integer> attemptsSeen = new ArrayList<>();
        Process p = new Process(store, record -> {
            attemptsSeen.add(record.attempts());
            if (broken.get()) {
                throw new IllegalStateException("downstream is down");
            }
        }, RetryPolicy.noRetry());
        p.scheduler.recover();
        p.scheduler.schedule("a", 10, null);
        p.advance(10);
        assertEquals(1, p.scheduler.deadLetters().size());

        broken.set(false);                                    // the operator fixed the downstream
        assertTrue(p.scheduler.retryDeadLetter("a"));
        assertEquals(List.of(0, 0), attemptsSeen);            // a fresh start: attempts were reset
        assertTrue(p.scheduler.deadLetters().isEmpty());
        assertTrue(store.loadAll().isEmpty());
        assertFalse(p.scheduler.retryDeadLetter("a"));
        assertFalse(p.scheduler.retryDeadLetter("never-existed"));
    }

    @Test
    void aDeadLetterCanBeDiscarded() throws Exception {
        Process p = new Process(store, failing(), RetryPolicy.noRetry());
        p.scheduler.recover();
        p.scheduler.schedule("a", 10, null);
        p.advance(10);

        assertTrue(p.scheduler.discardDeadLetter("a"));
        assertFalse(p.scheduler.discardDeadLetter("a"));
        assertTrue(p.scheduler.deadLetters().isEmpty());
        p.scheduler.schedule("a", 10, null);                  // the id is free again
    }

    @Test
    void aListenerThatThrowsDoesNotBreakTheDeadLettering() throws Exception {
        ManualClock mono = new ManualClock(0);
        TimingWheelTimer timer = new TimingWheelTimer(1, 64, new ThreadPoolTaskExecutor(Runnable::run), mono);
        DurableScheduler scheduler = new DurableScheduler(timer, store, failing(), new DurableScheduler.Options()
                .wallClock(wall).retryPolicy(RetryPolicy.noRetry())
                .deadLetterListener(record -> {
                    throw new IllegalStateException("pager is down");
                }));
        scheduler.recover();
        scheduler.schedule("a", 10, null);
        mono.set(10);
        timer.advanceClock(0);
        assertEquals(1, store.loadDeadLetters().size());
    }

    // ------------------------------------------------------------------ rejection by a full executor

    /** Refuses the first {@code rejections} submissions, then runs tasks inline. */
    private static final class FlakyExecutor implements TaskExecutor {
        private final AtomicInteger remaining;
        final AtomicInteger submissions = new AtomicInteger();

        FlakyExecutor(int rejections) {
            this.remaining = new AtomicInteger(rejections);
        }

        @Override
        public boolean submit(TimerTask task) {
            submissions.incrementAndGet();
            if (remaining.getAndDecrement() > 0) {
                return false;
            }
            task.runIfInPending();
            return true;
        }

        @Override
        public void close() {
        }
    }

    @Test
    void aTaskRefusedByAFullExecutorIsOfferedAgainAndNoAttemptIsUsedUp() throws Exception {
        FlakyExecutor executor = new FlakyExecutor(3);
        // no retries allowed: if a refusal counted as a failed attempt the task would be dead-lettered
        Process p = new Process(store, null, RetryPolicy.noRetry(), executor);
        p.scheduler.recover();
        p.scheduler.schedule("a", 50, null);

        for (int i = 0; i < 300 && p.handled.isEmpty(); i++) {
            p.advance(10);
        }
        assertEquals(List.of("a"), p.handled);
        assertEquals(4, executor.submissions.get());          // three refusals, then accepted
        assertEquals(0, p.records.get(0).attempts());
        assertTrue(store.loadAll().isEmpty());
        assertTrue(store.loadDeadLetters().isEmpty());
    }

    @Test
    void cancellingATaskThatWasRefusedStopsItFromBeingOfferedAgain() throws Exception {
        FlakyExecutor executor = new FlakyExecutor(1_000);
        Process p = new Process(store, null, RetryPolicy.noRetry(), executor);
        p.scheduler.recover();
        p.scheduler.schedule("a", 50, null);
        p.advance(50);
        assertEquals(1, executor.submissions.get());

        assertTrue(p.scheduler.cancel("a"));
        p.advance(10_000);
        assertEquals(1, executor.submissions.get());
        assertTrue(p.handled.isEmpty());
    }

    // ------------------------------------------------------------------ at-least-once, bookkeeping

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
        Process p = new Process(store, failing(), THREE_TRIES);
        p.scheduler.recover();
        p.scheduler.schedule("later", 100, null);
        p.scheduler.schedule("cancelled", 100, null);
        assertEquals(2, p.scheduler.armedCount());
        p.scheduler.cancel("cancelled");
        assertEquals(1, p.scheduler.armedCount());

        p.advance(100);                                         // fails, waits for its retry
        assertEquals(1, p.scheduler.armedCount());
        p.advance(100);
        p.advance(200);                                         // out of attempts: dead-lettered
        assertEquals(0, p.scheduler.armedCount());

        Process inline = new Process(new InMemoryTaskStore());
        inline.scheduler.recover();
        inline.scheduler.schedule("inline", 0, null);           // fires before schedule() returns
        assertEquals(0, inline.scheduler.armedCount());
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

    // ------------------------------------------------------------------ real clock, real threads

    @Test
    void endToEndOnTheRealClockWithAThreadPool() throws Exception {
        final int n = 200;
        ConcurrentHashMap<String, Long> earliest = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, AtomicInteger> runs = new ConcurrentHashMap<>();
        AtomicInteger early = new AtomicInteger();
        CountDownLatch all = new CountDownLatch(n);

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

    @Test
    void everyTaskFailsOnceThenSucceedsOnTheRealClock() throws Exception {
        final int n = 50;
        ConcurrentHashMap<String, AtomicInteger> failures = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, AtomicInteger> successes = new ConcurrentHashMap<>();
        CountDownLatch done = new CountDownLatch(n);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor(4, 1024);
        try (TimingWheelTimer timer = new TimingWheelTimer(1, 64, executor)) {
            timer.start();
            DurableScheduler scheduler = new DurableScheduler(timer, store, record -> {
                if (record.attempts() == 0) {
                    failures.computeIfAbsent(record.id(), k -> new AtomicInteger()).incrementAndGet();
                    throw new IllegalStateException("first attempt always fails");
                }
                successes.computeIfAbsent(record.id(), k -> new AtomicInteger()).incrementAndGet();
                done.countDown();
            }, new DurableScheduler.Options().retryPolicy(new RetryPolicy(3, 20, 2.0, 200, 0.0)));
            scheduler.recover();
            for (int i = 0; i < n; i++) {
                scheduler.schedule("t" + i, 10 + i, null);
            }
            assertTrue(done.await(5, TimeUnit.SECONDS), "retries were lost");
        } finally {
            executor.close();
        }
        assertEquals(n, failures.size());
        assertEquals(n, successes.size());
        for (int i = 0; i < n; i++) {
            assertEquals(1, failures.get("t" + i).get());
            assertEquals(1, successes.get("t" + i).get());
        }
        assertTrue(store.loadAll().isEmpty());
        assertTrue(store.loadDeadLetters().isEmpty());
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
        public boolean replace(TaskRecord record) {
            return inner.replace(record);
        }

        @Override
        public boolean remove(String id) {
            if (failRemoves) {
                throw new TaskStoreException("simulated disk failure");
            }
            return inner.remove(id);
        }

        @Override
        public boolean contains(String id) {
            return inner.contains(id);
        }

        @Override
        public List<TaskRecord> loadAll() {
            return inner.loadAll();
        }

        @Override
        public boolean deadLetter(TaskRecord finalState) {
            return inner.deadLetter(finalState);
        }

        @Override
        public List<TaskRecord> loadDeadLetters() {
            return inner.loadDeadLetters();
        }

        @Override
        public Optional<TaskRecord> requeueDeadLetter(String id, long deadlineEpochMs) {
            return inner.requeueDeadLetter(id, deadlineEpochMs);
        }

        @Override
        public boolean removeDeadLetter(String id) {
            return inner.removeDeadLetter(id);
        }

        @Override
        public void close() {
            inner.close();
        }
    }
}
