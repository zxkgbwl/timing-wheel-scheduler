package io.timewheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private static final double NO_JITTER = 0.5;

    @Test
    void delaysGrowExponentiallyUntilTheCap() {
        RetryPolicy policy = new RetryPolicy(10, 1_000, 2.0, 10_000, 0.0);
        assertEquals(1_000, policy.delayMs(1, NO_JITTER));
        assertEquals(2_000, policy.delayMs(2, NO_JITTER));
        assertEquals(4_000, policy.delayMs(3, NO_JITTER));
        assertEquals(8_000, policy.delayMs(4, NO_JITTER));
        assertEquals(10_000, policy.delayMs(5, NO_JITTER));    // 16 000 capped
        assertEquals(10_000, policy.delayMs(2_000, NO_JITTER)); // pow() overflows to infinity, still capped
    }

    @Test
    void jitterSpreadsTheDelayByThePlusMinusRatio() {
        RetryPolicy policy = new RetryPolicy(5, 1_000, 2.0, 60_000, 0.2);
        assertEquals(800, policy.delayMs(1, 0.0));
        assertEquals(1_000, policy.delayMs(1, 0.5));
        assertEquals(1_200, policy.delayMs(1, 1.0));
        assertTrue(policy.delayMs(3, 0.0) < policy.delayMs(3, 0.999));
    }

    @Test
    void maxAttemptsCountsTheFirstAttempt() {
        RetryPolicy three = new RetryPolicy(3, 10, 1.0, 10, 0.0);
        assertFalse(three.exhausted(1));
        assertFalse(three.exhausted(2));
        assertTrue(three.exhausted(3));
        assertTrue(RetryPolicy.noRetry().exhausted(1));
        assertFalse(RetryPolicy.defaults().exhausted(4));
        assertTrue(RetryPolicy.defaults().exhausted(5));
    }

    @Test
    void invalidSettingsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, 1, 1.0, 1, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, -1, 1.0, 1, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, 1, 0.5, 1, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, 10, 1.0, 5, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, 1, 1.0, 1, 1.5));
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.defaults().delayMs(0, 0.5));
    }
}
