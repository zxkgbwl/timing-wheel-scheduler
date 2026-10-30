package io.timewheel.bench;

import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

/**
 * The textbook baseline: a {@link DelayQueue} (a binary heap, O(log N) insert) drained by one
 * consumer thread that runs tasks inline.
 *
 * <p>Cancellation is LAZY (a flag, checked when the entry reaches the head): {@code
 * DelayQueue.remove(Object)} is O(N), which nobody can afford at this size. The price is that a
 * cancelled task keeps occupying memory until its original deadline; the memory probe shows it.
 */
final class DelayQueueTimerImpl implements TimerImpl {

    private static final class Entry implements Delayed {
        final long deadlineNanos;
        final Runnable task;
        volatile boolean cancelled;

        Entry(long deadlineNanos, Runnable task) {
            this.deadlineNanos = deadlineNanos;
            this.task = task;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(deadlineNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(deadlineNanos, ((Entry) other).deadlineNanos);
        }
    }

    private final DelayQueue<Entry> queue = new DelayQueue<>();
    private final Thread consumer;
    private volatile boolean running = true;

    DelayQueueTimerImpl() {
        consumer = new Thread(() -> {
            while (running) {
                try {
                    Entry entry = queue.take();
                    if (!entry.cancelled) {
                        entry.task.run();
                    }
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "bench-delayqueue-consumer");
        consumer.setDaemon(true);
        consumer.start();
    }

    @Override
    public String name() {
        return "delayqueue";
    }

    @Override
    public Object schedule(long delayMs, Runnable task) {
        Entry entry = new Entry(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs), task);
        queue.put(entry);
        return entry;
    }

    @Override
    public void cancel(Object handle) {
        ((Entry) handle).cancelled = true;
    }

    @Override
    public void close() throws InterruptedException {
        running = false;
        consumer.interrupt();
        consumer.join(2_000);
    }
}
