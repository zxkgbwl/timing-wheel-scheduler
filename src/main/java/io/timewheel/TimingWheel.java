package io.timewheel;

import java.sql.Time;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.atomic.AtomicInteger;

final class TimingWheel {

    private final long tickMs;
    private final int wheelSize;
    private final long intervalMs;
    private final boolean roundUp;
    private final TimerTaskList[] buckets;
    private final DelayQueue<TimerTaskList> queue;
    private final AtomicInteger taskCounter;
    private final Clock clock;
    private volatile long currentTimeMs;
    private volatile TimingWheel overflowWheel;


    TimingWheel(long tickMs, int wheelSize, long startMs, DelayQueue<TimerTaskList> queue,
                AtomicInteger taskCounter, Clock clock) {
        this(tickMs, wheelSize, startMs, true, queue, taskCounter, clock);
    }

    private TimingWheel(long tickMs, int wheelSize, long startMs, boolean roundUp,
                        DelayQueue<TimerTaskList> queue, AtomicInteger taskCounter, Clock clock) {
        if (tickMs <= 0) {
            throw new IllegalArgumentException("tickMs must be > 0");
        }
        //TODO WHY <= 2
        if (wheelSize <= 2) {
            throw new IllegalArgumentException("wheelSize must be >=2");
        }

        this.tickMs = tickMs;
        this.wheelSize = wheelSize;
        this.intervalMs = Math.multiplyExact(tickMs, (long)wheelSize);
        this.roundUp = roundUp;
        this.queue = queue;
        this.taskCounter = taskCounter;
        this.clock = clock;
        this.buckets = new TimerTaskList[wheelSize];
        for (int i = 0; i < wheelSize; i++) {
            buckets[i] = new TimerTaskList(taskCounter, clock);
        }
        this.currentTimeMs = startMs - Math.floorMod(startMs, tickMs);
    }

    long getIntervalMs() {
        return intervalMs;
    }

    long getCurrentTimeMs() {
        return currentTimeMs;
    }

    int levels() {
        TimingWheel next = overflowWheel;
        return next == null ? 1 : next.levels() + 1;
    }

    boolean add(TimerTaskEntry entry) {
        if (entry.cancelled()) {
            return false;
        }
        long expiration = entry.expirationMs;
        long current = currentTimeMs;
        long currentTick = Math.floorDiv(current,tickMs);
        long deadlineTick;

        if (expiration <= current) {
            return false;
        }
        //level one 0 currentTick, 1 - 9 next tick. means task will be put into the next bucket
        if (roundUp) {
            deadlineTick = Math.floorDiv(expiration, tickMs);
            if (Math.floorMod(expiration, tickMs) != 0) {
                deadlineTick++;
            }
        }else {
            //level 1 and abover, 0-9 current tick, 10 next time.
            deadlineTick = Math.floorDiv(expiration, tickMs);
            if (deadlineTick <= currentTick) {
                throw new IllegalStateException("coarse bucket is not in the future: expiration="
                        + expiration + ", wheel time=" + current + ", tick=" + tickMs);
            }
        }

        if (deadlineTick < currentTick + wheelSize) {
            TimerTaskList bucket = buckets[(int)(deadlineTick % wheelSize)];
            bucket.add(entry);
            if (bucket.setExpiration(deadlineTick * tickMs)) {
                queue.offer(bucket);
            }
            return true;
        }
        return overflow().add(entry);

    }

    /**
     * Moves wheel time forward (never backward). Callers must first flush every bucket that is
     * due, otherwise a stale bucket could be reused for a deadline one revolution later.
     */
    //used to driver the currentTimems and currentTick. timeMs is absolute time
    void advanceClock(long timeMs) {
        if (timeMs >= currentTimeMs + tickMs) {
            currentTimeMs = timeMs- (timeMs % tickMs);
            TimingWheel next = overflowWheel;
            if (next != null) {
                next.advanceClock(currentTimeMs);
            }
        }
    }

    private TimingWheel overflow() {
        TimingWheel next = overflowWheel;
        if (next == null) {
            synchronized (this) {
                next = overflowWheel;
                if (next ==null) {
                    try {
                        long nextTick = roundUp ? Math.multiplyExact(tickMs, (long) (wheelSize - 1)) : intervalMs;
                        next = new TimingWheel(nextTick, wheelSize, currentTimeMs, false, queue, taskCounter, clock);
                    } catch (ArithmeticException e) {
                        throw new IllegalArgumentException(("delay too large for the wheel hierarchy"), e);
                    }
                    overflowWheel = next;
                }
            }
        }
        return next;
    }
}
