# 2026-10-09 — #80 — The library-defect command runs every platform

**Change:** `sbt libraryDefects` was a chain of `testOnly`s, and sbt drops the rest of a command
sequence at the first failure; since these suites are red by design, only the JVM
`SconfigDefectsSpec` ever ran, so the Scala.js and Scala Native defect suites and
`KeepDetachedCommentsGuardSpec` were unreachable through the documented command — the silently
skipped platform the build rules exist to prevent. Each suite now runs as a task whose failure is
a value, and the command prints one labelled line per platform and suite (for example
`coreNative/SconfigDefectsSpec: 19 of 20 red`), then names every red. Found by the review scan of
the merged PRs #1–#31; the labels and a red/broken distinction came out of the review of this PR.

A test failure is red by design. Anything else — a compile error, a missing Node or clang, a suite
that crashes before reporting — gets `did not reach a test result (…)` and the command fails
naming it, never "red by design". The suites record what they reported through a test listener
(`project/LibraryDefects.scala`); a suite with no record never ran.

**Look at again before a release:** Run `sbt libraryDefects` and compare each labelled line with
the documented count (19 on the JVM and Native, 20 on Scala.js, 1 guard) — on 2026-10-09 they
matched. A line reading `green` means an upstream bug is fixed: drop its refusal, its
`SconfigDefectsSpec` case and its docs/limitations.md entry. The guard entry and the
`UPSTREAM-SCONFIG` comment go when the sconfig fork does.
