# 2026-10-09 — [#81](https://github.com/kastoestoramadus/hocon-fmt/pull/81) — Release wave 1 after review

**Change:** A review of #81 (deepseek-flash/max) ran every claim: actionlint 1.7.7 clean; the GitHub
docs put `vars` in `jobs.<job_id>.if` and in a step's `env`, so the switch works where it is used;
a wave-1 tag runs `central` alone (the version check in wave-1 mode, the key import, `signRelease`,
`sbt publishRelease`) while `native`, `npm`, `web` and `release` skip — a skipped need skips its
dependent, and `release`'s own condition is false, so nothing hangs; a wave-2 tag runs everything;
either dry run signs and uploads nothing. One real defect came out of it: `scripts/check-release-version.sh`
read a missing version place as fine — `grep` prints nothing to a command substitution when it
cannot read the file, and the empty operand made the test's usage error pass for a false condition.
It now names a missing place and fails, with two fixture cases (wave 1 and wave 2), and a place
whose count cannot be parsed no longer reaches the comparison. The workflow's wave-1 list names
`cats`, the adapter `hocon-fmt-cli_3`'s POM requires. PR #79 changes what wave 1 publishes; the
conflicts its merge would give are listed in the review's report, not resolved here.

**Look at again before a release:** the two wave-1 version places are `build.sbt` and
`java-api/build.gradle.kts`; a tag in wave 1 must still not fail on the four places wave 2 owns.
Merging #79 narrows the sbt deployment to the JVM set, so re-read the wave-1 wording in
`release.yml` against `releaseProjectIds` when it lands.
