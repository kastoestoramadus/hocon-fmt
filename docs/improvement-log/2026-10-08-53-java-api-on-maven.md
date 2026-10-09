# 2026-10-08 — #53 — The Java API reaches Maven

**Change:** The Java API reaches Maven: sbt builds and publishes `eu.ww86:hocon-fmt-java-api`
(Java-only, no `_3`, POM carrying the core and jspecify) over the `java-api/` sources the Gradle
build still tests, wired into `crossCompile`, `signRelease` and `publishRelease`; javadoc passes
doclint (a `RefusalKind` link fixed, `@param`/`@return` filled in), and a contract suite loads the
sbt-published jar across an isolated loader, reflectively, as the sbt plugin will.

**Look at again before a release:** `sbt javaApi/publishM2` is now a prerequisite of `./gradlew
check` in `java-api/` (CI orders them); steps 2–4 of the plugin migration move the Gradle, Maven and
sbt plugins onto the artifact and delete `JvmFacade`.
