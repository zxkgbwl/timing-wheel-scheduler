package io.timewheel;

import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;

import java.util.concurrent.TimeUnit;

public final class DisruptorTaskExecutor implements TaskExecutor {
    private static final long CLOSE_TIMEOUT_SECONDS = 10;

    //mutable slot resued for the lifetime of the ring buffer
    private static final class TaskEvent {
        TimerTask task;
    }

    private static final EventTranslatorOneArg<TaskEvent, TimerTask> TRANSLATOR =
            (event, sequence, task) -> event.task = task;

    private final Disruptor<TaskEvent> disruptor;
    private final RingBuffer<TaskEvent> ringBuffer;
    private volatile boolean closed;

    //Blocking wait strategy: workers sleep when idle(low CPU), at some latency cost
    public DisruptorTaskExecutor(int workers, int ringBufferSize) {
        this(workers, ringBufferSize, new BlockingWaitStrategy());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public DisruptorTaskExecutor(int workers, int ringBufferSize, WaitStrategy waitStrategy) {
        if (workers <= 0) {
            throw new IllegalArgumentException("workers should be greater than 0");
        }
        if (ringBufferSize <= 2 || Integer.bitCount(ringBufferSize) != 1) {
            throw new IllegalArgumentException("ringBufferSize should be a power of 2");
        }
        this.disruptor = new Disruptor<>(TaskEvent:: new, ringBufferSize,
                new NamedThreadFactory("timewheel-disruptor-"), ProducerType.MULTI, waitStrategy);

        WorkHandler<TaskEvent>[] handlers = new WorkHandler[workers];
        for (int i = 0; i < workers; i++) {
            handlers[i] = event-> {
                TimerTask task = event.task;
                event.task = null;
                task.runIfInPending();
            };
        }
        disruptor.handleEventsWithWorkerPool(handlers);
        disruptor.start();
        this.ringBuffer = disruptor.getRingBuffer();
    }
    @Override
    public boolean submit(TimerTask task) {
        if(closed) {
            return false;
        }
        return ringBuffer.tryPublishEvent(TRANSLATOR,task);
    }

    @Override
    public void close() {
        closed = true;
        try {
            disruptor.shutdown(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);  //wait for published tasks
        }catch (TimeoutException e) {
            disruptor.halt();
        }
    }




}
