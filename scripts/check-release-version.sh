#!/usr/bin/env bash
# Fails unless every place that carries the version holds $1, so a tag cannot release a build that
# still says -SNAPSHOT or names another version in one of its plugins. See docs/releasing.md.
#
# Wave 1 checks the sbt artifacts, the Java API's Gradle build, and both standalone plugins.
# Wave 2 also checks Mill and the pre-commit hooks. release.yml passes its repository variable;
# by hand the default is wave 1. All six version locations are listed in docs/releasing.md.
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
  # A place that is not there cannot be scanned: `grep` prints nothing to a command substitution
  # then, and the empty operand makes `test`'s usage error read as a false condition, so the tag
  # would pass with the place never checked.
  if [ ! -f "$root/$1" ]; then
    echo "$1: no such file" >&2
    status=1
    return 0
  fi
  found=$(grep -Ec -- "$2" "$root/$1" || true)
  found=${found:-0}
  if [ "$found" -lt "$3" ]; then
    echo "$1: expected at least $3 match(es) of $2, found $found" >&2
    status=1
  fi
}

expect build.sbt                 "^ThisBuild / version +:= \"$re\"$" 1
expect java-api/build.gradle.kts "^version = \"$re\"$"               1

expect gradle-plugin/build.gradle.kts "^version = \"$re\"$"      1
expect maven-plugin/pom.xml           "<version>$re</version>"   2

if [ "$wave" = 2 ]; then
  expect mill-plugin/build.mill         "formatterVersion = \"$re\"$" 1
  expect .pre-commit-hooks.yaml         "hocon-fmt(==|@)$re\""     4
fi

exit $status
