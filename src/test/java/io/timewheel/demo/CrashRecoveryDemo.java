package io.timewheel.demo;

import io.timewheel.DurableScheduler;
import io.timewheel.RetryPolicy;
import io.timewheel.RocksDbTaskStore;
import io.timewheel.TaskRecord;
import io.timewheel.ThreadPoolTaskExecutor;
import io.timewheel.TimingWheelTimer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Manual crash-recovery, retry and dead-letter demo.
 *
 * <pre>
 * 1. Run with program argument:  schedule
 *    -> schedules demo-1..demo-5 at +5s, +10s, ... +25s and prints each one when it fires.
 *       demo-3 fails twice and succeeds on its third attempt (retry with backoff).
 *       demo-4 never succeeds: after 3 attempts it lands in the dead-letter store.
 * 2. Kill the process HARD at any point (IDE "Stop" button, Task Manager "End task", kill -9).
 * 3. Run again with program argument:  recover
 *    -> pending tasks come back (overdue ones fire at once, the rest at their original time),
 *       a task that was waiting for a retry keeps its attempt count, finished tasks are NOT
 *       repeated, and dead letters from earlier runs are listed.
 * </pre>
 * Optional second argument: the data directory (default {@code demo-data}). Delete a
 * {@code demo-data} folder left over from step 4: the key layout changed.
 */
public final class CrashRecoveryDemo {

    private CrashRecoveryDemo() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "help";
        Path dir = Path.of(args.length > 1 ? args[1] : "demo-data");
        if (!mode.equals("schedule") && !mode.equals("recover")) {
            System.out.println("usage: CrashRecoveryDemo schedule|recover [dataDir]");
            return;
        }

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor(2, 1024);
        try (RocksDbTaskStore store = new RocksDbTaskStore(dir);
             TimingWheelTimer timer = new TimingWheelTimer(10, 512, executor)) {
            timer.start();
            DurableScheduler.Options options = new DurableScheduler.Options()
                    .retryPolicy(new RetryPolicy(3, 2_000, 2.0, 10_000, 0.1))
                    .deadLetterListener(dead -> log("DEAD LETTER %s after %d attempts: %s",
                            dead.id(), dead.attempts(), dead.lastError()));
            DurableScheduler scheduler = new DurableScheduler(timer, store, record -> {
                log("FIRED %s  attempt #%d  payload=\"%s\"  was due at %tT",
                        record.id(), record.attempts() + 1,
                        new String(record.payload(), StandardCharsets.UTF_8), record.deadlineEpochMs());
                if (record.id().equals("demo-3") && record.attempts() < 2) {
                    throw new IllegalStateException("flaky downstream");
                }
                if (record.id().equals("demo-4")) {
                    throw new IllegalStateException("always broken");
                }
            }, options);

            int recovered = scheduler.recover();
            log("recovered %d pending task(s) from %s", recovered, dir.toAbsolutePath());
            for (TaskRecord dead : scheduler.deadLetters()) {
                log("dead letter from an earlier run: %s (%d attempts, %s)",
                        dead.id(), dead.attempts(), dead.lastError());
            }

            if (mode.equals("schedule")) {
                for (int i = 1; i <= 5; i++) {
                    try {
                        scheduler.schedule("demo-" + i, i * 5_000L,
                                ("payload " + i).getBytes(StandardCharsets.UTF_8));
                        log("scheduled demo-%d to fire in %d s", i, i * 5);
                    } catch (IllegalArgumentException alreadyThere) {
                        log("demo-%d is already scheduled (left over from an earlier run)", i);
                    }
                }
                log("Now kill this process hard at some point, then run again with: recover");
            }
            Thread.sleep(45_000);
        } finally {
            executor.close();
        }
    }

    private static void log(String format, Object... args) {
        System.out.printf("[%tT] " + format + "%n", prepend(System.currentTimeMillis(), args));
    }

    private static Object[] prepend(Object first, Object[] rest) {
        Object[] all = new Object[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }
}
