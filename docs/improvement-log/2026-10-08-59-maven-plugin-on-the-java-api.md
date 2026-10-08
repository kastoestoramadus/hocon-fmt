# 2026-10-08 — #59 — The Maven plugin moves onto the Java API

**Change:** The Maven plugin moves onto the Java API: `Outcome` reads `HoconFmt.check(byte[],
name)`'s sealed `Verdict` records with a Java 17 `instanceof` chain (a verdict it does not know
fails loudly), the pom depends on `eu.ww86:hocon-fmt-java-api` with the core transitively, CI's
maven-plugin job and the release workflow's Maven steps publish the API beside the core, and a new
invoker build reads the plugin realm off the `-X` log and pins the API and the core onto it.

**Look at again before a release:** Step 4 (sbt plugin on the API, delete `JvmFacade`) and 5 (one
write rule, which also replaces the Maven plugin's `Files.writeString`) follow. The plugin still has
no unit tests, so JaCoCo measures nothing there — recheck it if one arrives.
