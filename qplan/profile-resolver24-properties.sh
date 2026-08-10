#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

size=${RESOLVER24_PROFILE_SIZE:-50:2:5}
seed=${RESOLVER24_PROFILE_SEED:-20260810}
report_dir="$PWD/build/reports/resolver24-profile"
recording="$report_dir/resolver24-properties.jfr"

java_bin=$(readlink -f -- "$(command -v java)")
jfr="${java_bin%/java}/jfr"
if [[ ! -x "$jfr" ]]; then
  jfr=$(command -v jfr)
fi

./gradlew \
  :semantics:resolver24PropertyProfile \
  "-Presolver24ProfileSize=$size" \
  "-Presolver24ProfileSeed=$seed" \
  --console=plain

"$jfr" summary "$recording" >"$report_dir/summary.txt"
"$jfr" view --width 200 hot-methods "$recording" >"$report_dir/hot-methods.txt"
"$jfr" view --width 200 allocation-by-site "$recording" >"$report_dir/allocation-by-site.txt"
"$jfr" view --width 200 gc "$recording" >"$report_dir/gc.txt"
"$jfr" view --width 200 thread-allocation "$recording" >"$report_dir/thread-allocation.txt"
"$jfr" view --width 200 gc-pauses "$recording" >"$report_dir/gc-pauses.txt"

printf 'Resolver24 property profile complete (size=%s, seed=%s)\n' "$size" "$seed"
printf 'Recording: %s\n' "$recording"
printf 'Reports: %s\n' "$report_dir"
