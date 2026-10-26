package io.timewheel;

import java.util.List;

/**
 * durable home of shceduled task, "write-ahead " ensure the crash recovery
 * define the interface, implementation could be either on rockDB or redis
 */
public interface TaskStore extends AutoCloseable{

    boolean putIfAbsent(TaskRecord record);

    boolean remove(String id);

    List<TaskRecord> loadAll();

    @Override
    void close();
}
