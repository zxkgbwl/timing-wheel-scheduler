package io.timewheel;

public class TaskStoreException extends RuntimeException{
    private static final long serialVersionUID = 1L;

    public TaskStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public TaskStoreException(String message) {
        super(message);
    }

}
