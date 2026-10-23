#!/usr/bin/env bash
# UPSTREAM-SCONFIG: remove this bootstrap and checkSconfigFork when released sconfig
# includes ConfigDocument.format (#655) and setKeepDetachedComments (#646/#647).
# See docs/site.md "Returning to upstream sconfig" for the remaining seams.
#
# Publishes the pinned kastoestoramadus/sHOCON fork for Scala 3.9.0 on JVM, JS and
# Native, to local Ivy and Maven repositories. Needs git and sbt; run before builds.
set -euo pipefail

SHA=298311f25f723534fb5f10f98841a3ac8907dc8c
SHORT=${SHA:0:10}
VERSION="2.1.0-hocon-fmt-$SHORT"
REPOSITORY=${HOCON_FMT_FORK_REPOSITORY:-https://github.com/kastoestoramadus/sHOCON.git}
CACHE=${HOCON_FMT_FORK_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/hocon-fmt/sconfig-fork-$SHORT}
IVY_HOME=${HOCON_FMT_FORK_IVY_HOME:-$HOME/.ivy2}
M2=${HOCON_FMT_FORK_M2_REPOSITORY:-${MAVEN_REPO_LOCAL:-$HOME/.m2/repository}}
ARTIFACTS=(sconfig_3 sconfig_sjs1_3 sconfig_native0.5_3)

published() {
  local artifact ivy maven
  for artifact in "${ARTIFACTS[@]}"; do
    ivy="$IVY_HOME/local/org.ekrich/$artifact/$VERSION"
    maven="$M2/org/ekrich/$artifact/$VERSION"
    [ -s "$ivy/jars/$artifact.jar" ] && [ -s "$ivy/ivys/ivy.xml" ] &&
      [ -s "$maven/$artifact-$VERSION.jar" ] && [ -s "$maven/$artifact-$VERSION.pom" ] || return 1
  done
}

if published; then
  echo "sconfig fork $VERSION is already published to local Ivy and Maven repositories"
  exit 0
fi

if [ ! -d "$CACHE/.git" ]; then
  mkdir -p "$CACHE"
  git -C "$CACHE" init -q
fi
# Read the override even when reusing a source cache; never retain a local origin.
git -C "$CACHE" fetch -q --depth 1 "$REPOSITORY" "$SHA"
git -C "$CACHE" checkout -q --detach "$SHA"

# The fork uses sbt 2; publish the Scala version every formatter module compiles with.
# The sbt 2 launcher enters its thin client and joins command arguments with spaces,
# so the commands travel as one '; '-separated argument: it parses identically with
# and without the client.
commands="set ThisBuild / version := \"$VERSION\"; ++3.9.0!; sconfigJVM/publishLocal; sconfigJVM/publishM2; sconfigJS/publishLocal; sconfigJS/publishM2; sconfigNative/publishLocal; sconfigNative/publishM2"
(cd "$CACHE" && sbt -batch "-Dsbt.ivy.home=$IVY_HOME" "-Dmaven.repo.local=$M2" "$commands")
published || { echo "sconfig fork publication is incomplete" >&2; exit 1; }
echo "published sconfig fork $VERSION"
