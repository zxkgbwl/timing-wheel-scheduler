package io.timewheel.bench;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The claim to test: inserting and cancelling a timer is O(1) in a timing wheel but O(log N) in a
 * heap, so the gap should WIDEN as the number of pending timers grows.
 *
 * <p>Each trial first fills the timer with {@code pending} timers 1 to 2 hours out (none fires).
 * The measured operation is then "schedule one more and cancel it", which keeps the size constant
 * across iterations. Delays are random so heap inserts do not hit a lucky best case.
 *
 * <p>This times the CALLING thread only. Netty defers part of the work to its worker thread, and
 * the lazily-cancelling DelayQueue defers its cost to memory; {@link LoadTest} reports the total
 * process CPU and retained memory that these numbers cannot show.
 *
 * <pre>
 *   java -jar target/benchmarks.jar SchedulingCostBenchmark                    # 1 thread
 *   java -jar target/benchmarks.jar SchedulingCostBenchmark -t 4               # 4 threads: contention
 *   java -jar target/benchmarks.jar SchedulingCostBenchmark -p impl=wheel,stpe -p pending=1000000
 * </pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"-Xms4g", "-Xmx4g"})
public class SchedulingCostBenchmark {

    private static final long HOUR_MS = 3_600_000L;
    private static final Runnable NOOP = () -> { };

    @Param({"wheel", "delayqueue", "stpe", "netty"})
    public String impl;

    @Param({"1000", "100000", "1000000"})
    public int pending;

    private TimerImpl timer;

    @Setup(Level.Trial)
    public void fill() {
        timer = Impls.create(impl, Settings.defaults());
        Random random = new Random(1);
        for (int i = 0; i < pending; i++) {
            timer.schedule(HOUR_MS + random.nextInt((int) HOUR_MS), NOOP);
        }
    }

    @TearDown(Level.Trial)
    public void close() throws Exception {
        timer.close();
    }

    @Benchmark
    public void scheduleAndCancel() {
        long delay = HOUR_MS + ThreadLocalRandom.current().nextInt((int) HOUR_MS);
        timer.cancel(timer.schedule(delay, NOOP));
    }
}
