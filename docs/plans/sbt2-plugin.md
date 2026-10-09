# sbt 2 plugin (wave 2): plan

A second sbt plugin, for sbt 2.x, calling `core` in process (owner decision), beside the unchanged
wave-1 plugin for sbt 1.x. A spike (sbt 2.0.10, caches under the task dir) proved the route on
2026-10-09; every number below is from that run.

**Spike evidence.** sbt 2.0.10 runs on, and compiles plugins with, Scala 3.8.4 (`scala3-library_3`
in its POM; `show scalaVersion` → `3.8.4`), the same TASTy minor as `core`'s 3.8.2 — the plugin
compiled and ran against the published core, so the feared forward-TASTy gap does not exist on
2.0.x. 3.7.x is no fallback anyway: `set scalaVersion := "3.7.4"` fails on sbt's own API ("Forward
incompatible TASTy file has version 28.8, produced by Scala 3.8.4"), and a bare scalac 3.7.4
against core says the same for `ConfigLookup.tasty` (28.8 > 28.7). `sbtPlugin := true` publishes
`eu.ww86:sbt-hocon-fmt_sbt2_3` (sbtBinaryVersion 2 × Scala 3), the shape Maven Central carries for
`sbt-scalafmt_sbt2_3`: sbt and scala3-library provided, core in compile scope. A consumer resolved
the plugin from a private ivy local, ran the task and reported both refusals ("not valid HOCON: …
Substitution ${ was not closed", "not valid UTF-8") without touching the files, with core's
transitive `sconfig_3` on the plugin classpath (`dependencyClasspath`, and logged at run time).
`scripted` works on sbt 2 (the test build launched with `sbt-launch-2.0.10.jar`), and one fixture
passed. Porting notes: `PathFinder.get` is `get()`; `scalaVersion` is declared per project.

**Scope.** Repeat the 1.x surface: the four `Option` settings (`hoconSeparator`, `hoconDoubleIndent`,
`hoconSimplifyNestedObjects`, `hoconFailOnDuplicates`), `hoconFormatSources` (default `*.conf` and
`*.hocon` under the Compile and Test resource directories), `hoconFormat` and `hoconFormatCheck`,
`allRequirements` on `JvmPlugin`, `aggregate := false` with one pass over
`ScopeFilter(inAggregates(ThisProject))`, a shared file examined once (differing options refused by
name), per-file `.hocon-fmt.conf` lookup plus overrides via core's
`ConfigLookup`/`FormatOptions.parse`/`StyleOverrides` as Mill does, the duplicate report with
`fail-on-duplicates`, refusals reported, never written, never failing, and the summary line.
Dropped: `IsolatedFormatter`, the java-api artifact, `FormatterArtifact`, the runtime classpath —
the plugin matches on `Verdict` directly.

**Build layout.** A standalone `sbt2-plugin/` build with its own `project/build.properties`
(`sbt.version=2.0.10`), as `mill-plugin/`, `gradle-plugin/` and `maven-plugin/` stand alone; wave
1's build and artifacts are untouched. `libraryDependencies += "eu.ww86" %% "hocon-fmt-core" %
<version>`, package `eu.ww86.hoconfmt.sbt2`. Rejected: an sbt 2 row in the wave-1 project (sbt
1.12.5's `SbtPlugin` emits the `_2.12_1.0` flavor and sbt 1 metadata, not `_sbt2_3` and
`sbt/sbt.autoplugins`, and shared sources would be forced into Scala 2.12); `sbt2-compat` (unifies
the host API, not the two implementations — reflective java-api versus direct core — so it still
needs version-specific sources and a second Scala version in the build).

**Writes.** `hoconFormat` writes with `Files.write(target, formatted.getBytes(UTF_8))` after
`Verdict.of` said `NeedsFormatting`: Mill's rule, no staging, no java-api. `docs/limitations.md`
gains: *"The sbt 2 plugin writes in place: an interrupted write can leave the file partly written,
and other hard links see the new content. An already formatted or refused file is still never
touched, not even its modification time; permissions, owner and symlinks survive."*
`cats`/`FileFormatter` was rejected: cats-effect and fs2 on every user's plugin classpath for one
rarely used path.

**Tests.** `sbt2-plugin/src/sbt-test/` takes the wave-1 fixtures that carry over (check, format,
options, refused, include, aggregate, custom-sources), rewrites `identity-write` to the in-place
contract (bytes, mode and symlink survive; the hard link shares the content) and drops `java-api`;
unit suites cover the options lookup and the shared-file conflict. Alias `sbt2PluginTest` mirrors
`sbtPluginTest`; CI runs it in the JVM job.

**Release, risks, estimate, users.** `docs/releasing.md`'s table and version list gain this
artifact, `signedReleaseTasks` and `release.yml` a publish step (sbt-pgp ships `sbt-pgp_sbt2_3`).
Risks: sbt 2.1 (on Scala 3.9 in its milestones) reads 3.8 TASTy, but a rebuild may be needed at 2.1
GA — re-verify; scripted on sbt 2 is young, so keep the spike's consumer build as a smoke test.
Estimate: M. Users: sbt 2 builds add `addSbtPlugin("eu.ww86" % "sbt-hocon-fmt" % v)`; sbt 1 keeps
the current coordinate.
