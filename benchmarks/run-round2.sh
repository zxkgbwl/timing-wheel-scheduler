#!/usr/bin/env bash
# Second measurement round, in one command. It records the conditions, checks that the load
# generator itself is cheap, runs the single-level wheel experiment, runs every candidate three
# times, runs the component benchmark, and packs everything into round2.tgz.
#
#   ./run-round2.sh                                  # about 20 to 25 minutes
#   PRODUCERS=4 RATE=500000 ./run-round2.sh
#   SKIP_JMH=1 JAR=/some/classes ./run-round2.sh     # for a dry run without the JMH jar
set -euo pipefail
cd "$(dirname "$0")"

JAR="${JAR:-target/benchmarks.jar}"
OUT="${OUT:-round2}"
HEAP="${HEAP:-4g}"
PRODUCERS="${PRODUCERS:-2}"          # a 4-core laptop cannot host 4 producers, the timer and the workers
RATE="${RATE:-200000}"
SECONDS_RUN="${SECONDS_RUN:-20}"
JVM_FLAGS="${JVM_FLAGS:--XX:+UseG1GC}"

load() {  # extra LoadTest arguments...
  java -Xms"$HEAP" -Xmx"$HEAP" $JVM_FLAGS -cp "$JAR" io.timewheel.bench.LoadTest \
      --mode=load --rate="$RATE" --seconds="$SECONDS_RUN" --producers="$PRODUCERS" "$@"
}

rm -rf "$OUT"; mkdir -p "$OUT"

echo "=== 1. conditions"
{
  lscpu | grep -E "Model name|^CPU\(s\)|Thread|Core|MHz" || true
  free -h | head -2
  java -version 2>&1 | head -1
  echo "cpu governor : $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo n/a)"
  echo "turbo off    : $(cat /sys/devices/system/cpu/intel_pstate/no_turbo 2>/dev/null || echo n/a)   (1 = turbo disabled)"
  echo "on AC power  : $(cat /sys/class/power_supply/AC*/online 2>/dev/null | head -1 || echo n/a)   (1 = yes)"
  echo "producers=$PRODUCERS rate=$RATE seconds=$SECONDS_RUN heap=$HEAP flags=$JVM_FLAGS"
} | tee "$OUT/conditions.txt"
if [ "$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo performance)" != "performance" ]; then
  echo "NOTE: the CPU governor is not 'performance'; results can drift between runs (sudo cpupower frequency-set -g performance)."
fi

echo; echo "=== 2. load generator cost (noop): must be well under 1 us per task"
load --impl=noop | tee "$OUT/noop-check.txt"
noop_cpu=$(grep -E '^CSV,' "$OUT/noop-check.txt" | awk -F, '{print $17}')
echo "noop cpu per task: $noop_cpu us"
awk -v v="$noop_cpu" 'BEGIN { if (v > 1.0) print "WARNING: the generator itself is expensive; CPU columns will not be comparable." }'

echo; echo "=== 3. single-level wheel (wheelSize=4096), three runs"
for i in 1 2 3; do
  load --impl=wheel --wheelSize=4096 --label=wheel-4096 | tee "$OUT/wheel4096-run$i.txt"
done

echo; echo "=== 4. all candidates, three rounds"
for i in 1 2 3; do
  PRODUCERS="$PRODUCERS" RATE="$RATE" SECONDS_RUN="$SECONDS_RUN" HEAP="$HEAP" JAR="$JAR" JVM_FLAGS="$JVM_FLAGS" ./run-all.sh
  rm -rf "$OUT/results-$i"; cp -r results "$OUT/results-$i"
done

if [ "${SKIP_JMH:-0}" != "1" ]; then
  echo; echo "=== 5. component costs"
  java -jar "$JAR" ComponentCostBenchmark | tee "$OUT/jmh-components.txt"
fi

tar czf "$OUT.tgz" "$OUT"
echo; echo "done: $(realpath "$OUT.tgz")"
