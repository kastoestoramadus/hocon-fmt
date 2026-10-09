#!/usr/bin/env bash
# Runs scripts/check-release-version.sh against fixture trees, not this checkout, so the wave
# split it enforces is exercised whatever versions the working tree itself carries. The cases
# mirror release.yml: RELEASE_WAVE unset is a wave-1 tag, RELEASE_WAVE=2 the full-release check.
set -euo pipefail

repo=$(cd "$(dirname "$0")/.." && pwd)
script="$repo/scripts/check-release-version.sh"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
failed=0

tree=

sbt_v= api_v= gradle_v= mill_v= maven_v= hooks_v=
fresh() { sbt_v=$1 api_v=$1 gradle_v=$1 mill_v=$1 maven_v=$1 hooks_v=$1; }

put() { printf '%b\n' "$2" > "$tree/$1"; }

write_tree() {
  tree="$work/fixture"
  rm -rf "$tree"
  mkdir -p "$tree/scripts" "$tree/java-api" "$tree/gradle-plugin" "$tree/maven-plugin" "$tree/mill-plugin"
  cp "$script" "$tree/scripts/"
  put build.sbt                      "ThisBuild / version      := \"$sbt_v\""
  put java-api/build.gradle.kts      "version = \"$api_v\""
  put gradle-plugin/build.gradle.kts "version = \"$gradle_v\""
  put mill-plugin/build.mill         "  def formatterVersion = \"$mill_v\""
  put maven-plugin/pom.xml           "<version>$maven_v</version>\n<version>$maven_v</version>"
  put .pre-commit-hooks.yaml         "additional_dependencies: [\"hocon-fmt==$hooks_v\"]\nadditional_dependencies: [\"hocon-fmt==$hooks_v\"]\nadditional_dependencies: [\"hocon-fmt@$hooks_v\"]\nadditional_dependencies: [\"hocon-fmt@$hooks_v\"]"
}

expect_run() { # name, expected exit status, pattern the output must carry ('-' = none), command
  local name="$1" want="$2" pattern="$3"
  shift 3
  local out status
  out=$("$@" 2>&1) && status=0 || status=$?
  if [ "$status" -ne "$want" ]; then
    echo "FAIL $name: exit $status, wanted $want" >&2
    echo "$out" >&2
    failed=1
  elif [ "$pattern" != "-" ] && ! grep -q -- "$pattern" <<< "$out"; then
    echo "FAIL $name: output carries no '$pattern':" >&2
    echo "$out" >&2
    failed=1
  else
    echo "ok   $name"
  fi
}

good=1.2.3
stale=0.1.0-SNAPSHOT

fresh "$good"; write_tree
expect_run 'wave 1 passes when all six places agree' 0 '-' \
  "$tree/scripts/check-release-version.sh" "$good"

fresh "$good"; gradle_v=$stale mill_v=$stale maven_v=$stale hooks_v=$stale; write_tree
expect_run 'wave 1 tolerates stale wave-2 places' 0 '-' \
  "$tree/scripts/check-release-version.sh" "$good"

fresh "$good"; sbt_v=$stale; write_tree
expect_run 'wave 1 fails on a build.sbt mismatch' 1 '^build\.sbt:' \
  "$tree/scripts/check-release-version.sh" "$good"

fresh "$good"; write_tree
expect_run 'wave 2 passes when all six places agree' 0 '-' \
  env RELEASE_WAVE=2 "$tree/scripts/check-release-version.sh" "$good"

fresh "$good"; hooks_v=$stale; write_tree
expect_run 'wave 2 still fails on stale hook pins' 1 '^\.pre-commit-hooks\.yaml:' \
  env RELEASE_WAVE=2 "$tree/scripts/check-release-version.sh" "$good"

fresh "$good"; write_tree
expect_run 'an unknown wave fails loudly' 2 'RELEASE_WAVE' \
  env RELEASE_WAVE=banana "$tree/scripts/check-release-version.sh" "$good"

if [ "$failed" -eq 0 ]; then
  echo 'check-release-version: every case passes'
else
  echo 'check-release-version: FAILED'
fi
exit "$failed"
