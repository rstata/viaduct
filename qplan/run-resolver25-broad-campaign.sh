#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

start_round=${RESOLVER25_BROAD_CAMPAIGN_START:-1}
end_round=${RESOLVER25_BROAD_CAMPAIGN_END:-100}
report_dir=${RESOLVER25_BROAD_CAMPAIGN_REPORT_DIR:-"$PWD/build/reports/resolver25-broad-campaign"}
mkdir -p -- "$report_dir"

if (( $# > 0 )); then
  rounds=("$@")
else
  mapfile -t rounds < <(seq "$start_round" "$end_round")
fi

for round in "${rounds[@]}"; do
  if [[ ! $round =~ ^[0-9]+$ ]] || (( round < 1 || round > 100 )); then
    printf 'Campaign round must be an integer in 1..100: %s\n' "$round" >&2
    exit 2
  fi

  printf -v padded_round '%03d' "$round"
  log="$report_dir/round-$padded_round.log"
  printf 'Starting Resolver25 broad-stress campaign round %s (log: %s)\n' \
    "$round" "$log"

  if ! ./gradlew \
    :semantics:resolver25BroadStressCampaign \
    "-Presolver25BroadStressCampaignRound=$round" \
    --console=plain \
    --rerun-tasks \
    2>&1 | tee "$log"; then
    printf 'Round %s failed. Replay the whole round with:\n' "$round" >&2
    printf '  ./run-resolver25-broad-campaign.sh %s\n' "$round" >&2
    exit 1
  fi
done

printf 'Completed %s Resolver25 broad-stress campaign round(s). Logs: %s\n' \
  "${#rounds[@]}" "$report_dir"
