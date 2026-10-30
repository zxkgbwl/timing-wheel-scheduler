package io.timewheel.bench;

import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * Fixed-size histogram of non-negative values (microseconds here) with about 6 % resolution:
 * 16 sub-buckets per power of two. Constant memory, lock-free recording, so measuring does not
 * disturb the thing being measured the way keeping millions of samples would.
 */
final class LagHistogram {

    private static final int SUB_BITS = 4;
    private static final int SUB = 1 << SUB_BITS;
    private static final int BUCKETS = (64 - SUB_BITS) * SUB;

    private final LongAdder[] counts = new LongAdder[BUCKETS];
    private final LongAdder total = new LongAdder();
    private final LongAccumulator max = new LongAccumulator(Math::max, 0);

    LagHistogram() {
        for (int i = 0; i < BUCKETS; i++) {
            counts[i] = new LongAdder();
        }
    }

    void record(long value) {
        counts[index(value)].increment();
        total.increment();
        max.accumulate(value);
    }

    long count() {
        return total.sum();
    }

    long max() {
        return max.get();
    }

    /** Smallest value of the bucket holding the p-th percentile, 0 &lt; p &lt;= 100. */
    long percentile(double p) {
        long n = total.sum();
        if (n == 0) {
            return 0;
        }
        long rank = Math.max(1, (long) Math.ceil(n * p / 100.0));
        long seen = 0;
        for (int i = 0; i < BUCKETS; i++) {
            seen += counts[i].sum();
            if (seen >= rank) {
                return lowerBound(i);
            }
        }
        return max.get();
    }

    static int index(long v) {
        if (v < SUB) {
            return (int) Math.max(v, 0);
        }
        int exp = 63 - Long.numberOfLeadingZeros(v);
        int sub = (int) ((v >>> (exp - SUB_BITS)) & (SUB - 1));
        return (exp - SUB_BITS + 1) * SUB + sub;
    }

    static long lowerBound(int index) {
        if (index < SUB) {
            return index;
        }
        int exp = index / SUB + SUB_BITS - 1;
        int sub = index % SUB;
        return (long) (SUB + sub) << (exp - SUB_BITS);
    }
}
