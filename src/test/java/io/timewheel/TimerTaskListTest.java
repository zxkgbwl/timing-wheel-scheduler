package io.timewheel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TimerTaskListTest {
    private final AtomicInteger counter = new AtomicInteger();
    private final ManualClock clock = new ManualClock(0);

    private TimerTaskList newList() {
        return new TimerTaskList(counter, clock);
    }

    private TimerTaskEntry newEntry(long expirationMs) {
        return new TimerTaskEntry(new TimerTask(expirationMs, () -> {}), expirationMs);
    }

    //remove all the entry from bucket and reset the expiration time.
    @Test
    void flushReturnsEntriesInInsertionOrderAndEmptiesTheList() {
        TimerTaskList list = newList();
        TimerTaskEntry a = newEntry(1);
        TimerTaskEntry b = newEntry(2);
        TimerTaskEntry c = newEntry(3);

        list.add(a);
        list.add(b);
        list.add(c);

        assertEquals(3, counter.get());

        List<TimerTaskEntry> flushed = new ArrayList<>();
        list.flush(flushed::add);

        assertEquals(List.of(a,b,c), flushed);
        assertEquals(0, counter.get());
        assertEquals(-1l, list.getExpiration());

    }

    //remove is indempotent
    @Test
    void remvoeIsIdempotent() {
        TimerTaskList list = newList();
        TimerTaskEntry a = newEntry(1);
        list.add(a);
        a.remove();
        a.remove();
        list.remove(a);
        assertEquals(0, counter.get());
    }

    //entry only could be added to one bucket, before it is added to second bucket, to be removed from the first one first
    @Test
    void addingAnEntryToAnotherListMovesIt() {
        TimerTaskList first = newList();
        TimerTaskList second = newList();
        TimerTaskEntry a = newEntry(1);
        first.add(a);
        second.add(a);

        assertEquals(1, counter.get());
        List<TimerTaskEntry> fromFirst = new ArrayList<>();
        first.flush(fromFirst::add);
        assertTrue(fromFirst.isEmpty());

        List<TimerTaskEntry> frontSecond = new ArrayList<>();
        second.flush(frontSecond::add);
        assertEquals(List.of(a), frontSecond);

    }
    //when cancel the task, entry will be unlinked first
    @Test
    void cancellingTheTaskUnlinksItsEntry() {
        TimerTaskList list = newList();
        TimerTaskEntry a = newEntry(1);
        list.add(a);
        assertTrue(a.task.cancel());
        assertEquals(0, counter.get());
        assertFalse(a.task.cancel());

    }

    //when the expiration time is changed, return true, else return false, used to decide if the bucket need to be
    //put into the queue.
    @Test
    void setExpirationReportsWhetherTheValueChanged() {
        TimerTaskList list = newList();
        assertTrue(list.setExpiration(10));
        assertFalse(list.setExpiration(10));
        assertTrue(list.setExpiration(20));
    }

    //check the delay of the bucket is measured against the injected clock, not the system clock.
    @Test
    void delayIsMeasuredAgainstTheInjectedClock() {
        TimerTaskList list = newList();
        list.setExpiration(100);
        assertEquals(100, list.getDelay(TimeUnit.MILLISECONDS));
        clock.set(60);
        assertEquals(40, list.getDelay(TimeUnit.MILLISECONDS));
        clock.set(500);
        assertEquals(0, list.getDelay(TimeUnit.MILLISECONDS));
    }



}
