# Wave 1 before the first release

The improvement log's "look at again before a release" items, narrowed to wave 1 — the JVM
artifacts `hocon-fmt-core_3`, `hocon-fmt-cats_3`, `hocon-fmt-cli_3`, `hocon-fmt-java-api` and
`sbt-hocon-fmt` ([releasing](../releasing.md) has the set and what wave 2 adds). The log is
append-only, so a checked item is recorded here rather than ticked in its own file. Checked
2026-10-09, against the artifacts published into a private Maven repository (see
"Wave 1, tried before the first tag" in [releasing](../releasing.md)).

## Checked

| entry | the check it names | result |
|---|---|---|
| #18 release runbook | recheck `docs/releasing.md` against the first real release; the CLI missing-file claim in `docs/ideas.md` | the runbook now carries the wave-1 set, the wave column and this dry run. The missing-file claim is stale: the published CLI prints `cannot read missing.conf: no such file` and exits 2, and `docs/ideas.md` already records it as implemented |
| #28 sbt signing | verify `publishSigned` and `sonaUpload`; decide whether the JVM-only artifact set is sufficient | the set is the wave-1 JVM set (with cats, without which the CLI cannot resolve) and `sbt test` fails if it stops being closed. `signRelease` reaches `gpg` and fails without `PGP_PASSPHRASE`, so the signing and the upload stay with the CI secrets |
| #62 sbt plugin on the Java API | keep the byte entry point and the isolated-loader contract green | both ran from the published artifacts in a Scala 2.13 build: `hoconFormatCheck` (byte entry point) failed naming the file, `hoconFormat` (the Java API's `formatFile` across the loader) rewrote it, the next check passed |
| #68 plugins options, #72 follow-ups | the plugin applies explicit settings and the repository config; a conflict names the projects | `hoconSeparator := Some(":")` produced `a: 1`; the conflict rule counts an unset option as a choice, as the entry says |
| #70 CLI directories | check the docs and the pre-commit hooks still read as intended | `usage.md`'s exit-code table and the published CLI agree (`cannot read`, exit 2); the hooks are wave 2 |
| #73 unreviewed scan | run `scripts/changes-classifiers-test.sh` | `all classifier cases pass` |
| #44 cats file identity, #52 rename pins | keep the JVM rename and hard-link pins green | `catsJVM/test` green (18 passed) |
| #2 golden files, #13 include preservation, #35 lost comments, #38 env override, #48 catalogue | the defect suite is the ledger; its failures are the list to shrink | `sbt coreJVM/testOnly …SconfigDefectsSpec` is `Failed: Total 20, Failed 19, Passed 1` on the JVM, the documented 19 red by design — nothing to remove yet |
| [first release](first-release.md) item 3 | the version is in six places and `java-api` is checked | `scripts/check-release-version.sh 0.1.0` checks all six places and today names the five that still say `-SNAPSHOT`, `java-api/build.gradle.kts` among them, so a tag before the bump stops (`.pre-commit-hooks.yaml` already carries `0.1.0`); the sbt `hocon-fmt-java-api` follows `build.sbt` (the published POM carried 0.1.0 while the build said `-SNAPSHOT`). A wave-1 tag reads only `build.sbt` and `java-api/build.gradle.kts`; all six are read at `RELEASE_WAVE=2` (#81) |

The wave-1 suites ran green from this worktree: `coreJVM/test` 450 passed, `catsJVM/test` 18,
`cliJVM/test` 64, `acceptance/test` 57 (the CLI's real-process suite), `checkReleaseSet` passed, and
`scripts/improvement-log.py` exits 0 over 73 entries.

## Remains

- **Needs the real secrets or CI**, so it cannot be checked from a worktree: #28 `sonaUpload`, #31
  the `Release` workflow with its Central job, and #73's `scripts/release-smoke-test.sh` (needs
  linked native binaries).
- **Blocked on upstream sconfig**; the check is `sbt libraryDefects` (red by design) and the issue
  states recorded in [limitations](../limitations.md): #2, #13, #35, #38 (env override), #48, #61.
  sconfig 2.0.0 was tried and the defects stand; #598 is merged but unreleased, #600 is open.
- **Needs a measurement**: #34 (`IncludeOrder` cost) wants a `scripts/bench.py run` on every
  platform; #51 wants `sbt coverageJvm`.
- **Needs the wave-2 builds**: #63 identity-preserving writes on every release platform (the
  `java-api/` Gradle tests and the scripted suites), #47 the Java API's Gradle `maven-publish`
  block, #53 `./gradlew check` in `java-api/`, #43 the zio adapter's per-platform identity sources,
  #10/#29/#59 the Maven and Mill plugins, #9/#56 the Gradle plugin, #15 Mill, #27 the Gradle Plugin
  Portal's `eu.ww86.hocon-fmt`.
- **A decision before or after the tag**: #25 the platform contract for the CLI (Windows and
  terminal stdin are not covered by `CliAcceptanceSuite`), #52 whether a sixth published artifact
  (a shared file-identity module) is wanted, #64 adding `changes` to the ruleset's required checks
  (the docs-only path list already covers everything the build reads, `docs/**` included), and the
  return of merges behind review once the first release is out (the commit-named entry 803a770,
  not a PR). The symlink alias-or-target-name question is no longer open: it is a documented
  limitation with an invitation to open an issue (see [limitations](../limitations.md)).
- **The site and the playground** (#16, #30, #32, #40, #42, #55, #57, #66, #67, #69, #71) are
  wave 2 or later: nothing there is on the JVM artifacts' path.
