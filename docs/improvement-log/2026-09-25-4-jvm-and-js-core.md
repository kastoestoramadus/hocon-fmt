# 2026-09-25 — #4 — The core runs on JVM and Scala.js

**Change:** The core runs on JVM and Scala.js, preserves includes through a separate masking
pipeline and refuses broken or unstable renderings.

**Look at again before a release:** Ordered runtime tests in `build.sbt` stop on the first failure;
review that trade-off against seeing all platform failures in one run.
