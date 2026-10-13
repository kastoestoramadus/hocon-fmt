#!/usr/bin/env bash
# Exercise repeated Native GC activity on already-formatted service configurations.
set -euo pipefail

if (( $# < 1 || $# > 3 )); then
  echo "Usage: $0 <binary> [runs=200] [files=100]" >&2
  exit 2
fi
binary=$1
runs=${2:-200}
files=${3:-100}
for count in "$runs" "$files"; do
  if [[ ! $count =~ ^[1-9][0-9]*$ ]]; then
    echo "runs and files must be positive integers" >&2
    exit 2
  fi
done
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
# Avoid a core-dump handler turning a crash into a hung process.
ulimit -c 0
paths=()
for ((i = 1; i <= files; i++)); do
  path="$work/$i.conf"
  case $((i % 3)) in
    0) cat > "$path" <<'CONF'
# Service settings
include "defaults.conf"
service {
  name = billing
  port = 8080
}
db.url = "jdbc:postgresql://localhost/billing"
CONF
      ;;
    1) cat > "$path" <<'CONF'
# Worker settings
include "defaults.conf"
worker {
  name = invoices
  threads = 4
}
queue.url = "https://localhost/invoices"
CONF
      ;;
    2) cat > "$path" <<'CONF'
# Gateway settings
include "defaults.conf"
gateway {
  name = public
  port = 8443
}
upstream.url = "https://localhost/billing"
CONF
      ;;
  esac
  paths+=("$path")
done

declare -A histogram=()
failed=0
for ((i = 1; i <= runs; i++)); do
  code=0
  timeout -s KILL 20 "$binary" --check "${paths[@]}" > "$work/run.log" 2>&1 || code=$?
  histogram[$code]=$(( ${histogram[$code]:-0} + 1 ))
  if (( code != 0 )); then
    failed=1
    echo "Run $i exited $code" >&2
    cat "$work/run.log" >&2
  fi
done
printf 'Exit-code histogram (%s runs, %s files):\n' "$runs" "$files"
for code in $(printf '%s\n' "${!histogram[@]}" | sort -n); do
  printf '  %s: %s\n' "$code" "${histogram[$code]}"
done
exit "$failed"
