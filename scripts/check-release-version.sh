#!/usr/bin/env bash
# Fails unless every place that carries the version holds $1, so a tag cannot release a build that
# still says -SNAPSHOT or names another version in one of its plugins. See docs/releasing.md.
set -euo pipefail

version="$1"
re="${version//./\\.}"
root="$(cd "$(dirname "$0")/.." && pwd)"
status=0

expect() { # file, pattern, minimum matches
  local found
  found=$(grep -Ec -- "$2" "$root/$1" || true)
  if [ "$found" -lt "$3" ]; then
    echo "$1: expected at least $3 match(es) of $2, found $found" >&2
    status=1
  fi
}

expect build.sbt                      "^ThisBuild / version +:= \"$re\"$" 1
expect gradle-plugin/build.gradle.kts "^version = \"$re\"$"               1
expect mill-plugin/build.mill         "formatterVersion = \"$re\"$"       1
expect maven-plugin/pom.xml           "<version>$re</version>"            2
expect .pre-commit-hooks.yaml         "hocon-fmt(==|@)$re\""              4

exit $status
