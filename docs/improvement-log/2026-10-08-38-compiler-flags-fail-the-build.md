# 2026-10-08 — #38 — Every Scala 3 warning fails the build

**Change:** Every Scala 3 warning fails the build (`-Werror`, `-Wunused:all`, `-Wsafe-init`,
`strictEquality`, explicit nulls, …); WartRemover on main code; `-Xlint:all -Werror` for the Gradle
and Maven plugins; 2.12 equivalents for the sbt plugin.

**Look at again before a release:** The four `@SuppressWarnings` (IncludeMasking, Bench, JvmFacade,
LivePr readers): could IncludeMasking drop `var`/`while` without a bench regression?
`-Wtostring-interpolated` was left off (15 hits, all test messages). `-Yexplicit-nulls` is
experimental in 3.8.
