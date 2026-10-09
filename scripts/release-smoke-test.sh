#!/usr/bin/env bash
# Run the native smoke step from release.yml against a linked binary, in a disposable directory.
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd)
binary=$(realpath "${1:-$repo/cli/.native/target/scala-3.8.2/hocon-fmt}")
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/cli/.native/target/scala-3.8.2"
ln -s "$binary" "$work/cli/.native/target/scala-3.8.2/hocon-fmt"
awk '
  /^      - name: Smoke test$/         { armed = 1; next }
  armed && $0 == "        run: |"     { on = 1; armed = 0; next }
  on && /^          /                 { sub(/^          /, ""); print; next }
  on                                 { exit }
' "$repo/.github/workflows/release.yml" > "$work/smoke.sh"
test -s "$work/smoke.sh"
(cd "$work" && bash -euo pipefail smoke.sh)
echo 'release native smoke test: OK'
