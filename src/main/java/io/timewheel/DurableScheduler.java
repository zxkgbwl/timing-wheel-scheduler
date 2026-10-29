package io.timewheel;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

/**
 * Add crash recovery to a TimeingWheelTimer
 * ordering is what makes it safe
 * 1.schedule: write the reocrd to Taskstore first, then put it into wheel,
 * A crash in between leaves a stored task, rearm after recovery
 * 2. fire: run the handler, only remove the record after it retunred.
 * A crash in between re-run the task after recovery.
 * 3. cancel: remove the record for taskstore first, then cancel the wheel entry
 * A crash in between can not resurrect a cancelled task
 * 4. recover: reload every record and put the task whose time not due into wheel
 * anyone already due runs immediately, in deadline order ASC
 *
 * When the executor refuses a due task (its queue is full) the handler never ran, so this is
 * NOT a failed attempt: the task is re-armed shortly after without using up an attempt. This
 * installs itself as the timer's rejection handler.
 *
 * Call recover() once before schedule. The timer, store and executor are
 * owned by the caller; this class closes none of them. After a long outage many overdue tasks fire
 * at once, so size the executor queue for that burst (rejected tasks are re-armed, but slowly).
 */
public final class DurableScheduler {
    private static final System.Logger LOG = System.getLogger(DurableScheduler.class.getName());

    public static final class Options {
        private RetryPolicy retryPolicy = RetryPolicy.defaults();
        private Clock wallClock = System::currentTimeMillis;
        private long rejectedRetryDelayMs = 200;
        private Consumer<TaskRecord> deadLetterListener = record -> {};
        private DoubleSupplier random = () -> ThreadLocalRandom.current().nextDouble();

        public Options retryPolicy(RetryPolicy policy) {
            this.retryPolicy = Objects.requireNonNull(policy, "retryPolicy");
            return this;
        }

        /** Epoch-based clock; NOT the timer's monotonic clock. */
        public Options wallClock(Clock clock) {
            this.wallClock = Objects.requireNonNull(clock, "wallClock");
            return this;
        }

        /** How long to wait before offering a task to a full executor again. Must be at least 1. */
        public Options rejectedRetryDelayMs(long delayMs) {
            if (delayMs < 1) {
                throw new IllegalArgumentException("rejectedRetryDelayMs must be >= 1");
            }
            this.rejectedRetryDelayMs = delayMs;
            return this;
        }

        /** Called (on a worker thread) after a task has been moved to the dead-letter store. */
        public Options deadLetterListener(Consumer<TaskRecord> listener) {
            this.deadLetterListener = Objects.requireNonNull(listener, "deadLetterListener");
            return this;
        }

        /** Source of jitter in [0, 1); replace it to make retries deterministic in tests. */
        public Options random(DoubleSupplier random) {
            this.random = Objects.requireNonNull(random, "random");
            return this;
        }
    }

    /** The unit of work handed to the timer; carries the record so a rejection can re-arm it. */
    private final class TaskRunnable implements Runnable {
        final TaskRecord record;

        TaskRunnable(TaskRecord record) {
            this.record = record;
        }

        @Override
        public void run() {
            dispatch(record);
        }
    }

    private final TimingWheelTimer timer;
    private final TaskStore store;
    private final TaskHandler handler;
    private final RetryPolicy retryPolicy;
    private final Clock wallClock;
    private final long rejectedRetryDelayMs;
    private final Consumer<TaskRecord> deadLetterListener;
    private final DoubleSupplier random;
    //map record id to task, the task armed to wheel were tracked by live
    private final ConcurrentHashMap<String, TimerTask> live = new ConcurrentHashMap<>();
    private final Object recoverLock = new Object();
    private volatile boolean ready;

    public DurableScheduler(TimingWheelTimer timer, TaskStore store, TaskHandler handler) {
        this(timer, store, handler, System::currentTimeMillis);
    }

    /** @param wallClock epoch-based clock; NOT the timer's monotonic clock */
    public DurableScheduler(TimingWheelTimer timer, TaskStore store, TaskHandler handler, Clock wallClock) {
        this(timer, store, handler, new Options().wallClock(wallClock));
    }

    public DurableScheduler(TimingWheelTimer timer, TaskStore store, TaskHandler handler, Options options) {
        this.timer = Objects.requireNonNull(timer, "timer");
        this.store = Objects.requireNonNull(store, "store");
        this.handler = Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(options, "options");
        this.retryPolicy = options.retryPolicy;
        this.wallClock = options.wallClock;
        this.rejectedRetryDelayMs = options.rejectedRetryDelayMs;
        this.deadLetterListener = options.deadLetterListener;
        this.random = options.random;
        timer.setRejectionHandler(this::onRejected);
    }

    public int recover() {
        synchronized (recoverLock) {
            if (ready) {
                throw new IllegalStateException("Already ready");
            }
            List<TaskRecord> records = new ArrayList<>(store.loadAll());
            records.sort(Comparator.comparingLong(TaskRecord::deadlineEpochMs).thenComparing(TaskRecord::id));
            long now = wallClock.nowMs();
            int armed = 0;
            for (TaskRecord record : records) {
                try {
                    arm(record, Math.max(0L, record.deadlineEpochMs() - now));
                    armed++;
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR, "cannot re-arm task" + record.id(), e);
                }
            }
            ready = true;
            return armed;
        }
    }

    public void schedule(String id, long delayMs, byte[] payload) {
        requireReady();

        if (delayMs < 0) {
            throw new IllegalArgumentException("delayMs must be >= 0");

        }
        long deadline;
        try {
            deadline = Math.addExact(wallClock.nowMs(), delayMs);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("delayMs too large" + delayMs, e);
        }
        TaskRecord record = new TaskRecord(id, deadline, payload);
        if (!store.putIfAbsent(record)) {
            throw new IllegalArgumentException("task id already scheduled" + id);
        }

        try {
            arm(record, delayMs);
        } catch (RuntimeException e) {
            try {
                store.remove(id);
            } catch (RuntimeException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /**
     * Removes the task from the store (so it is gone even after a restart) and from the wheel.
     * Call it after schedule has returned. A handler that is already running is not
     * interrupted.     *
     * return true if a stored record existed
     */
    public boolean cancel(String id) {
        requireReady();
        boolean existed = store.remove(id);
        TimerTask task = live.remove(id);
        if (task != null) {
            task.cancel();
        }
        return existed;
    }

    /** Tasks that used up their attempts (or failed permanently) and wait for a human. */
    public List<TaskRecord> deadLetters() {
        return store.loadDeadLetters();
    }

    /**
     * Puts a dead letter back into circulation with its attempt count reset; it is delivered
     * immediately.
     *
     * return false if there is no such dead letter, or the id has been scheduled again meanwhile
     */
    public boolean retryDeadLetter(String id) {
        requireReady();
        Optional<TaskRecord> revived = store.requeueDeadLetter(id, wallClock.nowMs());
        if (revived.isEmpty()) {
            return false;
        }
        arm(revived.get(), 0);
        return true;
    }

    /** return true if a dead letter existed and was discarded */
    public boolean discardDeadLetter(String id) {
        return store.removeDeadLetter(id);
    }

    //tasks current armed in this process( for test and diagnostic
    int armedCount() {
        return live.size();
    }

    private void requireReady() {
        if (!ready) {
            throw new IllegalStateException("call recover() before using the scheduler");
        }
    }

    //dispatch the task to handler to be executed
    private void dispatch(TaskRecord record) {
        String id = record.id();
        live.remove(id);
        if (!store.contains(id)) {
            return;
        }
        Throwable failure = null;
        try {
            handler.handle(record);
        }catch (Throwable t){
            failure = t;
        }

        if (failure == null) {
            acknowledge(id);
        } {
            onFailure(record, failure);
        }
    }
    //remove from the store when the task is successfuly executed
    private void acknowledge(String id) {
        try {
            store.remove(id);
        }catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR,
                    "task " + id + " ran but its record could not be removed; it will run again after recovery", e);
        }
    }

    /**
     *task were executed, but throw error during the execution, so retry attempt quote consumed.     *
     */
    private void onFailure(TaskRecord record, Throwable failure) {
        String id = record.id();
        String error = describe(failure);
        int attemptsMade = record.attempts() + 1;
        boolean permanent = failure instanceof NonRetryableException;
        try {
            if (permanent || retryPolicy.exhausted(attemptsMade)) {
                TaskRecord finalState = record.afterFailedAttempt(error, record.deadlineEpochMs());
                if (store.deadLetter(finalState)) {
                    LOG.log(System.Logger.Level.WARNING, "task " + id + " moved to the dead-letter store after "
                            + attemptsMade + " attempt(s): " + error);
                    notifyDeadLetter(finalState);
                }
                return;
            }
            long delay = retryPolicy.delayMs(attemptsMade, random.getAsDouble());
            TaskRecord next = record.afterFailedAttempt(error, wallClock.nowMs() + delay);
            if (store.replace(next)) {            // persist attempts + next deadline BEFORE re-arming
                arm(next, delay);
            }                                      // false: cancelled in the meantime, do not resurrect it
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "task " + id + " failed and its retry state could not be saved; "
                    + "it stays stored and is delivered again after the next recovery", e);
        }
    }

    private void arm(TaskRecord record, long delayMs) {
        TimerTask  task = timer.schedule(delayMs, new TaskRunnable(record));
        live.put(record.id(), task);
        if (task.isFired() || task.isCancelled()) {
            live.remove(record.id(), task);
        }
    }

    private void notifyDeadLetter(TaskRecord finalState) {
        try {
            deadLetterListener.accept(finalState);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "dead-letter listener failed for " + finalState.id(), e);
        }
    }

    /** The executor refused a due task, at this point the task has not been executed,
     * reason: queue full, so the handler never ran: try again soon, no attempt quote used.
     * */
    private void onRejected(TimerTask task) {
        Runnable action = task.action();
        if (!(action instanceof TaskRunnable)) {
            LOG.log(System.Logger.Level.ERROR, "executor rejected a due task");
            return;
        }
        TaskRecord record = ((TaskRunnable) action).record;
        try {
            if (store.contains(record.id())) {
                long jitter = (long) (rejectedRetryDelayMs * 0.5 * random.getAsDouble());
                arm(record, rejectedRetryDelayMs + jitter);
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "cannot re-arm rejected task " + record.id()
                    + "; it stays stored and is delivered after the next recovery", e);
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

}
