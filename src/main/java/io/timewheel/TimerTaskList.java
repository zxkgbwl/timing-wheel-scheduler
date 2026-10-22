package io.timewheel;

import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

final class TimerTaskList implements Delayed {
    private final TimerTaskEntry root = new TimerTaskEntry(null, -1L);
    private final AtomicInteger taskCounter;
    private final Clock clock;
    private final AtomicLong expirationMs = new AtomicLong(-1L);

    TimerTaskList(AtomicInteger taskCounter, Clock clock) {
        this.taskCounter = taskCounter;
        this.clock = clock;
        root.next = root;
        root.prev = root;
    }

    //@return true if expiration changed
    boolean setExpiration(long expiration) {
        return expirationMs.getAndSet(expiration) != expiration;
    }

    long getExpiration() {
        return expirationMs.get();
    }

    void add(TimerTaskEntry entry) {
        boolean done = false;
        while (!done) {
            entry.remove();
            synchronized (this) {
                synchronized (entry){
                    if (entry.list == null) {
                        TimerTaskEntry tail = root.prev;
                        entry.next = root;
                        entry.prev = tail;
                        entry.list = this;
                        tail.next = entry;
                        root.prev= entry;
                        taskCounter.incrementAndGet();
                        done = true;
                    }
                }
            }
        }
    }

    void remove(TimerTaskEntry entry) {
        synchronized (this) {
            synchronized (entry) {
                if (entry.list == this) {
                    entry.prev.next = entry.next;
                    entry.next.prev = entry.prev;
                    entry.next = null;
                    entry.prev = null;
                    entry.list = null;
                    taskCounter.decrementAndGet();
                }
            }
        }
    }

    synchronized void flush(Consumer<TimerTaskEntry> handler) {
        TimerTaskEntry head = root.next;
        while (head != root) {
            remove(head);
            handler.accept(head);
            head = root.next;
        }
        expirationMs.set(-1l);
    }

    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(Math.max(getExpiration() - clock.nowMs(), 0L),  TimeUnit.MILLISECONDS);
    }

    @Override
    public int compareTo(Delayed other) {
        return Long.compare(getExpiration(), ((TimerTaskList)other).getExpiration());
    }
}
