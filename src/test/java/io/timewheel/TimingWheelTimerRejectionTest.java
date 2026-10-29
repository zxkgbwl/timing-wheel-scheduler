package io.timewheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** What happens to a due task that the executor refuses. */
class TimingWheelTimerRejectionTest {

    private final ManualClock clock = new ManualClock(0);

    /** Refuses the first {@code rejections} submissions, then runs tasks inline. */
    private static final class FlakyExecutor implements TaskExecutor {
        private final AtomicInteger remainingRejections;
        final AtomicInteger submissions = new AtomicInteger();

        FlakyExecutor(int rejections) {
            this.remainingRejections = new AtomicInteger(rejections);
        }

        @Override
        public boolean submit(TimerTask task) {
            submissions.incrementAndGet();
            if (remainingRejections.getAndDecrement() > 0) {
                return false;
            }
            task.runIfInPending();
            return true;
        }

        @Override
        public void close() {
        }
    }

    private void tickTo(TimingWheelTimer timer, long timeMs) throws InterruptedException {
        clock.set(timeMs);
        timer.advanceClock(0);
    }

    @Test
    void theHandlerReceivesTheRefusedTaskWhichHasNotRun() throws Exception {
        TimingWheelTimer timer = new TimingWheelTimer(1, 64, new FlakyExecutor(1), clock);
        List<TimerTask> refused = new ArrayList<>();
        timer.setRejectionHandler(refused::add);
        AtomicInteger runs = new AtomicInteger();
        TimerTask task = timer.schedule(5, runs::incrementAndGet);

        tickTo(timer, 5);
        assertEquals(List.of(task), refused);
        assertEquals(0, runs.get());
        assertFalse(task.isFired(), "a refused task must stay pending so it can be offered again");
        assertEquals(0, timer.size(), "it has left the wheel");
    }

    @Test
    void aHandlerCanScheduleTheSameWorkAgain() throws Exception {
        FlakyExecutor executor = new FlakyExecutor(1);
        TimingWheelTimer timer = new TimingWheelTimer(1, 64, executor, clock);
        timer.setRejectionHandler(task -> timer.schedule(10, task.action()));
        AtomicInteger runs = new AtomicInteger();
        timer.schedule(5, runs::incrementAndGet);

        tickTo(timer, 5);
        assertEquals(0, runs.get());
        tickTo(timer, 14);
        assertEquals(0, runs.get());
        tickTo(timer, 15);
        assertEquals(1, runs.get());
        assertEquals(2, executor.submissions.get());
    }

    @Test
    void withoutAHandlerARefusedTaskIsOnlyLogged() throws Exception {
        TimingWheelTimer timer = new TimingWheelTimer(1, 64, new FlakyExecutor(1), clock);
        AtomicInteger runs = new AtomicInteger();
        timer.schedule(5, runs::incrementAndGet);
        tickTo(timer, 100);
        assertEquals(0, runs.get());
    }

    @Test
    void anExecutorThatThrowsCountsAsARefusal() throws Exception {
        TaskExecutor exploding = new TaskExecutor() {
            @Override
            public boolean submit(TimerTask task) {
                throw new IllegalStateException("pool is broken");
            }

            @Override
            public void close() {
            }
        };
        TimingWheelTimer timer = new TimingWheelTimer(1, 64, exploding, clock);
        List<TimerTask> refused = new ArrayList<>();
        timer.setRejectionHandler(refused::add);
        timer.schedule(5, () -> { });
        tickTo(timer, 5);
        assertEquals(1, refused.size());
    }

    @Test
    void aHandlerThatThrowsDoesNotStopTheTimer() throws Exception {
        FlakyExecutor executor = new FlakyExecutor(1);
        TimingWheelTimer timer = new TimingWheelTimer(1, 64, executor, clock);
        timer.setRejectionHandler(task -> {
            throw new IllegalStateException("handler bug");
        });
        AtomicInteger runs = new AtomicInteger();
        timer.schedule(5, () -> { });
        timer.schedule(20, runs::incrementAndGet);

        tickTo(timer, 5);
        tickTo(timer, 20);
        assertEquals(1, runs.get());
        assertTrue(timer.size() == 0);
    }
}
