package io.timewheel.bench;

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import java.util.concurrent.TimeUnit;

/**
 * Netty's {@link HashedWheelTimer}: a single-level wheel (long delays use a per-timeout
 * "remaining rounds" counter), 1 ms tick and 512 buckets to match this project's wheel. Tasks run
 * inline on its worker thread. {@code newTimeout} and {@code cancel} only enqueue; the worker
 * moves them into the wheel on its next tick, so the caller-side cost is small and the rest is
 * paid on the worker thread (visible in the CPU numbers, not in a caller-side JMH timing).
 */
final class NettyTimerImpl implements TimerImpl {

    private final HashedWheelTimer timer = new HashedWheelTimer(runnable -> {
        Thread thread = new Thread(runnable, "bench-netty-wheel");
        thread.setDaemon(true);
        return thread;
    }, 1, TimeUnit.MILLISECONDS, 512);

    @Override
    public String name() {
        return "netty";
    }

    @Override
    public Object schedule(long delayMs, Runnable task) {
        return timer.newTimeout(timeout -> task.run(), delayMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void cancel(Object handle) {
        ((Timeout) handle).cancel();
    }

    @Override
    public void close() {
        timer.stop();
    }
}
