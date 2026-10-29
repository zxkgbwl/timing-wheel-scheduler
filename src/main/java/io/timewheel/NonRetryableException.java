package io.timewheel;

/**
 * thrown by a TaskHandler to say "retrying is no use" when a falformed payload, or a business rule that can
 * not pass, the task goes directly to dead-letter store instead of retry attempts
 */
public class NonRetryableException extends RuntimeException{
    private static final long serialVersionUID = 1L;

    public NonRetryableException(String message){
        super(message);
    }

    public NonRetryableException(String message, Throwable cause){
        super(message,cause);
    }
}
