package io.timewheel;

import java.util.List;
import java.util.Optional;

/**
 * durable home of shceduled task, "write-ahead " ensure the crash recovery
 * define the interface, implementation could be either on rockDB or redis
 */
public interface TaskStore extends AutoCloseable{

    boolean putIfAbsent(TaskRecord record);

    //overwirte an existing active record (used to presite a retry's attampt count and next deadline)
    //dose nothing if the id is not active, return directly if the id dost not exist.
    boolean replace(TaskRecord record);

    boolean remove(String id);

    boolean contains(String id);

    List<TaskRecord> loadAll();

    /**
     * Atomically removes the active record with this id and stores it as a dead
     * letter, replacing an older dead letter with the same id if there is one.
     * return false if there was no active record with that id (nothing is changed)
     */
    boolean deadLetter(TaskRecord finalState);

    List<TaskRecord> loadDeadLetters();
    /**
     * moves a dead letter back to the active set with its attempts reset, due at  deadlineEpochMs.     *
     * return the re-activated record, or empty if there is no such dead letter or the id is
     *         active again (nothing is changed)
     */
    Optional<TaskRecord> requeueDeadLetter(String id, long deadlineEpochMs);

    //return true if the dead letter existed and was discarded
    boolean removeDeadLetter(String id);

    @Override
    void close();


}
