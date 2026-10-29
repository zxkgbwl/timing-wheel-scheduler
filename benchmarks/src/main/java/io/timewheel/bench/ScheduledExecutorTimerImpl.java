package io.timewheel.bench;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The JDK's own timer: {@link ScheduledThreadPoolExecutor}, one worker thread so tasks run inline
 * like the other candidates. {@code removeOnCancelPolicy} is on, the JDK's best behaviour (an
 * O(log N) heap removal); with the default a cancelled task stays queued until its deadline.
 */
final class ScheduledExecutorTimerImpl implements TimerImpl {

    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "bench-stpe-worker");
        thread.setDaemon(true);
        return thread;
    });

    ScheduledExecutorTimerImpl() {
        executor.setRemoveOnCancelPolicy(true);
    }

    @Override
    public String name() {
        return "stpe";
    }

    @Override
    public Object schedule(long delayMs, Runnable task) {
        return executor.schedule(task, delayMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void cancel(Object handle) {
        ((ScheduledFuture<?>) handle).cancel(false);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
