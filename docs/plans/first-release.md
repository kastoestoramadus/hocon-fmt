# First release: blockers

Written 2026-10-09 from `main` at `b4a441f`. Nothing is published yet. The runbook and the
one-time account setup are in [releasing](../releasing.md); this file only says what stands between
`main` and a tag, in the order to do it. Tick a box in the pull request that closes it.

## Two waves (decided 2026-10-09)

**Wave 1, the first publish:** the JVM artifacts sbt uploads to Maven Central with `publishRelease`:
`hocon-fmt-core_3`, `hocon-fmt-cats_3`, `hocon-fmt-cli_3`, `eu.ww86:hocon-fmt-java-api` and the sbt
plugin. The adapter is in the list because `hocon-fmt-cli_3`'s POM names it: a repository holding
the other four fails the CLI with `Error downloading eu.ww86:hocon-fmt-cats_3:0.1.0`. The wave-1 dry
run (#79) found that, made `publishRelease` this set, and made `sbt test` check the set is closed.
**Wave 2:** the Maven, Mill and Gradle plugins, PyPI wheels, npm, native binaries, the web script and
the pre-commit hooks. So for wave 1 the PyPI, npm and Gradle accounts below, and items 1 (PyPI, npm,
Gradle jobs) and the Gradle part of the secrets, can wait; a tag in wave 1 must not run the wave 2
jobs. The hooks pin PyPI and npm versions, so they must not be advertised before wave 2.

## Owner's part (accounts, secrets, decisions)

- [ ] Gradle Plugin Portal: sign in, check the portal verifies `eu.ww86` (else fall back to
  `io.github.kastoestoramadus.hocon-fmt` and rename in `gradle-plugin/`, README, usage); add
  `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET`.
- [ ] PyPI: account, pending trusted publisher (project `hocon-fmt`, workflow `release.yml`,
  environment `pypi`), the `pypi` environment.
- [ ] npm: account; the first version is published by hand from the release's tarball, then the
  trusted publisher is configured.
- [x] Decide the first version: `0.1.0`.
- [ ] After publish: tighten the process (see the end).

## Code

1. **`release.yml` has no PyPI, npm or Gradle job.** It builds the wheels and the npm tarball and
   attaches them to the GitHub release, and it stages Central. Missing: `pypa/gh-action-pypi-publish`
   with `environment: pypi` and `id-token: write`; an npm trusted-publisher job (npm >= 11.5.1);
   `com.gradle.plugin-publish` in `gradle-plugin/` (website, VCS URL, tags) and
   `./gradlew publishPlugins` in the job.
2. **The Central job is unverified** (#28, #29, #31): signing, `sonaUpload`, the Maven `release`
   profile and Mill's `publishAll` have never run with the real secrets. The dry run in
   `docs/releasing.md` step 2 is the test; it needs a manual `workflow_dispatch` on `main`.
3. **Version in five places** (`build.sbt`, `gradle-plugin/build.gradle.kts`,
   `maven-plugin/pom.xml`, `mill-plugin/build.mill`, `.pre-commit-hooks.yaml`);
   `scripts/check-release-version.sh` guards the tag. Check that `java-api` takes its version from
   `build.sbt` and is among the artifacts the checker covers.
4. **Sconfig is a snapshot of the world, not of the release.** The core uses published sconfig
   1.12.4; ekrich/sconfig#598 and #605 are merged but unreleased. Decide whether to wait; if the
   release goes out on 1.12.4, `docs/limitations.md` must say which inputs are refused because of
   it (the playground already runs a fork).
5. **Release notes.** `gh release create --generate-notes` is the only text today. Write them from
   the improvement log's "Look at again before a release" column (below) and the user-facing
   changes since #21.
6. **The page's "until it is out" line.** `UseIt.scala` says every channel runs from a checkout
   (improvement-log entry #69). Remove the sentence in the release PR, with the coordinates in the
   usage lines pointing at the real version.
7. **`docs/releasing.md` and `ideas.md`** say "nothing has been released yet" in several places;
   update after the first release, along with the `rev:` example.

## Review of the improvement log

`scripts/improvement-log.py` lists the "look at again before a release" items. Those that bite a
release (the rest are post-release or upstream-dependent):

| entry | what to check |
|---|---|
| #72, #68, #63, #44, #43 | file-identity writes: the unix view and the in-place fallback on the release platforms (Linux x86_64/aarch64, macOS arm64) |
| #62, #59, #56, #53 | `eu.ww86:hocon-fmt-java-api` is published with the core; the plugins' worker classpaths resolve it from Central, not from Maven Local |
| #31, #29, #28 | the Central job end to end (item 2) |
| #27 | the Gradle portal accepts `eu.ww86.hocon-fmt` |
| #25 | decide the supported platform contract (no Windows, no terminal stdin) and say it in usage |
| #18, #11 | recheck `docs/releasing.md` against the first real release |
| #16 | does the standalone `web` script still have users, or is it dropped from the release assets |
| #48, #38, #35, #13 | refusal expectations to drop if a newer sconfig ships first |
| #57 | Pages deployment, custom domain and DNS (done: the page is up) |
| #64 | add `changes` to the ruleset's required checks |

## The site's Scala showcase

After publishing 0.1.0, verify the core, cats, ZIO and Java API coordinates on Maven Central,
then flip the single `ScalaShowcase.released` constant and update its pre-release test.
Exercise the URL hand-off with edited code on every Scala tab.
The Java example remains a copyable Java source: Scastie runs Scala, not Java sources.

The page controls the submitted Scala version and dependencies; Scastie's build-settings editor
can change them afterwards. Directive rejection is a UI constraint, not a security boundary.
Enforcing a classpath for arbitrary remote programs requires our own controlled execution service.

Local browser compilation must wait for the browser-scala-probe report. If it proves the core
classpath works in Chrome, stage compiler assets through `site/build`, lazy-load them when the
editor opens, and detect `WebAssembly.JSTag`, `Suspending` and `promising` before loading.
Keep binaries out of Git and retain the Scastie fallback with an explanation when unavailable.

## After the release

- Process: review before merge (rewrite the bullet in AGENTS.md, entry "direct push" 2026-10-09)
  and remove the ruleset's admin bypass (`Protect main`, id 24052551, `bypass_actors: []`).
  The owner changes the ruleset.
- Distribution from [ideas](../ideas.md): Homebrew, coursier, Docker, GitHub Action.
- Native CLI: one segfault (exit -11) on a run over 99 files, 20 repeats clean. Watch.
