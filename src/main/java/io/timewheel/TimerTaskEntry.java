package io.timewheel;

import java.sql.Time;
//node of the bucket's doubly linked list, the list will be guarded by the lock who owns it.
final class TimerTaskEntry {
    volatile TimerTaskList list;
    TimerTaskEntry next;
    TimerTaskEntry prev;

    final TimerTask task;
    final long expirationMs;

    //task is null only for the list's sentinel node
    TimerTaskEntry(TimerTask task, long expirationMs) {
        this.task = task;
        this.expirationMs = expirationMs;
        if (task != null) {
            task.setEntry(this);
        }
    }

    boolean cancelled() {
        return task != null && task.isCancelled();
    }

    void remove() {
        TimerTaskList current = list;
        while (current != null) {
            current.remove(this);
            current = list;
        }
    }
}
