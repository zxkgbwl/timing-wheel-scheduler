package io.timewheel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ThreadPoolTaskExecutorTest extends TaskExecutorContractTest {

    @Override
    TaskExecutor create(int workers, int capacity) {
        return new ThreadPoolTaskExecutor(workers, capacity);
    }

    //close wrapper will not close the exteral thread pool, used for share pool scenario
    @Test
    void wrappingAnExistingExecutorDoesNotShutItDown() throws Exception {
        ExecutorService external = Executors.newSingleThreadExecutor();
        try {
            ThreadPoolTaskExecutor wrapper = new ThreadPoolTaskExecutor(external);
            CountDownLatch ran = new CountDownLatch(1);
            assertTrue(wrapper.submit(new TimerTask(0, ran::countDown)));
            assertTrue(ran.await(5, TimeUnit.SECONDS));

            wrapper.close();
            assertFalse(external.isShutdown());
            assertFalse(wrapper.submit(new TimerTask(0, () -> { })));
        } finally {
            external.shutdownNow();
        }
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ThreadPoolTaskExecutor(0, 8));
        assertThrows(IllegalArgumentException.class, () -> new ThreadPoolTaskExecutor(2, 0));
    }
}
