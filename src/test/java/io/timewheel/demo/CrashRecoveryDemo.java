package io.timewheel.demo;

import io.timewheel.DurableScheduler;
import io.timewheel.RocksDbTaskStore;
import io.timewheel.ThreadPoolTaskExecutor;
import io.timewheel.TimingWheelTimer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Manual crash-recovery demo.
 *
 * <pre>
 * 1. Run with program argument:  schedule
 *    -> schedules demo-1..demo-5 at +5s, +10s, ... +25s and prints each one when it fires.
 * 2. After demo-1 or demo-2 has fired, KILL the process hard (IDE "Stop" button, or Task Manager
 *    "End task", or kill -9). Do not let it exit cleanly.
 * 3. Run again with program argument:  recover
 *    -> the tasks that were still pending come back: overdue ones fire immediately, the rest fire
 *       at their original wall-clock time. Tasks that already fired are NOT repeated.
 * </pre>
 * Optional second argument: the data directory (default {@code demo-data}).
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
            DurableScheduler scheduler = new DurableScheduler(timer, store, record ->
                    System.out.printf("[%tT] FIRED %s  payload=\"%s\"  was due at %tT%n",
                            System.currentTimeMillis(),
                            record.id(),
                            new String(record.payload(), StandardCharsets.UTF_8),
                            record.deadlineEpochMs()));

            int recovered = scheduler.recover();
            System.out.printf("[%tT] recovered %d pending task(s) from %s%n",
                    System.currentTimeMillis(), recovered, dir.toAbsolutePath());

            if (mode.equals("schedule")) {
                for (int i = 1; i <= 5; i++) {
                    try {
                        scheduler.schedule("demo-" + i, i * 5_000L,
                                ("payload " + i).getBytes(StandardCharsets.UTF_8));
                        System.out.printf("[%tT] scheduled demo-%d to fire in %d s%n",
                                System.currentTimeMillis(), i, i * 5);
                    } catch (IllegalArgumentException alreadyThere) {
                        System.out.println("demo-" + i + " is already scheduled (left over from an earlier run)");
                    }
                }
                System.out.println("Now kill this process hard, then run again with: recover");
            }
            Thread.sleep(40_000);
        } finally {
            executor.close();
        }
    }
}
