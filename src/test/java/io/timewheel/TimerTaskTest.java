package io.timewheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TimerTaskTest {

    private TimerTask newTask() {
        return new TimerTask(100, () -> { });
    }

    @Test
    void newTaskIsPending() {
        TimerTask task = newTask();
        assertFalse(task.isCancelled());
        assertFalse(task.isFired());
        assertEquals(100, task.delayMs());
    }

    @Test
    void cancelSucceedsOnceAndBlocksFiring() {
        TimerTask task = newTask();
        assertTrue(task.cancel());
        assertFalse(task.cancel());
        assertTrue(task.isCancelled());
        assertFalse(task.tryFire());
        assertFalse(task.isFired());
    }

    @Test
    void firingBlocksCancelAndSecondFire() {
        TimerTask task = newTask();
        assertTrue(task.tryFire());
        assertFalse(task.tryFire());
        assertFalse(task.cancel());
        assertTrue(task.isFired());
        assertFalse(task.isCancelled());
    }

    @Test
    void cancelWithoutAnEntryDoesNotFail() {
        assertTrue(newTask().cancel());
    }

    @Test
    void exactlyOneOfManyConcurrentFirersWins() throws Exception {
        TimerTask task = newTask();
        int threads = 8;
        AtomicInteger winners = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    if (task.tryFire()) {
                        winners.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await();
        assertEquals(1, winners.get());
    }

    @Test
    void cancelAndFireRaceHasExactlyOneWinner() throws Exception {
        for (int round = 0; round < 2_000; round++) {
            TimerTask task = newTask();
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger wins = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(2);
            Thread canceller = new Thread(() -> {
                try {
                    start.await();
                    if (task.cancel()) {
                        wins.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            Thread firer = new Thread(() -> {
                try {
                    start.await();
                    if (task.tryFire()) {
                        wins.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            canceller.start();
            firer.start();
            start.countDown();
            done.await();
            assertEquals(1, wins.get(), "round " + round);
        }
    }
}
