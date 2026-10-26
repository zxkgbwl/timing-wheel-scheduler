package io.timewheel;

/**
 * what to do when a durable task id due, runs on a executer worker thread
 * Delivery is "AT LEAST ONCE"
 * if the process die after the handler finished but the record was not removed yet, the task runs again after recovery
 * so the handler should be indemptent(example: order-timeout handler: check the roder is still unpaid before
 * cancelling it)
 */
@FunctionalInterface
public interface TaskHandler {
    void handle(TaskRecord record) throws Exception;
}
