package io.timewheel;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
 * Known limits: a task whose handler throws or that a full executor rejects, stays in the store utill next recovery
 * will address in step 5
 */
public final class DurableScheduler {
    private static final System.Logger LOG = System.getLogger(DurableScheduler.class.getName());

    private final TimingWheelTimer timer;
    private final TaskStore store;
    private final TaskHandler handler;
    private final Clock wallClock;
    //map record id to task, the task armed to wheel were tracked by live
    private final ConcurrentHashMap<String, TimerTask> live = new ConcurrentHashMap<>();
    private final Object recoverLock = new Object();
    private volatile boolean ready;

    public DurableScheduler(TimingWheelTimer timer, TaskStore store, TaskHandler handler) {
        this(timer, store, handler, System::currentTimeMillis);
    }

    //wallClock epoch-based clock; not the timer's nonotonic clock
    public DurableScheduler(TimingWheelTimer timer, TaskStore store, TaskHandler handler, Clock wallClock) {
        this.timer = Objects.requireNonNull(timer);
        this.store = Objects.requireNonNull(store);
        this.handler = Objects.requireNonNull(handler);
        this.wallClock = Objects.requireNonNull(wallClock);
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

    //tasks current armed in this process( for test and diagnostic
    int armedCount() {
        return live.size();
    }

    private void requireReady() {
        if (!ready) {
            throw new IllegalStateException("call recover() before using the scheduler");
        }
    }

    private void arm(TaskRecord record, long delayMs) {
        TimerTask  task = timer.schedule(delayMs, ()-> run(record));
        live.put(record.id(), task);
        if (task.isFired() || task.isCancelled()) {
            live.remove(record.id(), task);
        }
    }

    private void run (TaskRecord record) {
        boolean acknowledged = false;
        try {
            handler.handle(record);
            acknowledged = true;
        }catch (Throwable t) {
            LOG.log(System.Logger.Level.ERROR, "TaskHandler failed for task " + record.id() + "; record kept for" +
                    "redelivery", t);
        }
        //remove the record from the live before operate the store
        live.remove(record.id());
        if (acknowledged) {
            try {
                store.remove((record.id()));
            }catch( RuntimeException e ) {
                LOG.log(System.Logger.Level.ERROR, "task" + record.id() + "ran but record can not be removed," +
                        "it will run again after recovery", e);
            }
        }

    }

}
