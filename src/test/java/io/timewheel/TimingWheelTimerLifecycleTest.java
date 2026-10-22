package io.timewheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Real-clock, real-thread tests for the driver thread and end-to-end behaviour. */
class TimingWheelTimerLifecycleTest {

    private static final String DRIVER = "timewheel-driver";

    private static long driverThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> DRIVER.equals(t.getName()) && t.isAlive())
                .count();
    }

    private static void awaitDriverCount(long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (driverThreads() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, driverThreads());
    }

    @Test
    void systemClockNeverGoesBackwards() {
        long previous = Clock.SYSTEM.nowMs();
        for (int i = 0; i < 100_000; i++) {
            long now = Clock.SYSTEM.nowMs();
            assertTrue(now >= previous, "clock went backwards");
            previous = now;
        }
    }

    @Test
    void startingTwiceIsRejected() {
        try (TimingWheelTimer timer = new TimingWheelTimer(1, 64, Runnable::run)) {
            timer.start();
            assertThrows(IllegalStateException.class, timer::start);
        }
    }

    @Test
    void closeStopsTheDriverThreadAndIsIdempotent() throws Exception {
        long before = driverThreads();
        TimingWheelTimer timer = new TimingWheelTimer(1, 64, Runnable::run);
        timer.start();
        awaitDriverCount(before + 1);

        timer.close();
        timer.close();
        awaitDriverCount(before);
    }

    @Test
    void closeWithoutStartIsHarmless() {
        new TimingWheelTimer(1, 64, Runnable::run).close();
    }

    @Test
    void taskFiresOnTheRealClockNotBeforeItsDelay() throws Exception {
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try (TimingWheelTimer timer = new TimingWheelTimer(1, 64, workers)) {
            timer.start();
            CountDownLatch fired = new CountDownLatch(1);
            AtomicReference<Long> firedAt = new AtomicReference<>();
            long startedAt = Clock.SYSTEM.nowMs();
            timer.schedule(80, () -> {
                firedAt.set(Clock.SYSTEM.nowMs());
                fired.countDown();
            });

            assertTrue(fired.await(3, TimeUnit.SECONDS), "task never fired");
            assertTrue(firedAt.get() - startedAt >= 80, "fired early: " + (firedAt.get() - startedAt) + " ms");
            assertEquals(0, timer.size());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void tasksRunOnTheExecutorNotOnTheDriverThread() throws Exception {
        ExecutorService workers = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-worker"));
        try (TimingWheelTimer timer = new TimingWheelTimer(1, 64, workers)) {
            timer.start();
            CountDownLatch fired = new CountDownLatch(1);
            AtomicReference<String> threadName = new AtomicReference<>();
            timer.schedule(20, () -> {
                threadName.set(Thread.currentThread().getName());
                fired.countDown();
            });
            assertTrue(fired.await(3, TimeUnit.SECONDS));
            assertEquals("test-worker", threadName.get());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void aSlowTaskDoesNotDelayOtherTasks() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        try (TimingWheelTimer timer = new TimingWheelTimer(1, 64, workers)) {
            timer.start();
            timer.schedule(10, () -> {
                try {
                    releaseSlow.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            CountDownLatch quick = new CountDownLatch(1);
            timer.schedule(60, quick::countDown);
            assertTrue(quick.await(3, TimeUnit.SECONDS), "quick task was blocked by the slow one");
        } finally {
            releaseSlow.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void rejectedExecutionDoesNotKillTheDriver() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.Executor flaky = r -> {
            if (calls.getAndIncrement() == 0) {
                throw new RejectedExecutionException("first submission rejected");
            }
            r.run();
        };
        try (TimingWheelTimer timer = new TimingWheelTimer(1, 64, flaky)) {
            timer.start();
            AtomicBoolean firstRan = new AtomicBoolean();
            CountDownLatch second = new CountDownLatch(1);
            timer.schedule(10, () -> firstRan.set(true));
            timer.schedule(60, second::countDown);

            assertTrue(second.await(3, TimeUnit.SECONDS), "driver died after a rejected submission");
            assertFalse(firstRan.get(), "the rejected task is lost in step 1 (addressed by ACK/retry later)");
        }
    }

    @Test
    void afterCloseNothingFiresAndTasksStayPending() throws Exception {
        TimingWheelTimer timer = new TimingWheelTimer(1, 64, Runnable::run);
        timer.start();
        timer.close();
        AtomicBoolean ran = new AtomicBoolean();
        timer.schedule(20, () -> ran.set(true));
        Thread.sleep(150);
        assertFalse(ran.get());
        assertEquals(1, timer.size());
    }
}
