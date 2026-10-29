package io.timewheel.bench;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * End-to-end load and memory test for ONE timer implementation per JVM (so JIT and GC state never
 * leaks from one candidate into the next).
 *
 * <pre>
 *   java -Xms4g -Xmx4g -cp target/benchmarks.jar io.timewheel.bench.LoadTest --mode=load   --impl=wheel --rate=200000 --seconds=20
 *   java -Xms4g -Xmx4g -cp target/benchmarks.jar io.timewheel.bench.LoadTest --mode=memory --impl=wheel --tasks=1000000 --cancelFraction=0.9
 * </pre>
 *
 * <p><b>load</b>: producers schedule {@code rate} tasks per second for {@code seconds}, each with a
 * random delay in [delayMin, delayMax] ms. Every task records how late it ran. Reports achieved
 * schedule rate, lateness percentiles, early firings, lost tasks, total process CPU per task, GC
 * and peak heap.
 *
 * <p><b>memory</b>: schedules {@code tasks} timers 10 to 20 minutes out (none fires), optionally
 * cancels a fraction, forces GC and reports the heap that is still retained per live task.
 */
public final class LoadTest {

    /**
     * The clock behind the wheel is millisecond-granular (it floors), so a task may run up to 1 ms
     * ahead of the exact nanosecond deadline. Anything earlier than this would be a real bug.
     */
    private static final long EARLY_TOLERANCE_NANOS = 1_000_000L;

    private static final class Stats {
        final LagHistogram lagMicros = new LagHistogram();
        final LongAdder scheduled = new LongAdder();
        final LongAdder fired = new LongAdder();
        final LongAdder early = new LongAdder();
        final LongAdder earlyBeyondTolerance = new LongAdder();
    }

    /** The task body: one small object per timer, the same for every candidate. */
    private static final class Job implements Runnable {
        final long deadlineNanos;
        final Stats stats;

        Job(long deadlineNanos, Stats stats) {
            this.deadlineNanos = deadlineNanos;
            this.stats = stats;
        }

        @Override
        public void run() {
            if (stats == null) {
                return;
            }
            long lag = System.nanoTime() - deadlineNanos;
            if (lag < 0) {
                stats.early.increment();
            }
            if (lag < -EARLY_TOLERANCE_NANOS) {
                stats.earlyBeyondTolerance.increment();
                lag = -EARLY_TOLERANCE_NANOS;           // clamp what the histogram cannot hold
            }
            // The histogram stores non-negative numbers, so every sample is shifted by the
            // tolerance; the report shifts it back, which keeps early firings visible as negatives.
            stats.lagMicros.record((lag + EARLY_TOLERANCE_NANOS) / 1_000);
            stats.fired.increment();
        }
    }

    public static void main(String[] args) throws Exception {
        Settings settings = Settings.parse(args);
        String mode = settings.text("mode", "load");
        String impl = settings.text("impl", "");
        if (impl.isEmpty()) {
            System.out.println("usage: LoadTest --mode=load|memory --impl=<" + Impls.NAMES + "> [--key=value ...]");
            return;
        }
        if (mode.equals("load")) {
            runLoad(settings, impl);
        } else if (mode.equals("memory")) {
            runMemory(settings, impl);
        } else {
            throw new IllegalArgumentException("unknown mode " + mode);
        }
    }

    // ------------------------------------------------------------------------------ load

    private static void runLoad(Settings s, String impl) throws Exception {
        final String label = s.text("label", impl);
        final int rate = s.integer("rate", 100_000);
        final int seconds = s.integer("seconds", 10);
        final int producers = s.integer("producers", 4);
        final int delayMin = s.integer("delayMin", 500);
        final int delayMax = s.integer("delayMax", 3_000);
        if (delayMax < delayMin) {
            throw new IllegalArgumentException("delayMax must be >= delayMin");
        }

        TimerImpl timer = Impls.create(impl, s);
        Stats stats = new Stats();
        settle();
        long baseHeap = usedHeap();
        long cpuBefore = processCpuNanos();
        long gcCountBefore = gcCount();
        long gcMillisBefore = gcMillis();

        long[] peaks = new long[2]; // peak heap, peak pending
        boolean[] sampling = {true};
        Thread sampler = new Thread(() -> {
            while (sampling[0]) {
                peaks[0] = Math.max(peaks[0], usedHeap());
                if (!impl.equals("noop")) {
                    peaks[1] = Math.max(peaks[1], stats.scheduled.sum() - stats.fired.sum());
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
            }
        }, "bench-sampler");
        sampler.setDaemon(true);
        sampler.start();

        long wallStart = System.nanoTime();
        Thread[] threads = new Thread[producers];
        for (int p = 0; p < producers; p++) {
            final long perProducerRate = Math.max(1, rate / producers);
            final long seed = 42 + p;
            threads[p] = new Thread(() -> produce(timer, stats, perProducerRate, seconds, delayMin, delayMax, seed),
                    "bench-producer-" + p);
            threads[p].start();
        }
        for (Thread t : threads) {
            t.join();
        }
        long produceNanos = System.nanoTime() - wallStart;

        if (!impl.equals("noop")) {
            long giveUp = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMax + 5_000L);
            while (stats.fired.sum() < stats.scheduled.sum() && System.nanoTime() < giveUp) {
                Thread.sleep(50);
            }
        }
        long cpuNanos = processCpuNanos() - cpuBefore;
        long gcCount = gcCount() - gcCountBefore;
        long gcMillis = gcMillis() - gcMillisBefore;
        sampling[0] = false;
        timer.close();

        long scheduled = stats.scheduled.sum();
        long fired = stats.fired.sum();
        double achieved = scheduled / (produceNanos / 1e9);
        double cpuMicrosPerTask = scheduled == 0 || cpuNanos < 0 ? -1 : cpuNanos / 1_000.0 / scheduled;
        LagHistogram lag = stats.lagMicros;

        System.out.printf("impl=%s  target=%,d/s  producers=%d  delay=%d..%d ms  duration=%d s%n",
                label, rate, producers, delayMin, delayMax, seconds);
        System.out.printf("  scheduled : %,d  (%,.0f/s achieved)%n", scheduled, achieved);
        System.out.printf("  fired     : %,d   lost: %,d%n", fired, impl.equals("noop") ? 0 : scheduled - fired);
        System.out.printf("  early     : %,d  (more than 1 ms early: %,d)%n",
                stats.early.sum(), stats.earlyBeyondTolerance.sum());
        if (lag.count() == 0) {
            System.out.println("  lateness  : n/a (no task ran)");
        } else {
            System.out.printf("  lateness  : p50=%.2f  p90=%.2f  p99=%.2f  p99.9=%.2f  max=%.2f ms (negative = early)%n",
                    ms(lag.percentile(50)), ms(lag.percentile(90)), ms(lag.percentile(99)),
                    ms(lag.percentile(99.9)), ms(lag.max()));
        }
        System.out.printf("  cpu       : %.2f s process total, %.2f us per scheduled task%n",
                cpuNanos / 1e9, cpuMicrosPerTask);
        System.out.printf("  gc        : %d collections, %d ms%n", gcCount, gcMillis);
        System.out.printf("  heap      : peak %,d MB (start %,d MB), peak pending %,d%n",
                peaks[0] >> 20, baseHeap >> 20, peaks[1]);
        System.out.println("CSV_HEADER,impl,rate,producers,scheduled,achievedPerSec,fired,lost,early,earlyOver1ms,"
                + "p50ms,p90ms,p99ms,p999ms,maxms,cpuSec,cpuUsPerTask,gcCount,gcMs,peakHeapMB,peakPending");
        boolean noSamples = lag.count() == 0;
        System.out.printf("CSV,%s,%d,%d,%d,%.0f,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d%n",
                label, rate, producers, scheduled, achieved, fired, impl.equals("noop") ? 0 : scheduled - fired,
                stats.early.sum(), stats.earlyBeyondTolerance.sum(),
                noSamples ? Double.NaN : ms(lag.percentile(50)), noSamples ? Double.NaN : ms(lag.percentile(90)),
                noSamples ? Double.NaN : ms(lag.percentile(99)), noSamples ? Double.NaN : ms(lag.percentile(99.9)),
                noSamples ? Double.NaN : ms(lag.max()), cpuNanos / 1e9, cpuMicrosPerTask,
                gcCount, gcMillis, peaks[0] >> 20, peaks[1]);
    }

    /** Paces one producer at {@code perSecond} tasks/s; if the timer cannot keep up the achieved rate drops. */
    private static void produce(TimerImpl timer, Stats stats, long perSecond, int seconds,
                                int delayMin, int delayMax, long seed) {
        Random random = new Random(seed);
        int range = delayMax - delayMin + 1;
        long start = System.nanoTime();
        long end = start + TimeUnit.SECONDS.toNanos(seconds);
        long produced = 0;
        while (true) {
            long now = System.nanoTime();
            if (now >= end) {
                return;
            }
            long due = (long) ((now - start) / 1e9 * perSecond) - produced;
            if (due <= 0) {
                LockSupport.parkNanos(50_000);
                continue;
            }
            long batch = Math.min(due, 5_000); // do not try to catch up in one huge burst
            for (long i = 0; i < batch; i++) {
                long delay = delayMin + random.nextInt(range);
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay);
                timer.schedule(delay, new Job(deadline, stats));
                stats.scheduled.increment();
            }
            produced += batch;
        }
    }

    // ---------------------------------------------------------------------------- memory

    /**
     * Measures memory by DIFFERENCES, never against an absolute baseline: the used-heap reading
     * right after a GC carries a constant offset that depends on the collector and heap layout
     * (a "tasks=1" run can read several MB below its own baseline), which swamps small structures.
     * An offset cancels in a difference, so:
     * <ul>
     *   <li>marginal bytes per task = growth between checkpoints 1 and 4 (after 1/4 and all of the tasks);</li>
     *   <li>bytes still held per cancelled task = what one task costs minus what cancelling it gave back.</li>
     * </ul>
     */
    private static void runMemory(Settings s, String impl) throws Exception {
        final String label = s.text("label", impl);
        final int chunks = 4;
        final int perChunk = Math.max(1, s.integer("tasks", 1_000_000) / chunks);
        final int n = perChunk * chunks;
        final double cancelFraction = s.decimal("cancelFraction", 0.0);
        if (cancelFraction < 0 || cancelFraction > 1) {
            throw new IllegalArgumentException("cancelFraction must be in [0, 1]");
        }

        TimerImpl timer = Impls.create(impl, s);
        Object[] handles = new Object[n];      // allocated up front, so it is not part of any difference
        Random random = new Random(7);

        // warm-up: touch every code path and grow internal structures once, then clean up
        for (int i = 0; i < 2_000; i++) {
            timer.cancel(timer.schedule(600_000L + random.nextInt(600_000), new Job(0, null)));
        }
        Thread.sleep(2_000);

        long[] heap = new long[chunks + 1];
        long[] server = new long[chunks + 1];
        heap[0] = usedHeapAfterSettle();
        server[0] = timer.serverMemoryBytes();
        for (int c = 0; c < chunks; c++) {
            for (int i = c * perChunk; i < (c + 1) * perChunk; i++) {
                long delay = 600_000L + random.nextInt(600_000);
                handles[i] = timer.schedule(delay, new Job(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay), null));
            }
            heap[c + 1] = usedHeapAfterSettle();
            server[c + 1] = timer.serverMemoryBytes();
        }
        double perTask = (heap[chunks] - heap[1]) / (double) (perChunk * (chunks - 1));
        double serverPerTask = server[0] < 0 ? -1 : (server[chunks] - server[1]) / (double) (perChunk * (chunks - 1));

        int cancelled = (int) (n * cancelFraction);
        long heldAfterCancel = heap[chunks];
        long serverAfterCancel = server[chunks];
        double stillHeld = 0;
        double serverStillHeld = server[0] < 0 ? -1 : 0;
        if (cancelled > 0) {
            for (int i = 0; i < cancelled; i++) {
                timer.cancel(handles[i]);
                handles[i] = null;
            }
            Thread.sleep(2_000); // Netty and Redis drop cancelled entries asynchronously
            heldAfterCancel = usedHeapAfterSettle();
            double freedPerCancelled = (heap[chunks] - heldAfterCancel) / (double) cancelled;
            stillHeld = Math.max(0, perTask - freedPerCancelled);
            if (server[0] >= 0) {
                serverAfterCancel = timer.serverMemoryBytes();
                double serverFreed = (server[chunks] - serverAfterCancel) / (double) cancelled;
                serverStillHeld = Math.max(0, serverPerTask - serverFreed);
            }
        }

        System.out.printf("impl=%s  tasks=%,d  cancelled=%,d (%.0f%%)%n", label, n, cancelled, cancelFraction * 100);
        System.out.printf("  client heap per scheduled task          : %,.0f bytes%n", perTask);
        if (cancelled > 0) {
            System.out.printf("  still held per CANCELLED task           : %,.0f bytes (%.0f%% of a live task)%n",
                    stillHeld, perTask <= 0 ? 0 : 100 * stillHeld / perTask);
            System.out.printf("  heap after cancelling                   : %,.1f MB (was %,.1f MB)%n",
                    heldAfterCancel / 1048576.0, heap[chunks] / 1048576.0);
        }
        if (server[0] >= 0) {
            System.out.printf("  Redis server memory per scheduled task  : %,.0f bytes%n", serverPerTask);
            if (cancelled > 0) {
                System.out.printf("  Redis still held per CANCELLED task     : %,.0f bytes%n", serverStillHeld);
            }
        }
        System.out.println("CSV_HEADER,impl,tasks,cancelled,heapBytesPerTask,heapStillHeldPerCancelled,serverBytesPerTask,serverStillHeldPerCancelled");
        System.out.printf("CSV,%s,%d,%d,%.0f,%.0f,%.0f,%.0f%n", label, n, cancelled, perTask, stillHeld,
                serverPerTask, serverStillHeld);
        timer.close();
        // `handles` must stay reachable until the measurements are done. Only a fence guarantees
        // that: a plain use such as `handles.length == -1` is provably false and the JIT removes it.
        java.lang.ref.Reference.reachabilityFence(handles);
    }

    // -------------------------------------------------------------------------- helpers

    /** Histogram value (shifted by the tolerance) back to signed milliseconds of lateness. */
    private static double ms(long shiftedMicros) {
        return (shiftedMicros - EARLY_TOLERANCE_NANOS / 1_000) / 1_000.0;
    }

    private static void settle() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(150);
        }
    }

    private static long usedHeapAfterSettle() throws InterruptedException {
        settle();
        return usedHeap();
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static long processCpuNanos() {
        java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        return os instanceof OperatingSystemMXBean ? ((OperatingSystemMXBean) os).getProcessCpuTime() : -1;
    }

    private static long gcCount() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0, gc.getCollectionCount());
        }
        return total;
    }

    private static long gcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0, gc.getCollectionTime());
        }
        return total;
    }
}
