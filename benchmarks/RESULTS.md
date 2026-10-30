# Benchmark results

Everything here was measured on **one machine**, and the numbers describe **this machine**. They are
for comparing the candidates with each other under identical conditions, not for predicting your
production numbers. Raw data is in [`docs/results/linux-i7-8665u/`](docs/results/linux-i7-8665u/).

| | |
|---|---|
| CPU | Intel Core i7-8665U (4 cores / 8 threads, 15 W laptop part), turbo enabled |
| Memory / OS | 16 GB, Linux (Ubuntu), `performance` governor, AC power |
| JVM | OpenJDK 21.0.12.1, G1, `-Xms4g -Xmx4g` |
| Load | 200 000 timers/s for 20 s (4 M timers), delays uniform in 500–3000 ms, about 350 000 pending at once, 2 producer threads, tasks run inline on the timer's thread |
| Statistics | every load figure is the **median of 3 runs** with (min–max) in brackets; each candidate runs in its own JVM |

Zero timers were lost and zero ran more than 1 ms early, for every candidate in every run. All
candidates sustained the 200 000/s target.

## 1. Load test: CPU and lateness

CPU is process CPU divided by timers scheduled, **minus the load generator's own 0.15 µs/timer**
(the `noop` candidate), so it is the cost of the timer. Lateness is `actual run time − deadline` in
milliseconds; a negative value means the task ran early, which the wheel's 1 ms clock allows by
less than 1 ms.

| Candidate | CPU net of load generator (µs/task) | p50 | p99 | p99.9 | max | GC (ms) |
|---|---|---|---|---|---|---|
| Netty `HashedWheelTimer` | 0.68 (0.67–0.69) | 1.05 (1.05–1.05) | 2.07 (2.07–2.07) | 2.20 (2.20–2.20) | 15.4 (15.0–15.9) | 13 |
| **This wheel, single level (4096 buckets)** | 0.77 (0.76–0.78) | 0.02 (0.02–0.02) | 1.05 (1.05–1.05) | 7.70 (7.19–8.22) | 18.3 (17.8–18.7) | 34 |
| This wheel, default (512 buckets, 2 levels here) | 0.90 (0.89–0.92) | 0.09 (0.02–0.09) | 6.17 (5.91–6.42) | 13.34 (12.82–18.46) | 24.4 (22.7–32.8) | 47 |
| `DelayQueue` + 1 consumer, lazy cancel | 1.13 (1.11–1.15) | 0.02 (0.02–0.02) | 0.02 (0.02–0.02) | 5.14 (4.89–5.66) | 21.2 (21.1–22.5) | 21 |
| This wheel + Disruptor executor | 1.38 (1.38–1.39) | 0.09 (0.09–0.09) | 8.22 (7.70–8.22) | 15.38 (13.85–15.38) | 25.9 (24.7–26.8) | 50 |
| `ScheduledThreadPoolExecutor(1)` | 1.50 (1.49–1.50) | 0.02 (0.02–0.02) | 0.02 (0.02–0.02) | 15.38 (15.38–15.38) | 25.1 (24.9–25.9) | 44 |
| This wheel + bounded thread pool | 1.56 (1.52–1.58) | 0.09 (0.09–0.09) | 8.73 (8.73–8.73) | 17.43 (16.41–17.43) | 26.2 (23.3–26.3) | 71 |

How to read it:

- **CPU.** The wheel is cheaper than the heap-based `DelayQueue` and `ScheduledThreadPoolExecutor`
  and about 14 % above Netty when sized well. Handing tasks to another thread costs more than
  running them inline (the tasks here do no work, so this is the pure hand-off cost).
- **Lateness.** `DelayQueue` and `ScheduledThreadPoolExecutor` wake at nanosecond precision, so
  their p99 is 0.02 ms. The wheel has a 1 ms resolution, so even at its best
  p99 is about 1.0 ms. Netty is typically 1–2 ms late (its 1 ms tick) but has the best p99.9.
- **The 512-bucket default is worse than the 4096-bucket configuration** (p99 6.17 → 1.05 ms,
  CPU −12 %). With 1 ms buckets, 512 of them cover only 0.5 s, so a 0.5–3 s delay lands on the
  second level and is later demoted into the first, and one coarse bucket holds ~100 000 timers
  that are flushed together while everything else waits. See [Known limitations](../README.md#known-limitations).
- The maximum of every candidate (15–26 ms) is consistent with garbage-collection pauses of the
  4 GB G1 heap, but no GC log was collected, so this is a likely explanation and not a verified one.
- The spread over the three runs is small, but runs were back to back on one machine, so low
  variance is not proof of the absence of a systematic error.

## 2. Memory

Measured by differences between GC'd checkpoints (never against an absolute baseline), 1 000 000
timers 10–20 minutes out, then 90 % cancelled.

| Candidate | Heap per scheduled task | Still held per cancelled task (90 % cancelled) |
|---|---|---|
| `DelayQueue`, lazy cancel | 62 B | 62 B (100 %) |
| Netty | 99 B | 0 B (0 %) |
| This wheel | 112 B | 0 B (0 %) |
| `ScheduledThreadPoolExecutor` | 126 B | 6 B (5 %) |

The wheel needs more memory per pending timer than a plain `DelayQueue` (a `TimerTask` with an
`AtomicInteger`, plus a list entry), but gives it back when a timer is cancelled; the lazily
cancelling `DelayQueue` keeps every cancelled timer until its original deadline.

## 3. Cost of one schedule + cancel (JMH)

`SchedulingCostBenchmark`: fill the timer with N timers 1–2 hours out, then time "schedule one
more and cancel it" (ns per operation, single thread, calling thread only).

| Candidate | 1 000 pending | 100 000 pending | 1 000 000 pending | growth 100 k → 1 M |
|---|---|---|---|---|
| This wheel | 191 ± 26 | 200 ± 31 | 226 ± 43 | +13 % |
| `ScheduledThreadPoolExecutor` | 102 ± 19 | 93 ± 10 | 146 ± 10 | +57 % |
| Netty | 85 ± 11 | 80 ± 29 | 74 ± 15 | -8 % |

- **The wheel's cost barely depends on N** (it grows 13 % over a 10× increase) while the heap's grows more
  (`ScheduledThreadPoolExecutor`: 57 %). This is the O(1) versus O(log N) effect, but at these sizes it is
  **not enough to overcome the wheel's higher constant**: one call is about 2.1× the heap's and 2.5× Netty's.
- The `DelayQueue` rows are omitted: its lazy cancellation leaves a tombstone per operation, the
  queue grows during the run, and the results are unstable (errors larger than the values). The
  memory test shows that cost instead.
- Netty with 4 threads at 1 000 000 pending produced one outlier (5 489 ± 45 288 ns, 4 iterations)
  that is excluded from the table below.

Producer threads, at 100 000 pending:

| Candidate | 1 thread | 4 threads (total) | 4-thread total ÷ 1-thread |
|---|---|---|---|
| This wheel | 5.0 M/s | 3.9 M/s | 0.78× |
| `ScheduledThreadPoolExecutor` | 10.7 M/s | 4.9 M/s | 0.46× |
| Netty | 12.4 M/s | 8.2 M/s | 0.66× |

**No candidate scales with the number of producer threads**; all of them contend on shared
structures. The wheel keeps the largest share of its single-thread throughput (0.78× against 0.66× for Netty and 0.46× for `ScheduledThreadPoolExecutor`), but it starts lower.

### Where the wheel's 191 ns go

`ComponentCostBenchmark` times the building blocks separately; adding them up as they occur in one
schedule + cancel accounts for nearly all of the measured cost:

| Part of one schedule + cancel | Cost | Count | Subtotal |
|---|---|---|---|
| `System.nanoTime()` (timer clock) | 14.6 ns | 1 | 15 ns |
| read lock + unlock | 14.2 ns | 1 | 14 ns |
| two nested `synchronized` (insert, then removal) | 26.3 ns | 2 | 53 ns |
| atomics: counter up/down 9.3, bucket expiration 5.3, task state ≈5 | | | 20 ns |
| 64-bit division by a runtime value | 7.5 ns | 8 | 60 ns |
| allocation (88 B) and the rest | not measured | | ≈25 ns |
| **Predicted** | | | **≈186 ns** |
| **Measured** (JMH, 1 000 pending) | | | **191 ns** |

Roughly half is synchronisation and clock reads, about a third is integer division (the wheel
divides by runtime values on every level it visits, and it cannot use shifts because level 2 has
a bucket width of `(n−1)·tick`), and little is allocation. **Allocation was tested and rejected as
the cause**: `-prof gc` showed 88 B/op for the wheel against 96 B/op for `ScheduledThreadPoolExecutor`
and 80 B/op for Netty (measured in the first round; allocation per operation does not depend on the operating system).

## 4. What was not measured

- **Redis ZSet** is implemented as a candidate (`redis`) but was not run: it needs a Redis server.
- **One million timers per second** (`--rate=1000000`) was not run; the 200 000/s result does not
  tell what the 2-level stall does at 5× the arrival rate.
- **Tasks that do real work.** The tasks are empty, which maximises the visible cost of the timer
  and of the executor hand-off, and understates how little either matters when tasks take
  milliseconds.
- **Other hardware, other JVMs, other collectors.**

## 5. Reproducing

```
mvn -q install -DskipTests                  # repository root
cd benchmarks && mvn -q package
sudo cpupower frequency-set -g performance  # Linux; keep the machine otherwise idle, on AC power
./run-round2.sh                             # ~25 min, writes round2.tgz
java -jar target/benchmarks.jar SchedulingCostBenchmark        # JMH, add -t 4 for 4 threads
java -jar target/benchmarks.jar ComponentCostBenchmark
```

Look at the `noop` row first: it must be far below the candidates (here 0.15 µs). The method and
its pitfalls are written up in [`benchmarks/README.md`](README.md) and in
[`docs/PHASE1-REPORT.md`](../docs/PHASE1-REPORT.md).
