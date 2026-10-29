package io.timewheel.bench;

import java.util.Arrays;
import java.util.concurrent.locks.LockSupport;

/**
 * How precisely can this machine wake a sleeping thread? Every timer here waits with
 * {@code LockSupport.parkNanos}; if asking for 1 ms really sleeps 2 ms or 15 ms (the Windows default
 * timer resolution is about 15.6 ms unless something raises it), then the lateness figures in
 * {@link LoadTest} measure the operating system, not the data structure.
 *
 * <pre>java -cp target/benchmarks.jar io.timewheel.bench.TimerResolutionProbe</pre>
 */
public final class TimerResolutionProbe {

    private TimerResolutionProbe() {
    }

    public static void main(String[] args) {
        System.out.println("requested  ->  actual sleep (microseconds)");
        for (long micros : new long[] {100, 500, 1_000, 2_000, 5_000}) {
            int n = 200;
            long[] actual = new long[n];
            for (int i = 0; i < n + 20; i++) {                       // the first 20 are warm-up
                long start = System.nanoTime();
                LockSupport.parkNanos(micros * 1_000);
                long took = (System.nanoTime() - start) / 1_000;
                if (i >= 20) {
                    actual[i - 20] = took;
                }
            }
            Arrays.sort(actual);
            System.out.printf("%6d us   ->  median %6d   p90 %6d   max %6d%n",
                    micros, actual[n / 2], actual[n * 9 / 10], actual[n - 1]);
        }
        System.out.println();
        System.out.println("If a request for 1000 us takes about 1000-1200 us, the OS is not the limit.");
        System.out.println("If it takes about 2000 us or 15000 us, lateness below that is not measurable here.");
    }
}
