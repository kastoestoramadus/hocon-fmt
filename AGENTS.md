# AGENTS.md

A formatter for HOCON files, meant to run as a pre-commit hook, a CLI and a build-tool plugin, so
`--check`, exit codes and "never write a broken file" are part of the product. Scala 3 core on
sconfig, cross-built for the JVM, Scala.js and Scala Native. GPL-3.0.

## Commands

```bash
sbt test                  # core + cli on JVM, Scala.js, Scala Native, web, and site (needs Node and clang)
sbt crossCompile          # compile every platform's tests; needs neither Node nor clang
sbt scalafmtAll scalafmtSbt  # format; CI runs scalafmtCheckAll scalafmtSbtCheck
sbt libraryDefects        # SconfigDefectsSpec on every platform: red by design, 19 (JVM, Native), 20 (JS)
sbt sbtPluginTest         # sbt plugin, scripted (slow: a fresh sbt per test)
sbt coreJVM/publishM2     # needed before the Gradle and Maven builds
sbt coreJVM/publishLocal  # needed before the Mill build
(cd gradle-plugin && ./gradlew check)
(cd maven-plugin && ./mvnw verify)
(cd mill-plugin && ./mill __.testForked)
scripts/pre-commit-e2e.sh # pre-commit hooks as installed from HEAD; needs pre-commit and python3
scripts/bench.py run      # time every phase on every platform, attach to HEAD in git notes
scripts/bench.py report   # medians per commit, slowdowns flagged

sbt cliNative/nativeLink  # cli/.native/target/scala-3.8.2/hocon-fmt
sbt cliJS/npmPackage      # cli/.js/target/npm-package
sbt web/bundle            # web/target/bundle/hocon-fmt.js, the script for web pages
sbt site/build            # site/target/site: the project page (index.html, main.js, CNAME)
sbt "cliJVM/run --check path/to/file.conf"
sbt "coreJVM/testOnly ww86.hocon_fmt.HoconFormatterInvariantsSpec -- *idempotent*"
UPDATE_GOLDEN=1 sbt "coreJVM/testOnly ww86.hocon_fmt.GoldenFileSpec"
scripts/refresh-contributions.py  # diff the site's contribution snapshot against GitHub, needs gh
```

`sbt test` fails rather than skips a platform whose runtime is missing: a silently skipped
platform is how a port rots.

## Layout

`core` (pure formatting, sconfig only) · `cli` (cats-effect `IOApp`) · `web` (script for web
pages) · `site` (the project page on Scala.js/Laminar, on the core directly —
[docs/site.md](docs/site.md)) · `sbt-plugin` (Scala 2.12) · `gradle-plugin`,
`maven-plugin` (standalone Java builds) · `mill-plugin` (standalone Mill build) · `npm/` (package
template) · `python/` (wheel carrying the native binary) · `bench` · `.pre-commit-hooks.yaml`.
Details and the reasons behind them: [docs/architecture.md](docs/architecture.md).

## Rules

- **`include` handling is ours; everything else is sconfig's.** Wrong output for any other reason
  is an upstream bug: reproduce it against bare sconfig in `SconfigDefectsSpec` (named `library:`),
  make `format` refuse the input, record it in [docs/limitations.md](docs/limitations.md). Never
  work around it in `HoconFormatter`.
- **`IncludeMasking` is the core algorithm.** Read
  [the masking section](docs/architecture.md#include-masking) before changing it. Its regexes must
  run on RE2 (Scala Native) and ES2015 JavaScript (Scala.js): no lookaround, no backreferences, no
  multiline `^`. The suites on every platform enforce it.
- **Refuse rather than corrupt.** Losing a comment or an include counts, not only meaning. Every
  integration acts on `Verdict`; a refused file is never written and never fails a run. The
  output check parses the *masked* text: sconfig cannot parse an `include` on Scala.js, so do not
  simplify it to parse the finished text.
- **`core` depends on sconfig only.** The plugins load it into sbt, Gradle and Maven; effects and
  libraries belong in `cli`. `JvmFacade` is the JDK-typed boundary the sbt, Gradle and Maven
  plugins share; Mill runs Scala 3, and its plugin matches on `Verdict`.
- **Do not "fix" the intentional normalisations** (`//` to `#`, `=` to `:`, flattened paths, …)
  listed in [docs/limitations.md](docs/limitations.md).
- **One version everywhere**: see [docs/releasing.md](docs/releasing.md) for the five places.

## Conventions

- Settle a claim by running something and quote the output; reading alone has been wrong.
- Tests before implementation, in their own commits; show them failing first. A guarantee that
  must hold for every input also gets a property in `FormatterPropertiesSpec`.
- A change to a hot path gets a `scripts/bench.py run` before and after.
- New Scala code is functional Scala 3: ADTs and `Either` rather than exceptions, no `var`, effects
  at the edges. Braces, not significant indentation (`.scalafmt.conf`). The sbt plugin is Scala 2.12.
- The compiler enforces what review would otherwise catch (`scalacOptions` in `build.sbt`, every
  warning an error). Adapt the code; never relax a flag or add `@nowarn` to get a build through.
  The usual fixes:
  - `strictEquality`: a type compared with `==` gets `derives CanEqual`, and so does an enum
    matched on a case without parameters, since that match is an `==`.
  - Explicit nulls: a value from a Java API is `T | Null`; wrap it in `Option(...)` or match
    `case s: String`, at the boundary. `.nn` only with a comment saying why it cannot be null. A
    Scala.js facade is trusted as typed, so a JS value that can be null or undefined is read as
    `js.Dynamic` and checked (`js.typeOf`, `js.isUndefined`, `Option(...)`) before use.
  - A value dropped on purpose is `val _ = ...`; anything else dropped is a bug, an `IO` above all.
  - Safe init: a test suite registers its tests while the class is constructed, so data the tests
    read is declared above them or is a `def`; extractors (`Regex`) stay `val`s at the top.
- WartRemover (`project/Warts.scala`) fails the main code on what the compiler has no flag for:
  `.head`, `.get` on `Option` or `Try`, `null`, `throw`, `return`, `var`, `while`, casts. A place
  that needs one takes a `@SuppressWarnings` on the narrowest definition, with a comment saying
  why. Catch with `NonFatal`, never `Throwable`.
- Comments explain why, never restate the code. No `private` in test code.
- Commits, PRs and review replies in English; a PR carries only what it delivers.
- Attribution names the model, its effort and the tool it ran in, never a bare tool name. A commit ends
  with `Co-Authored-By: <model-id>/<effort> through <tool> <noreply@anthropic.com>`; a PR body ends with
  `Generated with <model-id>/<effort> through <tool>`, e.g. `Generated with glm-5.3-flash/max through ZCode`.
  Effort is `l`, `m`, `h`, `xh` or `max`. Whoever later changes the PR adds `Revised by …` in the same form,
  and every review pass adds `Reviewed by …`, e.g. `Reviewed by deepseek-flash/h through ZCode`.
- Every merged improvement gets a line in [docs/improvement-log.md](docs/improvement-log.md), with
  what to look at again before a release.

Where to add a test: [docs/testing.md](docs/testing.md). What is not done yet, and why it might be
worth doing: [docs/ideas.md](docs/ideas.md).
