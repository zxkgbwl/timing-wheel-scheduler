package io.timewheel;

import java.util.concurrent.atomic.AtomicInteger;

//
public final class TimerTask {
    private static final System.Logger LOG = System.getLogger(TimerTask.class.getName());
    private static final int PENDING =0;
    private static final int CANCELLED =1;
    private static final int FIRED = 2;

    private final long delayMs;
    private final Runnable action;
    private final AtomicInteger state = new AtomicInteger(PENDING);
    private volatile TimerTaskEntry entry;

    TimerTask(long delayMs, Runnable action) {
        this.delayMs = delayMs;
        this.action = action;
    }

    public long delayMs() {
        return delayMs;
    }

    public boolean cancel() {
        if (!state.compareAndSet(PENDING, CANCELLED)) {
            return false;
        }
        TimerTaskEntry e = entry;
        if (e != null) {
            e.remove();
        }
        return true;
    }

    public boolean isCancelled() {
        return state.get() == CANCELLED;
    }

    public boolean isFired() {
        return state.get() == FIRED;
    }

    void setEntry(TimerTaskEntry entry) {
        this.entry = entry;
    }

    boolean tryFire() {
        return state.compareAndSet(PENDING, FIRED);
    }

    Runnable action() {
        return action;
    }

    public void runIfInPending() {
        if (tryFire()) {
            try {
                action.run();
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.ERROR, "scheduled task threw exception", t);
            }
        }
    }



}
