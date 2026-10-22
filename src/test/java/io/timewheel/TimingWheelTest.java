package io.timewheel;

import org.junit.jupiter.api.Test;

import java.util.concurrent.DelayQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TimingWheelTest {
    private final ManualClock clock = new ManualClock(0);
    private final DelayQueue<TimerTaskList> queue = new DelayQueue<>();
    private final TimingWheel wheel = new TimingWheel(10, 10, 0, queue, new AtomicInteger(), clock);

    private TimerTaskEntry entry(long expirationMs) {
        return new TimerTaskEntry(new TimerTask(expirationMs, () -> {}), expirationMs);
    }

    //due entry can not be add to the wheel
    @Test
    void alreadyDueEntryIsRejectedSoTheCallerCanRunIt() {
        assertFalse(wheel.add(entry(0)));
        assertFalse(wheel.add(entry(-5)));
    }
    //canceled entry can not be added to wheel
    @Test
    void cancelledEntryIsRejected() {
        TimerTaskEntry entry = entry(50);
        entry.task.cancel();
        assertFalse(wheel.add(entry));
        assertEquals(0, queue.size());
    }

    //expiration is rounded up to next tick boundary
    @Test
    void expirationIsRoundedUpToTheNextTickBoundaryNeverDown() {
        assertTrue(wheel.add(entry(18)));
        TimerTaskList bucket = queue.peek();
        assertEquals(20, bucket.getExpiration());

        // exactly on a boundary stays on that boundary
        DelayQueue<TimerTaskList> q2 = new DelayQueue<>();
        TimingWheel w2 = new TimingWheel(10, 10, 0, q2, new AtomicInteger(), clock);
        assertTrue(w2.add(entry(30)));
        assertEquals(30, q2.peek().getExpiration());
    }

    //entry in the same tickms range in the same bucket(list)
    @Test
    void entriesWithTheSameDeadlineShareOneBucketAdnOneQueueSlot() {
        assertTrue(wheel.add(entry(41)));
        assertTrue(wheel.add(entry(48)));
        assertEquals(1,  queue.size());
    }

    @Test
    void levelONeHoldsUpToWheelSizeMinusOneTicksAndNotCreatesAnOverflowWheel() {
        assertTrue(wheel.add(entry(90)));
        assertEquals(1, wheel.levels());
    }

    @Test
    void entryBeyongLevelOneGoesToCoarseBucketThatStartsBeforeItsDeadline() {
        assertTrue(wheel.add(entry(91)));
        assertEquals(2, wheel.levels());
        //level2 tick is 90 ms, floor(91/90) * 90 = 90, the ducket is due at 90. before the deadline
        assertEquals(90, queue.peek().getExpiration());
        assertEquals(1, queue.size());
    }

    @Test
    void faDeadlineCreatesAsManyLevelAsNeeded() {
        assertTrue(wheel.add(entry(50_000)));
        assertEquals(4, wheel.levels());
    }

    @Test
    void absurdDeadlineIsRejectedInsteadOfOverflowing() {
        assertThrows(IllegalArgumentException.class, ()->wheel.add(entry(Long.MAX_VALUE)));
    }


    @Test
    void advancingTheClockMovesCoarserLevelsToo() {
        assertTrue(wheel.add(entry(1_000)));
        assertEquals(3, wheel.levels());
        assertEquals(1, queue.size());          // 第 3 层有一个起点为 900 的桶

        wheel.advanceClock(1_000);              // 第 2 层时间应该变成 990

        assertTrue(wheel.add(entry(1_500)));
        assertEquals(2, queue.size());          // 第 2 层新建了一个桶
        assertTrue(java.util.Arrays.stream(queue.toArray())
                .anyMatch(b -> ((TimerTaskList) b).getExpiration() == 1_440));
    }
    //dvance whole ticks
    @Test
    void wheelTimeAdvanceInWholeTicksAndNeverGoesBackwords() {
        wheel.advanceClock(37);
        assertEquals(30, wheel.getCurrentTimeMs());
        wheel.advanceClock(39);
        assertEquals(30, wheel.getCurrentTimeMs());
        wheel.advanceClock(5);
        assertEquals(30, wheel.getCurrentTimeMs());

        wheel.advanceClock(40);
        assertEquals(40, wheel.getCurrentTimeMs());
    }

    //constructor parameter validation
    @Test
    void invalidConstructionArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TimingWheel(0, 10, 0, queue, new AtomicInteger(), clock));
        assertThrows(IllegalArgumentException.class, () -> new TimingWheel(10, 0, 0, queue, new AtomicInteger(), clock));
    }




}
