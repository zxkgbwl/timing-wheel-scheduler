package io.timewheel.bench;

import io.timewheel.TaskExecutor;
import io.timewheel.TimerTask;
import io.timewheel.TimingWheelTimer;

/** This project's hierarchical timing wheel, with whichever execution layer is being measured. */
final class WheelTimerImpl implements TimerImpl {

    private final String name;
    private final TimingWheelTimer timer;
    private final TaskExecutor executor;

    WheelTimerImpl(String name, TaskExecutor executor, long tickMs, int wheelSize) {
        this.name = name;
        this.executor = executor;
        this.timer = new TimingWheelTimer(tickMs, wheelSize, executor);
        this.timer.start();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Object schedule(long delayMs, Runnable task) {
        return timer.schedule(delayMs, task);
    }

    @Override
    public void cancel(Object handle) {
        ((TimerTask) handle).cancel();
    }

    @Override
    public void close() {
        timer.close();
        executor.close();
    }
}
