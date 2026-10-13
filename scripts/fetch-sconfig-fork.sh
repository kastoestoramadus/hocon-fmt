#!/usr/bin/env bash
# UPSTREAM-SCONFIG: temporary. Once ekrich/sconfig releases setKeepDetachedComments
# (#646, PR #647), delete this script, the `checkSconfigFork` task and `coreSite` in build.sbt,
# and the script's steps in .github/workflows; docs/site.md "Returning to upstream sconfig"
# lists every place (`git grep UPSTREAM-SCONFIG`).
#
# Publishes the sconfig fork the project page runs on, to the local Ivy repository: Scala.js,
# Scala 3, version 2.1.0-hocon-fmt-<sha10>. The fork is kastoestoramadus/sHOCON, tag
# fork-main-727eb3074a, pinned to the sha below so a build is reproducible. Needs git and sbt.
# Run it once per sha (it skips when the artifact is already published), before `sbt test`.
set -euo pipefail

SHA=727eb3074aac668362b51aa576e4c3db367f88bb
SHORT=${SHA:0:10}
VERSION="2.1.0-hocon-fmt-$SHORT"
CACHE=${HOCON_FMT_FORK_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/hocon-fmt/sconfig-fork-$SHORT}
IVY=$HOME/.ivy2/local/org.ekrich/sconfig_sjs1_3/$VERSION

if [ -d "$IVY" ]; then
  echo "sconfig fork $VERSION is already published: $IVY"
  exit 0
fi

if [ ! -d "$CACHE/.git" ]; then
  mkdir -p "$CACHE"
  git -C "$CACHE" init -q
  git -C "$CACHE" remote add origin https://github.com/kastoestoramadus/sHOCON.git
fi
git -C "$CACHE" fetch -q --depth 1 origin "$SHA"
git -C "$CACHE" checkout -q --detach FETCH_HEAD

# The fork's own build is sbt 2; ++3.9.0! pins the Scala version hocon-fmt compiles with.
(cd "$CACHE" && sbt -batch "set ThisBuild / version := \"$VERSION\"; ++3.9.0!; sconfigJS/publishLocal")
echo "published sconfig fork $VERSION"
