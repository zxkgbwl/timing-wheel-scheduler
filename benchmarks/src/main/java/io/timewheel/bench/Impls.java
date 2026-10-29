package io.timewheel.bench;

import io.timewheel.DisruptorTaskExecutor;
import io.timewheel.ThreadPoolTaskExecutor;

/** Builds a candidate by name. Heavy classes (Netty, Jedis) are only loaded when selected. */
public final class Impls {

    public static final String NAMES =
            "noop, wheel, wheel-pool, wheel-disruptor, delayqueue, stpe, netty, redis";

    private Impls() {
    }

    public static TimerImpl create(String name, Settings settings) {
        int workers = settings.integer("workers", 4);
        int queue = settings.integer("queue", 1 << 20);
        int tick = settings.integer("tick", 1);
        int wheelSize = settings.integer("wheelSize", 512);
        switch (name) {
            case "noop":
                return new NoopTimerImpl();
            case "wheel":              // tasks run inline on the wheel's driver thread, like Netty / the JDK
                return new WheelTimerImpl(name, new ThreadPoolTaskExecutor(Runnable::run), tick, wheelSize);
            case "wheel-pool":         // tasks handed to a bounded thread pool
                return new WheelTimerImpl(name, new ThreadPoolTaskExecutor(workers, queue), tick, wheelSize);
            case "wheel-disruptor":    // tasks handed to a Disruptor ring buffer
                return new WheelTimerImpl(name, new DisruptorTaskExecutor(workers, queue), tick, wheelSize);
            case "delayqueue":
                return new DelayQueueTimerImpl();
            case "stpe":
                return new ScheduledExecutorTimerImpl();
            case "netty":
                return new NettyTimerImpl();
            case "redis":
                return new RedisZSetTimerImpl(settings.text("redisHost", "localhost"), settings.integer("redisPort", 6379));
            default:
                throw new IllegalArgumentException("unknown impl '" + name + "'; choose one of: " + NAMES);
        }
    }
}
