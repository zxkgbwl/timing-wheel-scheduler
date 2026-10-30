package io.timewheel.bench;

/** Accepts tasks and forgets them: measures the load generator itself, so its cost can be subtracted. */
final class NoopTimerImpl implements TimerImpl {

    @Override
    public String name() {
        return "noop";
    }

    @Override
    public Object schedule(long delayMs, Runnable task) {
        return task;
    }

    @Override
    public void cancel(Object handle) {
    }

    @Override
    public void close() {
    }
}
