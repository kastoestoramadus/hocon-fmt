# 2026-10-08 — #51 — JVM coverage is measured, not gated

**Change:** JVM coverage is measured, not gated: `coverageJvm` (sbt-scoverage 2.4.4 on dotty's
`-coverage-out`) instruments core, cats, cli and zio; CI's non-required `coverage` job uploads the
HTML reports and writes a per-module table to the step summary; the command is in AGENTS.md, a row
and a Coverage note in `docs/testing.md`, and a `docs/ideas.md` entry proposes mutation testing of
core before a release.

**Look at again before a release:** Numbers drift — read the artifact, not this row: today core
97.68% statement, cli 68.71% (`StdStreams` 0% is acceptance-only, `BuildInfo` generated), cats
91.11%, zio 96.43%. Scala.js and Native cannot link instrumented (dotty's coverage runtime needs
`SecureRandom`); the Gradle and Maven plugins have no unit tests, so JaCoCo measures nothing there —
recheck both if a plugin gains unit tests or the javalibs grow the missing classes.
