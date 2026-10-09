#!/usr/bin/env bash
# Fails unless every place that carries the version holds $1, so a tag cannot release a build that
# still says -SNAPSHOT or names another version in one of its plugins. See docs/releasing.md.
#
# RELEASE_WAVE narrows the check to the places the publishing wave touches. The first publish
# ships the sbt-built JVM artifacts only (wave 1), whose versions build.sbt carries and
# java-api/build.gradle.kts repeats for the same artifact's Gradle build; the other four places
# may still hold what pre-release iterations left. RELEASE_WAVE=2, the full release, checks all
# six again. release.yml passes its repository variable through; by hand the default is wave 1,
# what a tag runs today.
set -euo pipefail

version="$1"
wave="${RELEASE_WAVE:-1}"
re="${version//./\\.}"
root="$(cd "$(dirname "$0")/.." && pwd)"
status=0

case "$wave" in
  1 | 2) ;;
  *) echo "RELEASE_WAVE must be 1 or 2, got '$wave'" >&2; exit 2 ;;
esac

expect() { # file, pattern, minimum matches
  local found
  found=$(grep -Ec -- "$2" "$root/$1" || true)
  if [ "$found" -lt "$3" ]; then
    echo "$1: expected at least $3 match(es) of $2, found $found" >&2
    status=1
  fi
}

expect build.sbt                 "^ThisBuild / version +:= \"$re\"$" 1
expect java-api/build.gradle.kts "^version = \"$re\"$"               1

if [ "$wave" = 2 ]; then
  expect gradle-plugin/build.gradle.kts "^version = \"$re\"$"      1
  expect mill-plugin/build.mill         "formatterVersion = \"$re\"$" 1
  expect maven-plugin/pom.xml           "<version>$re</version>"   2
  expect .pre-commit-hooks.yaml         "hocon-fmt(==|@)$re\""     4
fi

exit $status
