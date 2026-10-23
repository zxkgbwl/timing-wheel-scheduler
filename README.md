# timing-wheel-scheduler

A high-throughput delay-task scheduler built on a hierarchical timing wheel
(O(1) insert/cancel, vs O(log N) for `DelayQueue`).

## Status

| Step | Scope | State |
|------|-------|-------|
| 1 | Single-level wheel, doubly linked buckets, bucket-driven `DelayQueue`| done |
| 2 | Hierarchical wheel (overflow wheels + demotion) | done |
| 3 | Pluggable execution layer (thread pool vs Disruptor) | done |
| 4 | `TaskStore` (RocksDB / Redis), WAL-style persistence, crash recovery |next |
| 5 | ACK, retry, dead-letter queue (at-least-once) | |
| 6 | JMH + load tests vs `DelayQueue`, Netty `HashedWheelTimer`, Redis ZSet | |

## Design notes (step 1)

- **Never early, at most `tickMs - 1` late.** A task goes to the bucket of the first tick
  boundary at or after its deadline.
- **Buckets drive time, not ticks.** A `DelayQueue` holds one element per non-empty bucket, so
  the driver sleeps until the next occupied bucket instead of waking every tick.
- **Reads/writes are separated.** `schedule` takes a read lock (parallel producers);
  `advanceClock` takes the write lock to move time and flush buckets.
- **Actions never run on the driver thread.** They go to the supplied `Executor`.
- **Exactly-once state machine.** `PENDING -> CANCELLED | FIRED` via CAS, so a task never runs
  twice and never runs after a successful `cancel()`.

## Design notes (step 2: hierarchical wheel)

- **Any delay is accepted.** A task that does not fit the finest wheel goes to a lazily created
  coarser wheel (level 2, 3, ...). Levels are created on demand; a long idle timer holds one.
- **Finest level rounds up, coarse levels round down.** Level 1 puts a task at the first tick
  boundary at or after its deadline (never early). Level 2+ puts it in the bucket that *starts*
  at or before the deadline; when that bucket is due, its tasks are **demoted**: re-inserted into
  a finer level, which positions them precisely. Tasks never fire from a coarse bucket.
- **Tick sizes make demotion terminate.** With level-1 tick `t` and size `n`, level 2 uses tick
  `(n-1)*t` (exactly the span level 1 can hold), level 3 uses `n*(n-1)*t`, and so on. A demoted
  task therefore always fits the level below, and every coarse bucket starts strictly after the
  level-1 time, so it can never be due the moment it is created (which would loop forever).
- **Level 1 leaves the current-tick slot free.** That slot may still hold a bucket waiting to be
  flushed; reusing it would change the expiration of a bucket already inside the `DelayQueue`.
- All levels share one `DelayQueue`, so the driver still sleeps until the single earliest bucket,
  whichever level it belongs to.

## Usage

```java
ExecutorService workers = Executors.newFixedThreadPool(8);
try (TimingWheelTimer timer = new TimingWheelTimer(1, 512, workers)) { // 1 ms tick, 512 buckets per level
    timer.start();
    TimerTask t = timer.schedule(30 * 60_000, () -> System.out.println("fired")); // 30 minutes
    // t.cancel();
    Thread.sleep(2000);
}
workers.shutdown();
```

## Build

```
mvn test
```
