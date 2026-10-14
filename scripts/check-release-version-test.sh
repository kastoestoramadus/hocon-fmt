#!/usr/bin/env bash
# Runs scripts/check-release-version.sh against fixture trees, not this checkout, so the selection
# contract it enforces is exercised whatever versions the working tree itself carries. The cases
# mirror release.yml: default targets cover JVM plugins; selecting hooks checks their pins.
set -euo pipefail

repo=$(cd "$(dirname "$0")/.." && pwd)
script="$repo/scripts/check-release-version.sh"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
failed=0

tree=

sbt_v="" api_v="" gradle_v="" mill_v="" maven_v="" hooks_v="" drop=""
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
  # A place the wave checks can be missing; the check must fail, not read "not there" as "fine".
  if [ -n "$drop" ]; then rm -f "$tree/$drop"; fi
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
expect_run 'default targets passes when all six places agree' 0 '-' \
  "$tree/scripts/check-release-version.sh" "$good"

fresh "$good"; mill_v=$stale hooks_v=$stale; write_tree
expect_run 'default targets tolerates stale unselected places' 0 '-' \
  "$tree/scripts/check-release-version.sh" "$good"

fresh "$good"; gradle_v=$stale; write_tree
expect_run 'default targets fails on a Gradle plugin mismatch' 1 '^gradle-plugin/build\.gradle\.kts:' \
  "$tree/scripts/check-release-version.sh" "$good"

fresh "$good"; maven_v=$stale; write_tree
expect_run 'default targets fails on a Maven plugin mismatch' 1 '^maven-plugin/pom\.xml:' \
  "$tree/scripts/check-release-version.sh" "$good"

fresh "$good"; write_tree
put maven-plugin/pom.xml "<version>$good</version>\n<version>$stale</version>"
expect_run 'default targets fails on a Maven Java API dependency mismatch' 1 '^maven-plugin/pom\.xml:' \
  "$tree/scripts/check-release-version.sh" "$good"

fresh "$good"; drop=maven-plugin/pom.xml; write_tree; drop=""
expect_run 'default targets fails when the Maven plugin POM is missing' 1 '^maven-plugin/pom\.xml:' \
  "$tree/scripts/check-release-version.sh" "$good"

fresh "$good"; sbt_v=$stale; write_tree
expect_run 'default targets fails on a build.sbt mismatch' 1 '^build\.sbt:' \
  "$tree/scripts/check-release-version.sh" "$good"

fresh "$good"; drop=java-api/build.gradle.kts; write_tree; drop=""
expect_run 'default targets fails when a place it checks is missing' 1 '^java-api/build\.gradle\.kts:' \
  "$tree/scripts/check-release-version.sh" "$good"

fresh "$good"; write_tree
expect_run 'all targets passes when all six places agree' 0 '-' \
  env RELEASE_TARGETS=core,sbt-plugin,maven-plugin,gradle-plugin,mill-plugin,native,npm,pypi "$tree/scripts/check-release-version.sh" "$good"

fresh "$good"; drop=mill-plugin/build.mill; write_tree; drop=""
expect_run 'all targets fails when a place it checks is missing' 1 '^mill-plugin/build\.mill:' \
  env RELEASE_TARGETS=core,sbt-plugin,maven-plugin,gradle-plugin,mill-plugin,native,npm,pypi "$tree/scripts/check-release-version.sh" "$good"

fresh "$good"; hooks_v=$stale; write_tree
expect_run 'all targets still fails on stale hook pins' 1 '^\.pre-commit-hooks\.yaml:' \
  env RELEASE_TARGETS=core,sbt-plugin,maven-plugin,gradle-plugin,mill-plugin,native,npm,pypi "$tree/scripts/check-release-version.sh" "$good"

fresh "$good"; write_tree
expect_run 'an unknown target fails loudly' 2 'Unknown release target' \
  env RELEASE_TARGETS=banana "$tree/scripts/check-release-version.sh" "$good"

fresh "$good"; gradle_v=$stale maven_v=$stale mill_v=$stale hooks_v=$stale; write_tree
expect_run 'core-only ignores unselected channel versions' 0 '-' \
  env RELEASE_TARGETS=core "$tree/scripts/check-release-version.sh" "$good"

fresh "$good"; api_v=$stale gradle_v=$stale maven_v=$stale mill_v=$stale hooks_v=$stale; write_tree
expect_run 'native-only ignores all independent channel versions' 0 '-' \
  env RELEASE_TARGETS=native "$tree/scripts/check-release-version.sh" "$good"

fresh "$good"; mill_v=$stale hooks_v=$stale; write_tree
expect_run 'a selected Mill plugin version is checked' 1 '^mill-plugin/build\.mill:' \
  env RELEASE_TARGETS=mill-plugin "$tree/scripts/check-release-version.sh" "$good"

if [ "$failed" -eq 0 ]; then
  echo 'check-release-version: every case passes'
else
  echo 'check-release-version: FAILED'
fi
exit "$failed"
