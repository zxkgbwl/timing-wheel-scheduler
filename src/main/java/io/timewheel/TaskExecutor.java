package io.timewheel;

public interface TaskExecutor extends AutoCloseable {

    boolean submit(TimerTask task);

    @Override
    void close();
}
