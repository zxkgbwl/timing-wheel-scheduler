# Phase 1 report

What was built, how it was verified, what the measurements showed, what turned out to be wrong
along the way, and what Phase 2 should do. Written to be read without the history of the project.

## 1. Goal and scope

A high-throughput delay-task scheduler for Java, aimed at "a million timers" workloads such as
cancelling an order that is still unpaid after 30 minutes:

- a **hierarchical timing wheel** with O(1) insert and cancel, instead of a heap-based
  `DelayQueue`;
- a **pluggable execution layer** (thread pool or Disruptor) so a slow task cannot block the wheel;
- **durability**: persistence, crash recovery, acknowledgement, retry and a dead-letter store;
- a **benchmark** against `DelayQueue`, `ScheduledThreadPoolExecutor`, Netty's `HashedWheelTimer` and a
  Redis ZSet.

Phase 1 delivers all of that except the Redis measurement. Phase 2 is optimisation, driven by what
Phase 1 measured.

## 2. What was delivered

| Step | Delivered | Main verification |
|---|---|---|
| 1 | single-level wheel, bucket lists, bucket-driven `DelayQueue`, cancel | unit tests; deterministic timer tests on a manual clock; real-clock lifecycle tests |
| 2 | hierarchical wheel (levels created on demand, demotion) | demotion trace test; randomised test over four levels |
| 3 | `TaskExecutor`, thread-pool and Disruptor implementations | one contract test class run against both |
| 4 | `TaskRecord`, `TaskStore` (in-memory and RocksDB), `DurableScheduler`, crash recovery | contract tests incl. close-and-reopen; "crash" tests with a second scheduler on the same store |
| 5 | acknowledgement, retry with backoff, dead-letter store, re-offer of refused tasks, strict cancel | scheduler tests for each rule; atomic-move tests |
| 6 | benchmark project: JMH, load test, memory test, run scripts | the process in section 4 |

CI runs the build and all tests on Linux and Windows with JDK 17 and 21. There are well over a
hundred tests; the exact number changes as the project grows, so it is not quoted.

## 3. Engineering decisions worth remembering

Each of these was made, or corrected, because a test or a measurement forced it
(the full table is in [`DESIGN.md`](DESIGN.md)).

- **Level 1 rounds up, coarse levels round down.** A timeout must never fire early.
- **Level 2's bucket width is `(n−1)·tick`.** The obvious `n·tick` makes a task in a one-tick gap
  bounce between level 2 and level 1 forever. This was found by reasoning about the invariant
  "a coarse bucket starts strictly after the level-1 time", and the demotion trace test protects it.
- **Persist before arming, remove after the handler returns.** The ordering of those few operations
  is what makes recovery safe; section 6 of the design document lists the effect of a crash at each
  point.
- **A refusal by a full executor is not a failed attempt.** Otherwise a burst of overdue tasks
  after an outage would dead-letter good tasks.
- **The attempt count is persisted with the retry.** Otherwise a poison task retries forever by
  crashing the process.
- **`cancel` re-checks the record before starting the handler.** This removed the earlier rule
  "call cancel only after schedule has returned".
- **Deadlines in the store are wall-clock**, because the wheel's monotonic clock is meaningless
  after a restart.

## 4. Benchmark process and what it taught

The benchmark was not a single run. It went through rounds, and several early conclusions were
**wrong** and were corrected. This is the record.

### 4.1 Hypothesis ledger

| # | Claim | Evidence | Verdict |
|---|---|---|---|
| 1 | The wheel's schedule/cancel cost does not grow with the number of pending timers | JMH, 1 000 → 1 000 000 pending | **Supported**: +13 % from 100 k to 1 M, against +57 % for the heap |
| 2 | …so the wheel is faster than a heap | JMH, one call | **Not supported at these sizes**: one call is about 2× the heap's cost and 2.5× Netty's |
| 3 | …but the wheel is cheaper under a real load | load test, 350 k pending | **Supported**: 0.90 µs/task against 1.13 (`DelayQueue`) and 1.50 (`ScheduledThreadPoolExecutor`) |
| 4 | Allocation is why a call is slow | `-prof gc` | **Refuted**: 88 B/op against 96 (heap) and 80 (Netty) |
| 5 | Synchronisation and arithmetic explain the cost | component benchmark | **Supported**: parts add up to 186 ns of the measured 191 ns (about half synchronisation and clock, a third division) |
| 6 | One huge coarse bucket flushed under the write lock causes the tail latency | single-level wheel experiment | **Supported**: p99 6.17 → 1.05 ms, CPU −12 % |
| 7 | The wheel uses 3× Netty's CPU | first Windows round | **Refuted** by the clean Linux round: 1.1–1.3× |
| 8 | Netty has the best latency | first Windows round | **Depends on the OS**: on Linux the heap structures have p99 0.02 ms; Netty has the best p99.9 |
| 9 | The Disruptor beats the thread pool | load test | **Modest**: about 11 % less CPU, slightly lower tail, with empty tasks |
| 10 | Cancelled timers release memory | memory test | **Supported** against `DelayQueue` (it keeps 100 %); Netty and the wheel keep 0 % |
| 11 | More producer threads give more throughput | JMH, 4 threads | **No candidate scales**; the wheel keeps the largest share (0.78×) |

### 4.2 Mistakes in the measurement itself

These cost most of the calendar time and are the main lesson of this phase.

1. **The Windows timer tick is 15.6 ms.** `LockSupport.parkNanos`, which `DelayQueue`,
   `ScheduledThreadPoolExecutor` and this wheel wait with, was quantised to it. Netty waits with
   `Thread.sleep`, which may be why its latency looked different there (not verified). The first
   latency comparison therefore measured the operating system. A probe
   (`TimerResolutionProbe`) made this visible, and the JVM flag `-XX:+ForceTimeHighResolution` did
   not change it. The latency comparison was moved to Linux.
2. **The load generator was part of the load.** Producers parked for 50 µs when idle, which is
   harmless where parking is coarse (Windows) and costs about 2.5 µs of CPU per timer where it is
   precise (Linux). It hid every difference between candidates. The `noop` candidate exists to catch
   this, and the fix (park about 1 ms) took its cost from 2.46 to 0.15 µs.
3. **Memory against an absolute baseline.** The used-heap reading right after a GC carries a
   collector-dependent constant offset; a run with one timer could read 5 MB below its own baseline.
   Memory is now measured as a **difference** between GC'd checkpoints.
4. **The JIT deleted my "keep this array alive" check.** `handles.length == -1` is provably false and
   was removed, so the array was collected and the numbers were negative. `Reference.reachabilityFence`
   is the correct tool.
5. **A baseline that cannot be run in steady state.** The lazily cancelling `DelayQueue` leaves a
   tombstone per schedule+cancel, so its JMH result is unstable. Its cost is shown by the memory
   test instead.
6. **Single runs were over-interpreted.** A single Windows run suggested the single-level wheel used
   37 % more CPU; three clean runs show 12 % less.
7. **Producer count matters.** With 4 producers on a 4-core laptop the wheel's p99 was 30 ms; with 2
   it is 6 ms. Results are only comparable at equal conditions, which is why the scripts record them.

### 4.3 What the final numbers say

See [`benchmarks/RESULTS.md`](../benchmarks/RESULTS.md). In short, on one 15 W laptop at 200 000
timers/s with 350 000 pending:

- sized well, the wheel costs about 13 % more CPU than Netty and about 30 % less than `DelayQueue`;
- its p99 lateness is about 1 ms (sized well) against 0.02 ms for the heap structures, because its
  resolution is 1 ms;
- it uses 112 B per pending timer against 62 for a plain `DelayQueue`, but returns it on cancel;
- a single schedule+cancel call is slower than the alternatives, and no candidate scales with the
  number of producer threads.

It is **not** the fastest timer in every respect, and the report does not claim it is. Its
distinguishing property is the combination with the durable layer, which none of the baselines
offer.

## 5. Known limitations

1. **Resolution is 1 ms**; a task may run less than 1 ms before its exact-nanosecond deadline.
2. **Coarse-bucket stall.** When delays exceed level 1's span, timers pass through level 2 and are
   flushed together, in one pass under the write lock, when their coarse bucket is due. The pass
   grows with the arrival rate × the bucket width. At 200 000/s it moves p99 from 1 ms to 6 ms; at
   1 000 000/s it was **not measured**, and a linear extrapolation says it is about five times worse.
3. **A call costs about twice the heap's and 2.5× Netty's**; 112 B per pending timer.
4. **No scaling with producer threads.**
5. **At-least-once, not exactly-once.** Handlers must be idempotent.
6. **Durable deadlines use the wall clock**, so a clock jump shifts them.
7. **One process owns a RocksDB store**; there is no replication or failover. "Crash recovery" is
   delivered, "high availability across nodes" is not.
8. **Re-offer of refused tasks is periodic** (about 0.2–0.3 s), not driven by free space in the executor.
9. **Evidence is from one machine**, tasks do no work, and the Redis candidate was never run.

## 6. Phase 2 plan

Ordered by evidence and by how well it serves the stated use case (many timers per second, long
delays). The targets are **proposals**, to be confirmed or replaced by the first measurement.

| # | Task | Evidence | How to check | Proposed target |
|---|---|---|---|---|
| 1 | **Bounded flush**: flush a coarse bucket in batches and look at finer due buckets in between, so a big bucket cannot hold up everything else | limitation 2; p99 6.17 vs 1.05 ms | default config at 200 k/s and at **1 M/s** (`--rate=1000000`), before and after | default-config p99 within about 2× of the single-level figure; no stall that grows with the rate |
| 2 | **Fill the measurement gaps**: 1 M/s, the Redis candidate, tasks that do work (e.g. 100 µs of CPU), a GC log | section 4 of `RESULTS.md` | extend `run-round2.sh` | published numbers, including the unflattering ones |
| 3 | **Cheaper schedule path**: merge the two nested `synchronized` per operation (≈26 ns), cheaper level arithmetic (up to ≈60 ns of divisions) | component benchmark | `SchedulingCostBenchmark` and `ComponentCostBenchmark` before and after | single call ≤ 130 ns at 100 k pending (from 200) |
| 4 | **Smaller footprint**: fold the task state into `TimerTask` without an `AtomicInteger` (−16 B), consider merging task and entry | 112 B vs 62 B | memory test | ≤ 80 B per pending timer |
| 5 | **Where does the rest of the per-task CPU go?** (0.77–0.90 µs per task, of which the schedule call is a fraction) | not yet profiled | JFR on the load test (`jfr view hot-methods`, `contention-by-site`) | a ranked list that decides item 6 |
| 6 | **Producer hand-off prototype**: producers only enqueue to a lock-free queue and the driver owns the wheel (Netty's model) | Netty's 0.68 µs and better scaling | prototype behind the same `TimingWheelTimer` API, compare | decide on data; it changes the meaning of `cancel` and `size()`, so it must not be a silent change |
| 7 | **Sizing**: derive the wheel size from the expected maximum delay, with documented guidance | the 512 vs 4096 experiment | the same two configurations | one-line configuration that avoids the 2-level penalty for common delays |
| 8 | **CI for the benchmark module** (compile only) | the module is outside the main build | the PR check | a red build if the benchmarks stop compiling |
| 9 | **Durable layer follow-ups**: re-offer driven by free space; optional replication is out of scope for Phase 2 | limitations 7, 8 | tests first | |

The working method for Phase 2 is the one that worked in Phase 1: **state the hypothesis, measure
before changing code, change one thing, measure again**, and append the result to
[`benchmarks/RESULTS.md`](../benchmarks/RESULTS.md) with the same conditions.

## 7. Phase 1 exit checklist

- [x] Steps 1–5 merged; step 6 (benchmarks) pushed on the `step-6-benchmarks` branch
- [x] Tests green in CI on the full matrix (confirm on the pull request)
- [x] Benchmark data and conditions published in the repository
- [x] Limitations written down, including those that make the result look worse
- [ ] Merge the step-6 pull request and tag `v0.1.0`
- [ ] Redis baseline measured (moved to Phase 2, item 2)

## 8. Reproducing

Build and test: `mvn verify`. Benchmarks: see section 5 of [`benchmarks/RESULTS.md`](../benchmarks/RESULTS.md)
and [`benchmarks/README.md`](../benchmarks/README.md). The crash-recovery demo is
`io.timewheel.demo.CrashRecoveryDemo` (run it with `schedule`, kill the process, run it with `recover`).
