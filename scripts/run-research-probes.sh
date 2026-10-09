#!/usr/bin/env bash
# Research observations, not a correctness gate: known bad output is deliberately recorded.
set -euo pipefail
REPO=$(cd "$(dirname "$0")/.." && pwd)
TASK_M2=${HOCON_RESEARCH_M2:-"$REPO/../m2"}
BUILD=$(mktemp -d)
trap 'rm -rf "$BUILD"' EXIT
cd "$REPO"
scripts/fetch-sconfig-fork.sh
XDG_RUNTIME_DIR="${HOCON_RESEARCH_RUNTIME:-/tmp}" sbt -Dsbt.boot.server=false \
  -Dmaven.repo.local="$TASK_M2" 'cliJVM/compile' 'show cliJVM/Compile/mainClass' 'export cliJVM/Runtime/fullClasspath' > "$BUILD/build.log" 2>&1 || {
  cat "$BUILD/build.log"; exit 1;
}
CLASSPATH=$(tail -1 "$BUILD/build.log")
HOCON_RESEARCH_MAIN_CLASS=$(sed -n 's/^\[info\] Some(\(.*\))$/\1/p' "$BUILD/build.log")
if [ -z "$HOCON_RESEARCH_MAIN_CLASS" ]; then
  cat "$BUILD/build.log"
  echo 'Could not read CLI main class from sbt' >&2
  exit 1
fi
export HOCON_RESEARCH_MAIN_CLASS
javac -cp "$CLASSPATH" -d "$BUILD" scripts/research-probes/Oracle.java
python3 scripts/research-probes/run.py "$CLASSPATH" "$BUILD" "$@"
