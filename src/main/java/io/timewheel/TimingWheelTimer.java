package io.timewheel;


import javax.print.attribute.standard.NumberOfInterveningJobs;
import java.util.Objects;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class TimingWheelTimer implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(TimingWheelTimer.class.getName());
    private static final long DRIVER_POOL_MS = 100L;

    private final TaskExecutor executor;
    private final Clock clock;
    private final DelayQueue<TimerTaskList> delayQueue = new DelayQueue<>();
    private final AtomicInteger taskCounter = new AtomicInteger();
    private final TimingWheel wheel;
    private final ReentrantReadWriteLock rwlock = new ReentrantReadWriteLock();
    private final Lock readLock = rwlock.readLock();
    private final Lock writeLock = rwlock.writeLock();

    private Thread driver;
    // used as a indicator for other thread
    private volatile boolean running;

    //run due tasks on a caller-owned executor, timer never shuts it down
    public TimingWheelTimer(long tickMs, int wheelSize, Executor executor) {
        this(tickMs, wheelSize, new ThreadPoolTaskExecutor(executor), Clock.SYSTEM);
    }

    public TimingWheelTimer(long tickMs, int wheelSize, Executor executor, Clock clock) {
        this(tickMs, wheelSize, new ThreadPoolTaskExecutor(executor), clock);
    }

    //run due tasks on executor, the caller owns it: close  the timer first,then close it.
    public TimingWheelTimer(long tickMs, int wheelSize, TaskExecutor executor) {
        this(tickMs, wheelSize, executor, Clock.SYSTEM);
    }

    public TimingWheelTimer(long tickMs, int wheelSize, TaskExecutor executor, Clock clock) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.wheel = new TimingWheel(tickMs, wheelSize, clock.nowMs(), delayQueue, taskCounter, clock);
    }

    //Schedule to run action no ealier than delayMs form now/
    public TimerTask schedule(long delayMs, Runnable action) {
        Objects.requireNonNull(action, "action");
        if (delayMs < 0) {
            throw new IllegalArgumentException("delayMs must be > 0");
        }
        long expirationMs;
        try {
            expirationMs = Math.addExact(clock.nowMs(), delayMs);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("delayMs too large: " + delayMs, e);
        }
        TimerTask task = new TimerTask(delayMs, action);
        TimerTaskEntry entry = new TimerTaskEntry(task, expirationMs);
        readLock.lock();
        try {
            addEntryOrFire(entry);
        }finally {
            readLock.unlock();
        }
        return task;
    }
    //number of task waiting in buckets
    public int size() {

        return taskCounter.get();
    }

    public boolean advanceClock(long timeoutMs) throws InterruptedException {
        TimerTaskList bucket = delayQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        writeLock.lock();
        try {
            boolean flushed = false;
            while (bucket != null) {
                wheel.advanceClock(bucket.getExpiration());
                bucket.flush(this:: addEntryOrFire);
                flushed = true;
                bucket = delayQueue.poll();
            }

            wheel.advanceClock(clock.nowMs());
            return flushed;

        }finally {
            writeLock.unlock();
        }
    }

    public synchronized  void start() {
        if (driver != null ) {
            throw new IllegalStateException("Already started");
        }
        running = true;
        driver = new Thread(this::driverLoop, "timewheel-driver");
        driver.setDaemon(true);
        driver.start();
    }

    @Override
    public synchronized void close() {
        running = false;
        if (driver != null) {
            driver.interrupt();
            try {
                driver.join(1000);
            }catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            driver = null;
        }
    }

    private void driverLoop() {
        while (running) {
            try {
                advanceClock(DRIVER_POOL_MS);
            }catch (InterruptedException e) {
                return;
            }catch (Throwable t) {
                LOG.log(System.Logger.Level.ERROR, "timer driver iteration failed", t);
            }
        }
    }


    private void addEntryOrFire(TimerTaskEntry entry) {
        if (!wheel.add(entry) && !entry.cancelled()) {
            fire(entry.task);
        }
    }

    private void fire(TimerTask task) {
        boolean accepted = false;
        try {
            accepted = executor.submit(task);
        }catch(RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "executor failed to accept a due task", e);
            return;
        }
        if (!accepted) {
            LOG.log(System.Logger.Level.ERROR, "executor rejected a due task");
        }
    }






}