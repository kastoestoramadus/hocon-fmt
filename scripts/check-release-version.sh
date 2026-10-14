#!/usr/bin/env bash
# Fails unless every place that carries the version holds $1, so a tag cannot release a build that
# still says -SNAPSHOT or names another version in one of its plugins. See docs/releasing.md.
# The workflow passes its canonical selection; by hand the JVM/plugin defaults apply.
set -euo pipefail

version="$1"
targets="${RELEASE_TARGETS:-core,sbt-plugin,maven-plugin,gradle-plugin}"
re="${version//./\\.}"
root="$(cd "$(dirname "$0")/.." && pwd)"
status=0

selected() { [[ ",$targets," == *",$1,"* ]]; }
IFS=',' read -ra names <<< "$targets"
for name in "${names[@]}"; do
  case "$name" in
    core|sbt-plugin|maven-plugin|gradle-plugin|mill-plugin|native|npm|pypi) ;;
    *) echo "Unknown release target: $name" >&2; exit 2 ;;
  esac
done

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

# Every target derives its package version from sbt; the Java API's independent build
# needs to agree only when it is part of the selected core deployment.
expect build.sbt "^ThisBuild / version +:= \"$re\"$" 1
if selected core; then
  expect java-api/build.gradle.kts "^version = \"$re\"$" 1
fi
if selected gradle-plugin; then
  expect gradle-plugin/build.gradle.kts "^version = \"$re\"$" 1
fi
if selected maven-plugin; then
  expect maven-plugin/pom.xml "<version>$re</version>" 2
fi
if selected mill-plugin; then
  expect mill-plugin/build.mill "formatterVersion = \"$re\"$" 1
fi
if selected npm; then
  expect .pre-commit-hooks.yaml "hocon-fmt@$re\"" 2
fi
if selected pypi; then
  expect .pre-commit-hooks.yaml "hocon-fmt==$re\"" 2
fi

exit $status
