# 2026-10-09 — #80 — The library-defect command runs every platform

**Change:** `sbt libraryDefects` was a chain of `testOnly`s, and sbt drops the rest of a command
sequence at the first failure; since these suites are red by design, only the JVM
`SconfigDefectsSpec` ever ran, so the Scala.js and Scala Native defect suites and
`KeepDetachedCommentsGuardSpec` were unreachable through the documented command — the silently
skipped platform the build rules exist to prevent. Each suite now runs as a task whose failure is a
value, and every red is named at the end. Found by the review scan of the merged PRs #1–#31.

**Look at again before a release:** Run `sbt libraryDefects` and compare each platform's count with
the documented one (19 on the JVM and Native, 20 on Scala.js, 1 guard). The guard entry and the
`UPSTREAM-SCONFIG` comment go when the sconfig fork does.
