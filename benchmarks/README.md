# Benchmarks

Compares this project's hierarchical timing wheel with the usual alternatives on the things a timer
is chosen for: the cost of scheduling and cancelling at scale, accuracy, CPU, and memory.

| Candidate | What it is | Tasks run |
|---|---|---|
| `wheel` | this project, 1 ms tick, 512 buckets per level | inline on the wheel's driver thread |
| `wheel-pool` | same wheel, bounded `ThreadPoolTaskExecutor` | on a worker pool |
| `wheel-disruptor` | same wheel, `DisruptorTaskExecutor` | on a worker pool fed by a ring buffer |
| `delayqueue` | `java.util.concurrent.DelayQueue` + 1 consumer thread, lazy cancel | inline on the consumer |
| `stpe` | `ScheduledThreadPoolExecutor(1)`, remove-on-cancel | inline on its worker |
| `netty` | Netty `HashedWheelTimer`, 1 ms tick, 512 buckets | inline on its worker |
| `redis` | `ZADD` / `ZRANGEBYSCORE` + `ZREM` delay queue (needs Redis) | inline on a poller thread |
| `noop` | accepts and drops tasks | measures the load generator itself |

The first group of candidates run tasks inline on ONE timer thread on purpose: it isolates the data
structure. `wheel-pool` and `wheel-disruptor` compare the execution layer (step 3) instead.

## Build and run

```
# 1. in the repository root: install the library into the local Maven repository
mvn -q install -DskipTests

# 2. in this directory: build the benchmark jar
mvn -q package

# 3a. everything, one JVM per candidate, results collected as CSV
./run-all.sh                  # Linux / macOS
.\run-all.ps1                 # Windows PowerShell

# 3b. or piece by piece
java -jar target/benchmarks.jar SchedulingCostBenchmark                 # JMH, 1 thread
java -jar target/benchmarks.jar SchedulingCostBenchmark -t 4            # JMH, 4 threads
java -Xms4g -Xmx4g -cp target/benchmarks.jar io.timewheel.bench.LoadTest --mode=load   --impl=wheel --rate=500000 --seconds=20
java -Xms4g -Xmx4g -cp target/benchmarks.jar io.timewheel.bench.LoadTest --mode=memory --impl=wheel --tasks=1000000 --cancelFraction=0.9
```

`LoadTest` options: `--rate` (tasks/s, default 100000), `--seconds` (10), `--producers` (4),
`--delayMin` / `--delayMax` (500..3000 ms), `--workers` and `--queue` (for the pooled variants),
`--redisHost` / `--redisPort`. Use `--rate=1000000` for the "million timers per second" scenario;
it needs a machine with enough cores and heap for the producers plus the timer (try `-Xmx8g`).

## What each measurement means

**`SchedulingCostBenchmark` (JMH)** fills a timer with N pending timers (1 000, 100 000, 1 000 000)
and times "schedule one more and cancel it". The claim under test is O(1) vs O(log N): the wheel's
line should stay flat as N grows while the heap-based ones climb. It times the CALLING thread only.
Netty defers part of its work to its own thread, so read it together with the CPU figure below.

**`LoadTest --mode=load`** runs paced producers for a fixed time and reports:

| Field | Meaning |
|---|---|
| achieved schedule rate | what the timer actually accepted; below the target means it could not keep up |
| lost | scheduled but never run (for example refused by a full executor queue) |
| lateness p50 / p99 / p99.9 / max | `actual run time - deadline`, in ms. **Negative means early** |
| early (more than 1 ms) | must be 0. See the note on clock granularity below |
| cpu us per task | total PROCESS CPU divided by tasks scheduled, producers included |
| peak pending | most timers waiting at once |

Subtract the `noop` row to remove what the load generator itself costs.

**`LoadTest --mode=memory`** schedules timers 10 to 20 minutes out (none fires), then optionally
cancels a fraction. It reports bytes per scheduled task and how many bytes are STILL HELD per
cancelled task. Memory is measured by DIFFERENCES between GC'd checkpoints, never against an
absolute baseline, because the used-heap reading right after a GC carries a constant offset that
varies with the collector and heap layout.

## Things to know before trusting a number

- **Clock granularity.** The wheel's clock has 1 ms resolution, so a task can run up to just under
  1 ms BEFORE its exact nanosecond deadline (about 40 % of tasks do, by a fraction of a millisecond).
  The test treats anything earlier than 1 ms as a bug and counts it separately. `delayqueue` and
  `stpe` use nanosecond deadlines and are never early. If sub-millisecond precision matters to you,
  that is a real trade-off.
- **Lazy cancellation.** The `delayqueue` baseline marks cancelled entries and skips them when they
  reach the head, because `DelayQueue.remove(Object)` is O(N). The memory probe shows the price: a
  cancelled task keeps all of its memory until its original deadline.
- **Netty is asynchronous.** `newTimeout` and `cancel` only enqueue; the worker thread does the
  wheel work on its next tick.
- **Redis is not pipelined.** A caller that wants a handle back cannot batch, so every schedule and
  cancel is a network round trip. The Runnable itself stays in a client-side map, since Redis
  stores only ids. Run Redis on the same machine to get a lower bound for its cost.
- **Compare only within one machine and one session.** Absolute numbers depend on CPU, JDK, power
  plan, and whatever else is running. Close other programs, plug the laptop in, run each candidate
  at least three times and report the median.
- **One JVM per candidate**, same JVM flags for all (the scripts do this), so JIT and GC state does
  not leak from one into the next.

## What to publish

Record the hardware (CPU model, cores, RAM), OS, JDK version and exact command lines next to every
table, keep the raw `results/*.csv`, and write down the cases where the wheel is NOT the best.
A benchmark that shows only wins is not believed.
