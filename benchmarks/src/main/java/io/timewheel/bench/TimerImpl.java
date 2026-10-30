package io.timewheel.bench;

/**
 * One timer implementation under test, reduced to the two operations every candidate has:
 * schedule a task and cancel it. Each adapter keeps its own natural data structure and hands
 * back whatever handle that structure needs for cancellation.
 */
public interface TimerImpl extends AutoCloseable {

    String name();

    /** Runs {@code task} no earlier than {@code delayMs} from now. @return a handle for {@link #cancel} */
    Object schedule(long delayMs, Runnable task);

    void cancel(Object handle);

    /** Memory used by an external server on behalf of this timer (Redis), or -1 if not applicable. */
    default long serverMemoryBytes() {
        return -1;
    }

    @Override
    void close() throws Exception;
}
