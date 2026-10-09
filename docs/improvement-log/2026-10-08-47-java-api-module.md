# 2026-10-08 — #47 — A Java API module for Java and Kotlin callers

**Change:** A Java API module (`java-api/`, standalone Gradle build like the Gradle plugin): the
core's verdicts mirrored as a sealed interface of records with a `RefusalKind` per `Refusal` case,
static `check`/`checkFile`/`formatFile`/`formatOrThrow` for Java and Kotlin under JSpecify
`@NullMarked`, one Kotlin test on the test source set only.

**Look at again before a release:** The mirror dispatches on Scala's case names (`instanceof` where
the JVM has a class, `productPrefix` where it does not); the artifact has no maven-publish
configuration yet, and `check(String)` goes through the core's `Verdict.of(String)` rather than the
byte facade so lone surrogates survive.
