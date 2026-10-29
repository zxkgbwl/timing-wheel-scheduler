# timing-wheel-scheduler
[![CI](https://github.com/zxkgbwl/timing-wheel-scheduler/actions/workflows/ci.yml/badge.svg)](https://github.com/zxkgbwl/timing-wheel-scheduler/actions/workflows/ci.yml)

A high-throughput delay-task scheduler built on a hierarchical timing wheel
(O(1) insert/cancel, vs O(log N) for `DelayQueue`).

## Status

| Step | Scope | State |
|------|-------|-------|
| 1 | Single-level wheel, doubly linked buckets, bucket-driven `DelayQueue`| done |
| 2 | Hierarchical wheel (overflow wheels + demotion) | done |
| 3 | Pluggable execution layer (thread pool vs Disruptor) | done |
| 4 | `TaskStore` (RocksDB / Redis), WAL-style persistence, crash recovery |done |
| 5 | ACK, retry, dead-letter queue (at-least-once) | done |
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


## Design notes (step 3: pluggable execution layer)

- **`TaskExecutor` is the only thing the timer knows about execution.** The driver thread calls
  `submit(task)`; the implementation decides which thread runs it. `ThreadPoolTaskExecutor` and
  `DisruptorTaskExecutor` are interchangeable, which is what makes the step-6 comparison fair.
- **`submit` never blocks.** A blocked driver stops the whole wheel, so a full queue / ring buffer
  returns `false`. Both implementations use a bounded buffer for the same reason.
- **One place defines "run a task".** `TimerTask.runIfPending()` is what every worker calls: it
  runs at most once, never after a successful `cancel()`, and swallows exceptions. A new executor
  cannot get these rules wrong because it does not implement them.
- **Disruptor specifics.** Multi-producer (already-due tasks are submitted from the caller's
  thread, later ones from the driver), a worker pool so each slot is consumed by exactly one
  worker, `tryPublishEvent` for the non-blocking hand-off, and a configurable wait strategy.
- **One contract test, two implementations.** `TaskExecutorContractTest` is inherited by both
  executor tests, so they are held to identical behaviour.
- **Known gap (step 5):** a task rejected by a full executor has already left the wheel and is
  only logged, not retried.

## Design notes (step 4: persistence and crash recovery)

- **A `Runnable` cannot be persisted, so durable tasks are data.** `TaskRecord` = id + absolute
  wall-clock deadline + opaque payload. After a restart a `TaskHandler` is given the record.
- **Deadlines are wall-clock, the wheel is monotonic.** The wheel runs on `System.nanoTime`, which
  restarts from an arbitrary origin, so the persisted deadline is epoch milliseconds and recovery
  re-arms each task with `deadline - now`. Overdue tasks run immediately, in deadline order.
- **Ordering is the whole design.**
  - schedule: write the record, THEN arm the wheel (a crash in between leaves a stored task);
  - fire: run the handler, THEN remove the record (a crash in between re-runs the task);
  - cancel: remove the record, THEN cancel the wheel entry (a crash in between cannot resurrect it).
- **At-least-once delivery.** Handlers must be idempotent. Returning normally acknowledges the
  task; throwing keeps the record for redelivery after the next recovery.
- **`recover()` before `schedule()`**, enforced. A record that cannot be re-armed is logged and
  skipped; one bad record does not block the rest.
- **`TaskStore` is an interface** with an in-memory implementation (tests) and a RocksDB one. Both
  run the same contract tests; the durable ones also reopen the store and check nothing was lost.
- **Known gaps (step 5):** a failed handler or an executor-rejected task is not retried until the
  next recovery, and many overdue tasks fire at once after a long outage.


## Design notes (step 5: acknowledgement, retry and dead letters)

- **ACK / NACK is the handler's return.** Returning normally acknowledges the task (its record is
  removed). Throwing is a failed attempt. Throwing `NonRetryableException` means "retrying cannot
  help" and skips the remaining attempts.
- **Retry = exponential backoff with a cap and jitter** (`RetryPolicy`). `maxAttempts` counts the
  first attempt; the jitter spreads out tasks that failed together so they do not retry together.
- **Retry state is persisted before the retry is armed.** The record is rewritten with
  `attempts + 1`, the last error and the NEXT deadline, then re-armed. A crash while a task waits
  for its retry keeps the attempt count, so a poison task cannot retry forever by crashing the
  process, and the retry fires at the right wall-clock time after recovery.
- **Dead letters are a second set in the same store.** When attempts run out the record moves
  there in one atomic step (a single RocksDB `WriteBatch`). Recovery ignores dead letters; a
  human can list them, retry one (attempts reset) or discard it.
- **A refusal by a full executor is not a failure.** The handler never ran, so no attempt is used:
  the timer reports the refused task through a rejection handler and the scheduler re-offers it
  shortly after. Without this, a burst of overdue tasks after an outage could exhaust attempts
  that were never really used.
- **Cancel is strict.** Before starting a task the scheduler re-checks that its record still
  exists, so once `cancel(id)` has returned true the handler will not be started, including for
  a task that is waiting for a retry or for the executor.
- **Delivery stays at-least-once**, so handlers must be idempotent. `TaskRecord.attempts()` tells a
  handler whether it is running a retry.
- **Storage format:** records are written as version 2 (adds attempts and last error) and version 1
  records still load. The RocksDB key layout is now prefixed (`a:` active, `d:` dead); delete a
  `demo-data` directory created by step 4.

## Demo

`CrashRecoveryDemo`: `demo-3` fails twice and then succeeds (retry), `demo-4` always fails and
ends in the dead-letter store. Kill the process at any point and run it again with `recover`.

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
