#!/usr/bin/env bash
# One fixture, one CLI-produced byte sequence, every plugin's real build harness.
set -euo pipefail
cd "$(dirname "$0")/.."
repo=$PWD
m2=${MAVEN_REPO_LOCAL:-$repo/../m2}
fixture=$repo/test-fixtures/plugin-options
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT

# Scripted and invoker copy their projects into sandboxes. Keep their fixture snapshots exact.
for consumer in sbt-plugin/src/sbt-test/sbt-hocon-fmt/options maven-plugin/src/it/options mill-plugin/integration/resources/options-project; do
  for name in .hocon-fmt.conf input.conf expected.conf; do
    cmp "$fixture/$name" "$consumer/$name"
  done
done
cp "$fixture/.hocon-fmt.conf" "$scratch/.hocon-fmt.conf"
cp "$fixture/input.conf" "$scratch/app.conf"
scripts/fetch-sconfig-fork.sh
sbt -batch -J-Xmx5g "-Dmaven.repo.local=$m2" coreJVM/publishM2 javaApi/publishM2 \
  "cliJVM/run $scratch/app.conf"
cmp "$scratch/app.conf" "$fixture/expected.conf"
sbt -batch -J-Xmx5g "-Dmaven.repo.local=$m2" "sbtPlugin/scripted sbt-hocon-fmt/options"
(cd gradle-plugin && ./gradlew "-Dmaven.repo.local=$m2" functionalTest --tests '*repositoryStyleAndDuplicatesMatchTheCli')
(cd maven-plugin && ./mvnw "-Dmaven.repo.local=$m2" -Dinvoker.test=options,options-fail,options-override verify)
(cd mill-plugin && ./mill 'integration[__].testForked')
echo 'Plugin parity: CLI + sbt + Gradle + Maven + Mill passed with byte-identical output.'
