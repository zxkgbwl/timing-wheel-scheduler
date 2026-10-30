package io.timewheel.bench;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** Cost of the building blocks of one schedule+cancel: clock read, read lock, monitors, atomics. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1)
public class ComponentCostBenchmark {

    private final Lock readLock = new ReentrantReadWriteLock().readLock();
    private final Object outer = new Object();
    private final Object inner = new Object();
    private final AtomicInteger counter = new AtomicInteger();
    private final AtomicLong expiration = new AtomicLong();
    private long toggle;

    @Benchmark
    public long nanoTime() {
        return System.nanoTime();
    }

    @Benchmark
    public void readLockPair() {
        readLock.lock();
        readLock.unlock();
    }

    @Benchmark
    public int nestedMonitorsOnePair() {
        synchronized (outer) {
            synchronized (inner) {
                return counter.get();
            }
        }
    }

    @Benchmark
    public int atomicIncrementAndDecrement() {
        counter.incrementAndGet();
        return counter.decrementAndGet();
    }

    @Benchmark
    public long atomicGetAndSet() {
        return expiration.getAndSet(toggle++);
    }
}