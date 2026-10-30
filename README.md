# timing-wheel-scheduler

[![CI](https://github.com/zxkgbwl/timing-wheel-scheduler/actions/workflows/ci.yml/badge.svg)](https://github.com/zxkgbwl/timing-wheel-scheduler/actions/workflows/ci.yml)

A **hierarchical timing wheel** for Java 17+ with a pluggable execution layer and an optional
**durable layer** that adds crash recovery, at-least-once delivery, retry with backoff and a
dead-letter store. Built for workloads such as "cancel the order if it is still unpaid after 30
minutes", at a rate where a heap-based `DelayQueue` becomes expensive.

It is measured, not just claimed: the repository includes a benchmark against `DelayQueue`,
`ScheduledThreadPoolExecutor` and Netty's `HashedWheelTimer`, with raw data and an honest account of where
this timer is **slower** than they are. Start with [Benchmarks at a glance](#benchmarks-at-a-glance).

## Features

- **O(1) insert and cancel** with a hierarchy of wheels created on demand; any delay is accepted.
- **Never early** (to the millisecond), at most one tick late.
- **One `DelayQueue` of non-empty buckets** drives all levels, so the driver thread sleeps until the
  next occupied bucket instead of ticking.
- **Cancelled timers free their memory immediately.**
- **Pluggable execution:** a bounded thread pool or an LMAX Disruptor ring buffer; a full executor is
  visible (`submit` returns `false`) and never blocks the timer.
- **Durable tasks** (optional): stored before they are armed, re-armed with the remaining time after a
  crash, acknowledged by the handler returning, retried with exponential backoff and jitter, and moved
  to a dead-letter store when attempts run out.
- Embedded **RocksDB** store, or an in-memory store for tests.

## Quick start

Maven, Java 17 or newer:

```
mvn verify
```

A plain timer:

```java
TaskExecutor executor = new ThreadPoolTaskExecutor(8, 4096);        // or new DisruptorTaskExecutor(8, 4096)
try (TimingWheelTimer timer = new TimingWheelTimer(1, 4096, executor)) {   // 1 ms tick, 4096 buckets per level
    timer.start();
    TimerTask task = timer.schedule(30 * 60_000, () -> System.out.println("order timed out"));
    // task.cancel();
}
executor.close();                                                    // close the timer first, then its executor
```

Durable tasks that survive a crash:

```java
try (RocksDbTaskStore store = new RocksDbTaskStore(Path.of("data"));
     TimingWheelTimer timer = new TimingWheelTimer(1, 4096, executor)) {
    timer.start();
    DurableScheduler scheduler = new DurableScheduler(timer, store,
            record -> cancelOrderIfUnpaid(record.id()),             // must be idempotent: delivery is at-least-once
            new DurableScheduler.Options().retryPolicy(RetryPolicy.defaults()));
    scheduler.recover();                                            // re-arms whatever was pending when the process died
    scheduler.schedule("order-42", 30 * 60_000, payloadBytes);
}
```

To see it work, run `io.timewheel.demo.CrashRecoveryDemo` with the argument `schedule`, kill the process,
then run it again with `recover`.

### Choosing the wheel size

The first level covers `(wheelSize − 1) × tickMs`. Delays longer than that pass through the next level
and are moved down when their coarse bucket is due. If most of your delays are a few seconds, size the first level
to cover them: with delays of 0.5–3 s, **4096 buckets gave p99 lateness 1.05 ms against 6.17 ms for 512**, and
12 % less CPU ([data](benchmarks/RESULTS.md)). Long delays (the 30-minute order) use the higher levels
whatever you choose; see [Known limitations](#known-limitations).

## Benchmarks at a glance

One Linux laptop (Intel i7-8665U, 15 W), 200 000 timers/s, about 350 000 pending, tasks run inline,
median of 3 runs. **CPU is net of the load generator.** Details, method and raw data:
[`benchmarks/RESULTS.md`](benchmarks/RESULTS.md).

| | CPU per timer | p99 lateness | Memory per pending timer | Held after cancelling |
|---|---|---|---|---|
| Netty `HashedWheelTimer` | **0.68 µs** | 2.07 ms | 99 B | 0 % |
| **This wheel (4096 buckets)** | 0.77 µs | 1.05 ms | 112 B | 0 % |
| This wheel (512 buckets) | 0.90 µs | 6.17 ms | 112 B | 0 % |
| `DelayQueue` (lazy cancel) | 1.13 µs | **0.02 ms** | **62 B** | **100 %** |
| `ScheduledThreadPoolExecutor` | 1.50 µs | **0.02 ms** | 126 B | 5 % |

What this says, without spin:

- Under load the wheel is **cheaper than the heap-based timers** and within about 13 % of Netty when it is sized well.
- Its cost per call does not grow with the number of pending timers (+13 % from 100 k to 1 M, against
  +57 % for the heap), **but a single call is still about twice as expensive** as the heap's and 2.5× Netty's.
- Because it works in milliseconds, its p99 lateness cannot go below about 1 ms; the heap-based timers
  wake with nanosecond precision.
- It needs more memory per pending timer than a plain `DelayQueue`, and gives it back when timers are cancelled.
- Netty has no persistence, recovery, retry or dead-letter store; that is where this project differs.

## Project status

| Phase | Scope | State |
|---|---|---|
| 1 | hierarchical wheel, pluggable executors, durable scheduling with crash recovery, retry and dead letters, benchmarks | **done** — see the [Phase 1 report](docs/PHASE1-REPORT.md) |
| 2 | optimisation driven by the Phase 1 measurements | planned: [Phase 2 plan](docs/PHASE1-REPORT.md#6-phase-2-plan) |

## Known limitations

1. **Resolution is 1 ms.** A task may run less than 1 ms before its exact-nanosecond deadline.
2. **Large coarse buckets stall the timer.** Timers with delays beyond the first level are flushed together when
   their coarse bucket is due, and the work grows with the arrival rate. At 200 000/s this raised p99 from 1 ms
   to 6 ms; at 1 000 000/s it has not been measured. Phase 2, task 1.
3. A single schedule+cancel call is slower than in the alternatives (≈190 ns against ≈100 ns and ≈80 ns), and memory per pending timer is higher (112 B).
4. No candidate, this one included, scales with the number of producer threads.
5. **Delivery is at-least-once**; handlers must be idempotent.
6. Durable deadlines use the **wall clock**, so a clock jump shifts them.
7. One process owns a RocksDB store. There is **crash recovery, not high availability** across nodes.
8. The Redis ZSet baseline is implemented but has **not been measured**; all measurements come from one machine.

## Documentation

| Document | What it covers |
|---|---|
| [`docs/DESIGN.md`](docs/DESIGN.md) | architecture, the level arithmetic, durability ordering, testing, decisions |
| [`docs/PHASE1-REPORT.md`](docs/PHASE1-REPORT.md) | what was built, the benchmark process including the mistakes, limitations, Phase 2 plan |
| [`benchmarks/RESULTS.md`](benchmarks/RESULTS.md) | measured results with conditions |
| [`benchmarks/README.md`](benchmarks/README.md) | how to run the benchmarks and read them |

## Repository layout

```
src/main/java/io/timewheel/    the library
src/test/java/io/timewheel/    tests, including contract tests and the crash-recovery demo
benchmarks/                    separate Maven project: JMH, load and memory tests, run scripts, published data
docs/                          design and phase reports
```

## License

See [`LICENSE`](LICENSE).
