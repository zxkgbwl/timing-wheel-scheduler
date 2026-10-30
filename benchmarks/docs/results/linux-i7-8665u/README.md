# Raw data: Linux, Intel Core i7-8665U

Collected with `benchmarks/run-round2.sh` (load, memory, single-level wheel, component costs) and
with `java -jar target/benchmarks.jar SchedulingCostBenchmark [-t 4]` (JMH). See `conditions.txt`
for the machine and the settings (performance governor, AC power, turbo enabled, 2 producers).

| File | Contents |
|---|---|
| `conditions.txt` | CPU, memory, JDK, governor, power, load settings |
| `noop-check.txt` | cost of the load generator itself (must be far below the candidates) |
| `load-run{1,2,3}.csv` | one row per candidate per run: rate, lost, early, lateness percentiles, CPU, GC, heap |
| `memory-run{1,2,3}.csv` | bytes per scheduled task, bytes still held per cancelled task |
| `wheel-4096-runs.csv` | the wheel with one 4096-bucket level (3 runs), same columns as `load-run*.csv` |
| `jmh-schedule-cost-1thread.txt`, `jmh-schedule-cost-4threads.txt` | JMH: schedule+cancel with N pending timers |
| `jmh-components.txt` | JMH: cost of the building blocks (clock, locks, atomics, division) |

Notes on reading them:

- The two `jmh-schedule-cost-*` files come from an earlier session on the same machine, before the
  governor and power state were being recorded, so they are less controlled than the load, memory
  and component files, which come from the controlled round with `conditions.txt`. The JMH `delayqueue` rows are
  not usable (see `../../RESULTS.md`).
- Results from the first measurement round (Windows, and a first Linux round whose load generator
  woke every 50 us) are intentionally not published: they were invalidated, and why is described
  in `../../../docs/PHASE1-REPORT.md`.
