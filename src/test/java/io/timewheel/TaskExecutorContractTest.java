package io.timewheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Behaviour every {@link TaskExecutor} must have. Each implementation extends this class and only
 * says how to build itself, so the thread pool and the Disruptor are held to the same rules.
 */
abstract class TaskExecutorContractTest {

    /** {@code capacity} is always a power of two so the Disruptor accepts it too. */
    abstract TaskExecutor create(int workers, int capacity);

    private static TimerTask task(Runnable action) {
        return new TimerTask(0, action);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "timed out");
    }
    //task run on thread of executor, not main Thread
    @Test
    void submittedTaskRunsOnAWorkerThread() throws Exception {
        TaskExecutor executor = create(2, 8);
        try {
            CountDownLatch ran = new CountDownLatch(1);
            AtomicReference<Thread> runner = new AtomicReference<>();
            assertTrue(executor.submit(task(() -> {
                runner.set(Thread.currentThread());
                ran.countDown();
            })));
            await(ran);
            assertTrue(runner.get() != Thread.currentThread(), "ran on the submitting thread");
        } finally {
            executor.close();
        }
    }

    //canelled
    @Test
    void cancelledTaskIsAcceptedButNeverRuns() {
        TaskExecutor executor = create(2, 8);
        AtomicInteger runs = new AtomicInteger();
        TimerTask t = task(runs::incrementAndGet);
        assertTrue(t.cancel());
        assertTrue(executor.submit(t));
        executor.close(); // graceful: everything accepted has been processed when this returns
        assertEquals(0, runs.get());
    }

    @Test
    void aTaskIsRunAtMostOnceEvenIfSubmittedTwice() {
        TaskExecutor executor = create(2, 8);
        AtomicInteger runs = new AtomicInteger();
        TimerTask t = task(runs::incrementAndGet);
        assertTrue(executor.submit(t));
        assertTrue(executor.submit(t));
        executor.close();
        assertEquals(1, runs.get());
    }

    @Test
    void throwingTaskDoesNotKillTheWorker() throws Exception {
        TaskExecutor executor = create(1, 8);
        try {
            CountDownLatch ran = new CountDownLatch(1);
            assertTrue(executor.submit(task(() -> {
                throw new IllegalStateException("boom");
            })));
            assertTrue(executor.submit(task(ran::countDown)));
            await(ran);
        } finally {
            executor.close();
        }
    }

    @Test
    void everyTaskRunsExactlyOnceUnderConcurrentSubmission() throws Exception {
        final int producers = 4;
        final int perProducer = 5_000;
        final int total = producers * perProducer;
        AtomicIntegerArray runs = new AtomicIntegerArray(total);
        TaskExecutor executor = create(4, 1024);
        Thread[] threads = new Thread[producers];
        try {
            for (int p = 0; p < producers; p++) {
                final int base = p * perProducer;
                threads[p] = new Thread(() -> {
                    for (int i = 0; i < perProducer; i++) {
                        final int id = base + i;
                        TimerTask t = task(() -> runs.incrementAndGet(id));
                        while (!executor.submit(t)) {
                            Thread.yield(); // full: back off and retry
                        }
                    }
                });
                threads[p].start();
            }
            for (Thread thread : threads) {
                thread.join();
            }
        } finally {
            executor.close();
        }
        for (int id = 0; id < total; id++) {
            assertEquals(1, runs.get(id), "task " + id);
        }
    }

    @Test
    void aFullExecutorRejectsInsteadOfBlockingAndRecoversAfterwards() throws Exception {
        TaskExecutor executor = create(2, 8);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();
        int accepted = 0;
        boolean rejected = false;
        try {
            //test1: queue will be filled and new task will be rejected
            for (int i = 0; i < 1_000; i++) {
                if (executor.submit(task(() -> {
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    ran.incrementAndGet();
                }))) {
                    accepted++;
                } else {
                    rejected = true;
                    break;
                }
            }
            assertTrue(rejected, "submit never refused a task although every worker is stuck");
            assertTrue(accepted < 1_000);

            //test2: all accepted will be run.
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (ran.get() < accepted && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(accepted, ran.get());

            //test3:
            CountDownLatch again = new CountDownLatch(1);
            boolean taken = false;
            for (int i = 0; i < 200 && !taken; i++) {
                taken = executor.submit(task(again::countDown));
                if (!taken) {
                    Thread.sleep(5);
                }
            }
            assertTrue(taken, "executor did not accept tasks again after draining");
            await(again);
        } finally {
            release.countDown();
            executor.close();
        }
    }

    @Test
    void submitAfterCloseIsRejected() {
        TaskExecutor executor = create(2, 8);
        executor.close();
        assertFalse(executor.submit(task(() -> { })));
    }

    @Test
    void closeRunsEverythingAlreadyAccepted() {
        TaskExecutor executor = create(2, 1024);
        AtomicInteger runs = new AtomicInteger();
        for (int i = 0; i < 500; i++) {
            assertTrue(executor.submit(task(runs::incrementAndGet)));
        }
        executor.close();
        assertEquals(500, runs.get());
    }

    @Test
    void closeIsIdempotent() {
        TaskExecutor executor = create(2, 8);
        executor.close();
        executor.close();
    }

    @Test
    void timerFiresTasksThroughThisExecutorExactlyOnceAndNeverEarly() throws Exception {
        final int n = 5_000;
        TaskExecutor executor = create(4, 4096);
        AtomicIntegerArray runs = new AtomicIntegerArray(n);
        long[] earliest = new long[n];
        long[] firedAt = new long[n];
        CountDownLatch all = new CountDownLatch(n);
        Random random = new Random(1);
        try (TimingWheelTimer timer = new TimingWheelTimer(1, 16, executor)) {
            timer.start();
            for (int i = 0; i < n; i++) {
                final int id = i;
                long delay = 1 + random.nextInt(300);
                earliest[id] = Clock.SYSTEM.nowMs() + delay;
                timer.schedule(delay, () -> {
                    firedAt[id] = Clock.SYSTEM.nowMs();
                    runs.incrementAndGet(id);
                    all.countDown();
                });
            }
            await(all);
        } finally {
            executor.close();
        }
        for (int id = 0; id < n; id++) {
            assertEquals(1, runs.get(id), "task " + id);
            assertTrue(firedAt[id] >= earliest[id], "task " + id + " fired early");
        }
    }
}
