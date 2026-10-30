#!/usr/bin/env bash
# Runs the load test and the memory probe for each candidate, ONE JVM PER CANDIDATE, and collects
# the CSV lines into results/load.csv and results/memory.csv.
#
#   ./run-all.sh                                   # defaults below
#   RATE=500000 SECONDS_RUN=30 IMPLS="wheel netty" ./run-all.sh
#   JAR=target/benchmarks.jar HEAP=8g ./run-all.sh
#
# Add "redis" to IMPLS only with a Redis running (docker run --rm -p 6379:6379 redis:7).
set -euo pipefail
cd "$(dirname "$0")"

JAR="${JAR:-target/benchmarks.jar}"
HEAP="${HEAP:-4g}"
RATE="${RATE:-200000}"
SECONDS_RUN="${SECONDS_RUN:-20}"
PRODUCERS="${PRODUCERS:-4}"
TASKS="${TASKS:-1000000}"
CANCEL_FRACTION="${CANCEL_FRACTION:-0.9}"
IMPLS="${IMPLS:-noop wheel wheel-pool wheel-disruptor delayqueue stpe netty}"
JVM_FLAGS="${JVM_FLAGS:--XX:+UseG1GC}"          # keep identical for every candidate

mkdir -p results
rm -f results/load-*.txt results/memory-*.txt

for impl in $IMPLS; do
  echo "=== load   : $impl"
  java -Xms"$HEAP" -Xmx"$HEAP" $JVM_FLAGS -cp "$JAR" io.timewheel.bench.LoadTest \
      --mode=load --impl="$impl" --rate="$RATE" --seconds="$SECONDS_RUN" --producers="$PRODUCERS" \
      | tee "results/load-$impl.txt"
  if [ "$impl" != "noop" ]; then
    echo "=== memory : $impl"
    java -Xms"$HEAP" -Xmx"$HEAP" $JVM_FLAGS -cp "$JAR" io.timewheel.bench.LoadTest \
        --mode=memory --impl="$impl" --tasks="$TASKS" --cancelFraction="$CANCEL_FRACTION" \
        | tee "results/memory-$impl.txt"
  fi
done

collect() {  # pattern, output
  local first
  first=$(ls results/$1-*.txt 2>/dev/null | head -n 1 || true)
  if [ -z "$first" ]; then
    echo "no $1 results to collect"
    return 0
  fi
  { grep -h '^CSV_HEADER,' "$first" | head -n 1 | sed 's/^CSV_HEADER,//'
    grep -h '^CSV,' results/$1-*.txt | sed 's/^CSV,//'; } > "$2"
}
collect load results/load.csv
collect memory results/memory.csv
echo; echo "wrote results/load.csv and results/memory.csv"
