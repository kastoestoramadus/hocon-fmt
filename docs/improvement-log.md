# Improvement log

Changes made under standing approval, newest first. Before a release we go through this list:
what to keep, simplify, optimise or remove. Each entry names the PR and what to look at again.

| Date | PR | Change | Revisit before release |
|---|---|---|---|
| 2026-10-08 | This PR | Drop null or undefined pull request items in live responses and cached answers; require the browser deadline test to observe an abort. | Keep the bounded fake-fetch fallback when changing deadline handling; it catches requests that never abort. |
| 2026-10-08 | #39 | CI also runs `scalafmtSbtCheck`; `build.sbt` and `project/ExampleGenerator.scala` reformatted. | — |
| 2026-10-08 | #38 | Every Scala 3 warning fails the build (`-Werror`, `-Wunused:all`, `-Wsafe-init`, `strictEquality`, explicit nulls, …); WartRemover on main code; `-Xlint:all -Werror` for the Gradle and Maven plugins; 2.12 equivalents for the sbt plugin. | The four `@SuppressWarnings` (IncludeMasking, Bench, JvmFacade, LivePr readers): could IncludeMasking drop `var`/`while` without a bench regression? `-Wtostring-interpolated` was left off (15 hits, all test messages). `-Yexplicit-nulls` is experimental in 3.8. |
| 2026-10-08 | #38 | `SconfigDefectsSpec` records the env-override defect (`x = 1` then `x = ${?X}`); 357 of 1,650 real files are refused for it, fixed by ekrich/sconfig#600. | Drop the refusal tests that turn green once a sconfig with #600 is released. |
