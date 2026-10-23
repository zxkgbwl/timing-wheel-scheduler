package io.timewheel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class DisruptorTaskExecutorTest extends TaskExecutorContractTest{
    @Override
    TaskExecutor create(int workers, int capacity) {
        return new DisruptorTaskExecutor(workers, capacity);
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, ()->new DisruptorTaskExecutor(0,8));
        assertThrows(IllegalArgumentException.class, ()->new DisruptorTaskExecutor(2,6));
        assertThrows(IllegalArgumentException.class, ()->new DisruptorTaskExecutor(2,1));
    }
}
