# 2026-10-08 — #49 — The Scala sources drop the indentation syntax

**Change:** The site's Scala sources drop the indentation syntax for braces, and `-no-indent` in the
Scala 3 `scalacOptions` (`build.sbt` and the Mill plugin's build) makes the unbraced form a compile
error; AGENTS.md names the flag.

**Look at again before a release:** Scalafmt's `rewrite.insertBraces` (3.10.7) never braces a
template body and over-braces multi-line single-expression bodies, so the conversion was by hand; if
a later version covers `object X:`, the rule could also be enforced at format time.
