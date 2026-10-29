package io.timewheel;

/**
 * How many times a failing task is attempted and how long to wait in between: exponential
 * backoff with a cap and random jitter.
 *
 */
public final class RetryPolicy {

    private final int maxAttempts;
    private final long initialDelayMs;
    private final double multiplier;
    private final long maxDelayMs;
    private final double jitterRatio;

    public RetryPolicy(int maxAttempts, long initialDelayMs, double multiplier, long maxDelayMs, double jitterRatio) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (initialDelayMs < 0) {
            throw new IllegalArgumentException("initialDelayMs must be >= 0");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be >= 1");
        }
        if (maxDelayMs < initialDelayMs) {
            throw new IllegalArgumentException("maxDelayMs must be >= initialDelayMs");
        }
        if (jitterRatio < 0.0 || jitterRatio > 1.0) {
            throw new IllegalArgumentException("jitterRatio must be in [0, 1]");
        }
        this.maxAttempts = maxAttempts;
        this.initialDelayMs = initialDelayMs;
        this.multiplier = multiplier;
        this.maxDelayMs = maxDelayMs;
        this.jitterRatio = jitterRatio;
    }

    /** 5 attempts, 1 s then 2 s, 4 s, 8 s between them, capped at 60 s, +/- 20 % jitter. */
    public static RetryPolicy defaults() {
        return new RetryPolicy(5, 1_000, 2.0, 60_000, 0.2);
    }

    /** The first failure sends the task to the dead-letter store. */
    public static RetryPolicy noRetry() {
        return new RetryPolicy(1, 0, 1.0, 0, 0.0);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /** @param attemptsMade attempts that have already run, successful or not */
    public boolean exhausted(int attemptsMade) {
        return attemptsMade >= maxAttempts;
    }

    /**
     * @param failedAttempts how many attempts have failed so far (at least 1)
     * @param random01       a value in [0, 1); 0.5 means "no jitter"
     */
    public long delayMs(int failedAttempts, double random01) {
        if (failedAttempts < 1) {
            throw new IllegalArgumentException("failedAttempts must be >= 1");
        }
        double base = Math.min(initialDelayMs * Math.pow(multiplier, failedAttempts - 1), (double) maxDelayMs);
        double jitter = 1.0 + jitterRatio * (2.0 * random01 - 1.0);
        return Math.max(0L, Math.round(base * jitter));
    }

    @Override
    public String toString() {
        return "RetryPolicy{maxAttempts=" + maxAttempts + ", initialDelayMs=" + initialDelayMs
                + ", multiplier=" + multiplier + ", maxDelayMs=" + maxDelayMs + ", jitterRatio=" + jitterRatio + '}';
    }
}
