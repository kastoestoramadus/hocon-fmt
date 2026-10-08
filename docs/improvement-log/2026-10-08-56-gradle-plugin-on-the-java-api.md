# 2026-10-08 — #56 — The Gradle plugin moves onto the Java API

**Change:** The Gradle plugin moves onto the Java API: `FormatAction` reads (`instanceof`: the
plugin targets Java 17, where a pattern switch does not compile) the `HoconFmt.check(byte[],
name)`'s `Verdict` records instead of calling `JvmFacade`, `compileOnly` and the worker's
`formatter.properties` coordinates name `eu.ww86:hocon-fmt-java-api`, and CI's gradle-plugin job
publishes it next to the core; a functional test pins that the worker classpath holds the API and
the core.

**Look at again before a release:** The documented `hoconFormatter(...)` override must now name the
Java API, not the core (usage.md says so); step 3 (Maven), 4 (sbt, delete `JvmFacade`) and 5 (one
write rule, which also replaces `FormatAction.write`) follow.
