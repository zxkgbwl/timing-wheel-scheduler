package io.timewheel;

import java.util.concurrent.*;

public final class ThreadPoolTaskExecutor implements TaskExecutor {
    private static final long CLOSE_TIMEOUT_SECONDS = 10;

    private final Executor delegate;
    private final ExecutorService owned; //null when wrapping a called-owned execution
    private volatile boolean closed;

    public ThreadPoolTaskExecutor(Executor delegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate);
        this.owned = null;
    }

    public ThreadPoolTaskExecutor(int threads, int queueCapacity)  {
        if (threads <= 0) {
            throw new IllegalArgumentException("threads must be > 0");
        }

        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queue capacity must be > 0");
        }

        ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), new NamedThreadFactory("timewheel-worker-"),
                new ThreadPoolExecutor.AbortPolicy());
        this.delegate = pool;
        this.owned = pool;
    }

    @Override
    public boolean submit(TimerTask task) {
        if (closed) {
            return false;
        }

        try {
            delegate.execute(task:: runIfInPending);
            return true;
        }catch (RejectedExecutionException e) {
            return false;
        }
    }

    @Override
    public void close() {
        closed = true;
        if (owned == null) {
            return;
        }
        owned.shutdown();
        try {
            if (!owned.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                owned.shutdownNow();
            }
        }catch (InterruptedException e) {
            owned.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }


}
