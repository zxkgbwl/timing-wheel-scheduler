package io.timewheel;

public class ManualClock implements Clock{
    private volatile long now;
    ManualClock(long start){
        this.now = start;
    }

    void set(long ms) {
        this.now = ms;
    }

    @Override
    public long nowMs() {
        return now;
    }
}
