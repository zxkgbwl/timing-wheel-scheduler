package io.timewheel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TimingWheelDemotionTest {
    private final ManualClock clock = new ManualClock(0);
    private final DelayQueue<TimerTaskList> queue=  new DelayQueue<>();
    private final AtomicInteger counter = new AtomicInteger();
    private final TimingWheel wheel = new TimingWheel(10, 10, 0, queue, counter, clock);
    private final List<TimerTaskEntry> fired = new ArrayList<>();

    private TimerTaskEntry schedule(long deadline) {
        TimerTaskEntry entry = new TimerTaskEntry(new TimerTask(deadline, ()-> {}), deadline);
        assertTrue(wheel.add(entry));
        return entry;
    }

    private void advanceTo(long timeMs) {
        clock.set(timeMs);
        TimerTaskList bucket;
        while((bucket = queue.poll()) != null) {
            wheel.advanceClock(bucket.getExpiration());
            bucket.flush(e->
            {
                if( !wheel.add(e) && !e.cancelled()) {
                    fired.add(e);
                }
            });
        }
        wheel.advanceClock(timeMs);
    }

    private static long bucketOf(TimerTaskEntry entry) {
        return entry.list == null ? -1 : entry.list.getExpiration();
    }

    @Test
    void entryWalksFromLevel3ToLevel2ToLevel1AndThenFires() {
        TimerTaskEntry e = schedule(2_000);
        assertEquals(3, wheel.levels());
        assertEquals(1_800, bucketOf(e));          // L3: floor(2000 / 900) * 900

        advanceTo(1_799);
        assertEquals(1_800, bucketOf(e));          // nothing is due yet
        advanceTo(1_800);
        assertEquals(1_980, bucketOf(e));          // demoted to L2: floor(2000 / 90) * 90

        advanceTo(1_979);
        assertEquals(1_980, bucketOf(e));
        advanceTo(1_980);
        assertEquals(2_000, bucketOf(e));          // demoted to L1: ceil(2000 / 10) * 10

        advanceTo(1_999);
        assertEquals(2_000, bucketOf(e));
        assertTrue(fired.isEmpty());

        advanceTo(2_000);
        assertEquals(List.of(e), fired);
        assertEquals(-1, bucketOf(e));
        assertEquals(0, counter.get());
    }

    @Test
    void lastStepRoundsUpToTheTickSoTheTaskIsNeverEarly() {
        TimerTaskEntry e = schedule(2_005);
        assertEquals(1_800, bucketOf(e));

        advanceTo(1_800);
        assertEquals(1_980, bucketOf(e));
        advanceTo(1_980);
        assertEquals(2_010, bucketOf(e));          // ceil(2005 / 10) * 10, not 2000

        advanceTo(2_009);
        assertTrue(fired.isEmpty());
        advanceTo(2_010);
        assertEquals(List.of(e), fired);
    }

    @Test
    void entriesSharingACoarseBucketSplitApartWhenTheyAreDemoted() {
        TimerTaskEntry near = schedule(1_850);
        TimerTaskEntry far = schedule(1_990);
        assertEquals(1_800, bucketOf(near));
        assertEquals(1_800, bucketOf(far));         // same L3 bucket

        advanceTo(1_800);
        assertEquals(1_850, bucketOf(near));       // fits L1 directly
        assertEquals(1_980, bucketOf(far));        // needs L2 first

        advanceTo(1_850);
        assertEquals(List.of(near), fired);
        advanceTo(1_980);
        assertEquals(1_990, bucketOf(far));
        advanceTo(1_990);
        assertEquals(List.of(near, far), fired);
    }

    @Test
    void cancellingAfterADemotionRemovesTheEntryFromItsNewBucket() {
        TimerTaskEntry e = schedule(2_000);
        advanceTo(1_800);
        assertEquals(1_980, bucketOf(e));
        assertEquals(1, counter.get());

        assertTrue(e.task.cancel());
        assertEquals(-1, bucketOf(e));
        assertEquals(0, counter.get());

        advanceTo(2_100);
        assertTrue(fired.isEmpty());
    }
}
