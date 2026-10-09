# 2026-10-09 — [#72](https://github.com/kastoestoramadus/hocon-fmt/pull/72) — Plugin option follow-ups

**Change:** The four edge cases the #68 review left open. An sbt file that two aggregated projects
share and would format with different explicit HOCON options now fails `hoconFormat` and
`hoconFormatCheck`, naming the projects and their settings, instead of silently formatting with
whichever project the aggregation filter listed first; the Java API's rejection reaches the task
unwrapped, message intact. Gradle's config-file inputs stop at the `.git` boundary like the lookup,
so a config the formatter would never read can no longer invalidate an up-to-date check. A rejected
explicit setting is reported against its own property and value: values are rendered as fully
escaped HOCON strings, and core's option errors name the value they found (`got: …`) for a
`.hocon-fmt.conf` and a plugin override alike. `HoconFmt.discoverConfig` asks the core's pure
decision through enum predicates instead of comparing `toString()`. Tests came first in e038640 and
were red (java-api 7 tests, 2 failed; Gradle functionalTest 1, 1 failed; Maven invoker `Passed: 0,
Failed: 1`; scripted `aggregate` and `options` failed); afterwards core 11/11, java-api 37, Gradle
16 functional tests, Maven invoker `Passed: 15, Failed: 0`, scripted 9/9, Mill 13, `sbt test` 2,101
passed, both scalafmt checks, and `scripts/plugin-parity.sh` byte-identical output across CLI, sbt,
Gradle, Maven and Mill.

**Look at again before a release:** The sbt conflict counts an unset option as a choice: a project
that sets `hoconSeparator` and one that leaves it default cannot share a file, which is intended.
Values still travel as HOCON strings, so booleans keep sconfig's string-to-boolean coercion, the
same thing a `.hocon-fmt.conf` may write. Gradle tracks config inputs only up to the `.git`
boundary; the lookup's deeper filesystem effects stay edge-specific.
