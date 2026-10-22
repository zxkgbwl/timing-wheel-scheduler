package io.timewheel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.junit.jupiter.api.Assertions.*;

public class TimingWheelTimerTest {
    private final ManualClock clock = new ManualClock(0);

    private TimingWheelTimer manualTimer(long tickMs, int wheelSize) {
        return new TimingWheelTimer(tickMs, wheelSize, Runnable::run, clock);
    }

    private void tickTo(TimingWheelTimer timer, long timeMs)  throws InterruptedException{
        clock.set(timeMs);
        timer.advanceClock(0);
    }

    //task fired at its deadline
    @Test
    void taskFireAtItsDeadlineAndNotBefore() throws Exception {
        TimingWheelTimer timer = manualTimer(1, 64);
        AtomicInteger runs = new AtomicInteger();
        timer.schedule(5, runs::incrementAndGet);

        tickTo(timer,4);
        assertEquals(0, runs.get());

        tickTo(timer,5);
        assertEquals(1, runs.get());

        tickTo(timer,50);
        assertEquals(1, runs.get());
    }

    @Test
    void coarseTicksRoundTheDeadlineUpNeverFiringEarly() throws Exception {
        clock.set(3);
        TimingWheelTimer timer = new TimingWheelTimer(10, 10, Runnable::run, clock);
        AtomicInteger runs = new AtomicInteger();
        timer.schedule(15, runs::incrementAndGet);

        tickTo(timer, 19);
        assertEquals(0, runs.get());
        tickTo(timer,20);
        assertEquals(1, runs.get());

    }

    @Test
    void zeroDelayRunsImmediately() {
        TimingWheelTimer timer = manualTimer(1, 64);
        AtomicInteger runs = new AtomicInteger();
        timer.schedule(0, runs::incrementAndGet);
        assertEquals(1, runs.get());
        assertEquals(0, timer.size());
    }

    @Test
    void taskFireInDeadlineOrder() throws  Exception {
        TimingWheelTimer timer = manualTimer(1, 64);
        List<Integer> order = new ArrayList<>();
        timer.schedule(30, ()-> order.add(30));
        timer.schedule(10, ()-> order.add(10));
        timer.schedule(20, ()-> order.add(20));

        for (long t = 1; t <= 40; t++) {
            tickTo(timer, t);
        }
        assertEquals(List.of(10, 20, 30), order);
    }

    @Test
    void cancelledTaskNeverRunsAdnLeavesTheWheel() throws Exception {
        TimingWheelTimer timer = manualTimer(1, 64);
        AtomicInteger runs = new AtomicInteger();
        TimerTask task = timer.schedule(10, runs::incrementAndGet);
        assertEquals(1, timer.size());

        assertTrue(task.cancel());
        assertFalse(task.cancel());
        assertTrue(task.isCancelled());
        assertEquals(0, timer.size());

        tickTo(timer, 100);
        assertEquals(0, runs.get());
    }

    @Test
    void cancelAfterFiringReturnsFalse() throws Exception {
        TimingWheelTimer timer = manualTimer(1, 64);
        TimerTask task = timer.schedule(5, ()->{});
        tickTo(timer, 5);
        assertTrue(task.isFired());
        assertFalse(task.cancel());
    }

    @Test
    void  throwingTaskDoseNotStopOtherTask() throws Exception {
        TimingWheelTimer timer = manualTimer(1, 64);
        AtomicInteger runs = new AtomicInteger();
        timer.schedule(5, runs::incrementAndGet);
        timer.schedule(5, () -> {throw new IllegalArgumentException("ohh");});
        tickTo(timer, 5);
        assertEquals(1, runs.get());
    }

    @Test
    void scheduleAfterAnIdlePeriodSTillFitsTheWheel() throws Exception {
        TimingWheelTimer timer = manualTimer(5, 200);
        AtomicInteger runs = new AtomicInteger();
        tickTo(timer, 5000);
        timer.schedule(900, runs::incrementAndGet);

        tickTo(timer, 5899);
        assertEquals(0, runs.get());
        tickTo(timer, 5900);
        assertEquals(1, runs.get());

    }

    @Test
    void randomizaedTasksFireExactlyOnceNeverEarlyAndLessThanOneTickLate() throws Exception {
        final long tick = 5;
        TimingWheelTimer timer = manualTimer(tick, 200);
        Random random = new Random(42);
        int n = 20_000;
        long[] deadLine = new long[n];
        long[] firedAt = new long[n];
        int[] runs = new int[n];

        int scheduled = 0;
        //t is simulated time ticks
        for (long t = 0; t <=3000; t++) {
            clock.set(t);
            //
            while(scheduled < n && scheduled < (t+1) * n/2000) {
                final int id = scheduled++;
                long delay =  1 + random.nextInt(900);
                deadLine[id] = t + delay;
                timer.schedule(delay , ()->{
                    runs[id]++;
                    firedAt[id] = clock.nowMs();;
                });
            }
            timer.advanceClock(0);
        }

        //assert-------------------------
        // ==================== 补充的断言部分 ====================
        for (int id = 0; id < n; id++) {
            // 1. 验证：每个任务恰好执行一次（防止漏跑或重复触发）
            assertEquals(1, runs[id],
                    String.format("Task %d execution count error! Expected: 1, Actual: %d", id, runs[id]));

            // 2. 验证：绝不提前触发（实际触发时间必须大于或等于预设的截止时间）
            assertTrue(firedAt[id] >= deadLine[id],
                    String.format("Task %d fired early! Deadline: %d, FiredAt: %d", id, deadLine[id], firedAt[id]));

            // 3. 验证：延迟小于一个 Tick（实际触发时间减去截止时间的滞后量必须严格小于 tick 周期）
            long lag = firedAt[id] - deadLine[id];
            assertTrue(lag < tick,
                    String.format("Task %d fired too late! Deadline: %d, FiredAt: %d, Lag: %d ms (Max allowed < %d ms)",
                            id, deadLine[id], firedAt[id], lag, tick));
        }
    }

    @Test
    void concurrentSchedulingAndCancellationOnTheRealClockIsExactlyOnce() throws Exception {
        final int producers = 4;
        final int perProducer = 5_000;
        final int total = producers * perProducer;
        ExecutorService workers = Executors.newFixedThreadPool(4);
        ExecutorService producerPool = Executors.newFixedThreadPool(producers);
        AtomicIntegerArray runs = new AtomicIntegerArray(total);
        long[] earliestAllowed = new long[total];
        long[] firedAt = new long[total];
        boolean[] cancelled = new boolean[total];
        CountDownLatch  finishedOrCancelled = new CountDownLatch(total);

        try (TimingWheelTimer timer = new TimingWheelTimer(1, 2048, workers)) {
            timer.start();

            CountDownLatch produced = new CountDownLatch(producers);

            for (int p =0; p < producers; p++) {
                final int base = p * perProducer;
                producerPool.execute(()->{
                    Random random = new Random(base);

                    for (int i = 0; i < perProducer; i++) {
                        final int id = base + i;
                        long delay = 1 + random.nextInt(500);
                        earliestAllowed[id] = Clock.SYSTEM.nowMs() + delay;
                        TimerTask task = timer.schedule(delay, ()->{
                            firedAt[id] = Clock.SYSTEM.nowMs();
                            runs.incrementAndGet(id);
                            finishedOrCancelled.countDown();
                        });
                        if (random.nextInt(10) < 3 && task.cancel()) {
                            cancelled[id] = true;
                            finishedOrCancelled.countDown();
                        }

                    }

                    produced.countDown();
                });
            }


            assertTrue(produced.await(10, TimeUnit.SECONDS));
            assertTrue(finishedOrCancelled.await(10, TimeUnit.SECONDS),"tasks lost");

        }finally {
            producerPool.shutdownNow();
            workers.shutdown();
        }

        for (int id = 0; id < total; id++) {
            if (cancelled[id]) {
                assertEquals(0, runs.get(id), "Cancelled task " + id + " should not have run");
            }else {
                assertEquals(1, runs.get(id), "task did not run exactly onec: " + id);
                assertTrue(firedAt[id] >= earliestAllowed[id], "task fired early" + id);
            }
        }

    }

}
