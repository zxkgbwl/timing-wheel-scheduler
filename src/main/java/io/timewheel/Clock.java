package io.timewheel;

@FunctionalInterface
public interface Clock {
    long nowMs();
    //constant
    Clock SYSTEM = () -> System.nanoTime() / 1_000_000L;
}
