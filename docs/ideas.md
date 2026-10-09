# Ideas

What could come next, kept for review rather than promised. Each entry says what it is, why it
would be worth it, and what it takes, split where it matters into **your part** (accounts,
repositories, decisions) and **code**. Sizes are rough: S is an afternoon, M a few days, L more.
Written 2026-09-26, after the plugins, the web script and the release runbook; publishing itself
is in [releasing](releasing.md), not here.

## Found while trying the channels

### A file that cannot be read fails the run (S) — implemented

`hocon-fmt --check missing.conf` used to print
`ERROR: cannot format, leaving unchanged: .../missing.conf (missing.conf)` and exit 0, so a typo in
a CI script's path passed silently. A missing or unreadable file is not a refusal of its content:
the run now says `cannot read <path>: no such file` on stderr and exits 2, as a usage error does,
leaving 1 to mean "unformatted". **Code:** `Result.Unreadable` in `CmdApi`, `ReadFailure` turning
the JVM's typed exceptions and Node's errno strings into words, and `Walk.Unreadable` for a
directory argument that does not exist; tests in `CmdApiSpec` and `CliAcceptanceSuite`, on all
three platforms.

### One report format for every channel (S)

The command line kept the original tool's messages, and they disagree with the plugins':

| situation | CLI | sbt, Mill | Maven |
|---|---|---|---|
| rewritten | nothing | `Formatted <path>` | `Formatted <path>` |
| already formatted | `.`, with no newline | nothing | nothing |
| unformatted, `--check` | `Found a not formatted file: <path> .` then the whole formatted text | `Not formatted: <path>` | `Not formatted: <path>` |
| refused | `ERROR: cannot format, leaving unchanged: <path> (<reason>)` | `Leaving <path> unchanged: <reason>` | the same |
| summary | `Running HOCON formatter for 1 files.` first | `HOCON files: 1 formatted, ...` | `HOCON files: 1 reformatted, ...` |

"ERROR" for something that does not fail the run misleads, and "1 files" reads badly everywhere.
Settle on the plugins' wording, print the summary last, and move the formatted text behind a
`--diff` flag. **Code:** mostly `CmdApi.render`, the Maven plugin's summary, and the tests that
pin the old messages; the pre-commit e2e script reads none of them.

### Wheels for more machines (M)

The wheels cover Linux with glibc 2.34+ (Ubuntu 22.04, Debian 12, RHEL 9 and newer) and Apple
silicon. Intel Macs, Windows and older Linux get no wheel, so their users fall back to the Node
hooks, twice as slow and 200 MB heavier. **Code:** a `macos-13` job for Intel Macs; linking the
Linux binaries in a `manylinux_2_28` container to lower the glibc floor; a Windows build (below).
Each is a matrix entry in `release.yml`, then a test that the wheel installs where it claims to.

### A native build for Windows (M)

Scala Native supports Windows with clang, but nothing here has tried it: the file handling in
`cli` goes through fs2, which should work, and the regex constraints are the same RE2 ones.
Worth it for Windows developers, who now need Node, and a prerequisite for Scoop and Windows
wheels. **Code:** a `windows-latest` entry in the release matrix and in CI; expect path-handling
surprises in `CmdApiSpec`.

## The command line

Standard input to standard output and `--version` are implemented; see [usage](usage.md).

### Directories and ignores (S) — implemented

`hocon-fmt src/` walks for `*.conf` and `*.hocon`, skipping what `.gitignore` excludes, as ruff
and prettier do; the caller no longer has to expand globs. Hidden entries are skipped and a
symlinked directory is not followed; a file named on the command line is examined even when
ignored, since the user typed that path. The ignores are the `.gitignore` files from the
checkout root (where the walk up stops, as the style lookup does) down and below, a deeper
file's match beating a shallower one's; like prettier and ruff, the walk reads those files and
not `core.excludesFile` or `.git/info/exclude`. The `--strict` the entry floated — a refusal
failing the run — was not taken, so a refused file still never fails a run. **Code:** `Walk` and
`GitIgnore` in `cli`, tests in `CmdApiSpec`, `GitIgnoreSpec` and `CliAcceptanceSuite`.

### Report dead duplicate keys (M) — implemented

The CLI reports them as warnings, `DuplicateReport` in the core computes them, and
`--fail-on-duplicates` (or its `.hocon-fmt.conf` key) makes one fail a run; see
[usage](usage.md#the-duplicate-report) and [architecture](architecture.md#the-duplicate-report).
Sconfig merges repeated keys: a later plain value overrides an earlier one, a
"dead duplicate" that is often a mistake, and the report points at both definitions without
refusing formatting. The common, deliberate `x = default` then `x = ${?ENV}` idiom and
object merges of disjoint fields (`a { x = 1 }` then `a { y = 2 }`) kill nothing and are exempt.
When both objects define the same field, the later value can kill the earlier one like any other
dead duplicate: `a { x = 1 }` then `a { x = 2, y = 3 }` loses `a.x = 1`. A sweep of real files from
GitHub (October 2026) found the idiom in 626 of 1,650 GitHub files and dead
duplicates in 138; for HMRC's 1,168 files, 990 and 70 respectively. These are heuristic counts.
[Sunbird's `route.domain`](https://github.com/Sunbird-Knowlg/knowledge-platform/blob/adca9749a0ce537b7572326af3ae80ee0a3a22de/content-api/content-service/conf/application.conf#L456-L459)
changes from `localhost:8182` to `localhost:9042`; other examples are
[DataDog's `ssl-config.logger`](https://github.com/DataDog/system-tests/blob/1d664089d6aaac7e0fcd5f8a3ad21ddcbfdaa0fa/utils/build/docker/java/akka-http/src/main/resources/reference.conf#L645-L649)
and [Macrometa's `akka.logging-filter`](https://github.com/Macrometacorp/macrometa-connector-databricks/blob/9b24097996e04c17786af33c16cef34c3ce08104/app/src/main/resources/application.conf#L5-L21).

**Code:** as expected, the merged `Config` loses an overridden value's origins, so the report reads
the pre-merge `ConfigDocument` tree instead — through its implementation, since `ConfigNode` still
has no public accessor in sconfig 1.12.4: `DuplicateReport` matches the document against
`SimpleConfigDocument` and walks `configNodeTree`. That is the piece to revisit when
[upstream](https://github.com/lightbend/config/issues/300) publishes a traversal API.

## Distribution

### Install options for the CLI

Where the command line can come from, one or two lines each; the entries below cover what is
still to build. The first four ship in wave 2, with PyPI, npm and the release binaries.

- **pipx** — `pipx install hocon-fmt`, the PyPI wheel carrying the native binary. Needs Python;
  no wheel for Windows, Intel Macs or Linux older than glibc 2.34.
- **npx** — `npx hocon-fmt`, the npm package running the Node build; needs Node only.
- **pre-commit** — the hooks from this repository at a tag; needs Python and pre-commit only, and
  installs the wheel or the npm package itself.
- **GitHub release binary** — the native binary attached to a release, downloaded by hand.
- **Homebrew tap** — `brew install kastoestoramadus/tap/hocon-fmt` on macOS and Linux; entry below.
- **coursier** — `cs install hocon-fmt` for the Scala crowd; the same channel serves pre-commit
  through `language: coursier`, which pre-commit 4.6.2 supports and which needs `cs` on PATH and
  the JVM CLI on Maven Central, and starts slower than the native binary; entry below.
- **Docker** — `docker run ghcr.io/kastoestoramadus/hocon-fmt ...` for CI that runs containers;
  entry below.
- **GitHub Action** — `uses: kastoestoramadus/hocon-fmt-action@v1`, checking with an annotation
  per unformatted file; entry below.
- **Scoop / Windows wheel** — both want the Windows native build first: Scoop installs the
  binary from a bucket, the wheel gives pip and pipx a Windows build.
- **Nix** — `nix run github:kastoestoramadus/hocon-fmt` from a flake, then a nixpkgs package;
  entry below.

After wave 2, build the Homebrew tap and coursier first, then Docker and the Action.

### Homebrew tap (S)

`brew install kastoestoramadus/tap/hocon-fmt` on macOS and Linux, installing the release
binary. **Your part:** create the repository `kastoestoramadus/homebrew-tap`, and a fine-grained
token that can write to it only, saved as the secret `HOMEBREW_TAP_TOKEN` (the workflow's own
token cannot push to another repository). **Code:** `Formula/hocon-fmt.rb` with a URL and
SHA-256 per platform, and a release job that rewrites it for each tag. homebrew-core itself
accepts only projects with some following, so it comes later if at all.

### coursier (S)

`cs install hocon-fmt` for the Scala crowd, who have coursier already. coursier installs
apps from a channel, a JSON description that can point either at the JVM command line on Maven
Central or at the native binaries on GitHub releases. **Your part:** decide where the channel
lives (a file in this repository, or a small channel repository). **Code:** the app descriptor;
publishing `cliJVM` to Maven Central with its main class, if the JVM variant is used.

### Scoop (S, after the Windows build)

`scoop install hocon-fmt`, a JSON manifest in a bucket repository
`kastoestoramadus/scoop-bucket`, pointing at the Windows binary; same shape as the Homebrew tap.

### Nix (M)

A flake in this repository (`nix run github:kastoestoramadus/hocon-fmt`) that fetches the
release binary and patches its interpreter, and later a nixpkgs package. Building from source in
Nix's sandbox would mean packaging sbt's dependencies, which is where most Scala packages there
stall.

### Docker image (S)

`docker run --rm -v "$PWD:/work" -w /work ghcr.io/kastoestoramadus/hocon-fmt --check ...`,
for CI systems that run containers and nothing else. The binary links glibc and libstdc++
dynamically, so the base must carry both: Debian slim does, scratch and Alpine do not. **Your
part:** after the first push, check the package's visibility in its settings on GitHub and make it
public.
**Code:** a Dockerfile and a release job with `packages: write`, which needs no other secret.

## CI and tools

### GitHub Action (S)

```yaml
- uses: kastoestoramadus/hocon-fmt-action@v1
  with: { files: "**/*.conf", version: 0.1.0 }
```

A composite action that downloads the release binary for the runner and runs `--check`, with an
annotation on each unformatted file. **Your part:** the Marketplace takes an action only from a
public repository with `action.yml` at its root and no workflow files, so it needs its own
repository, `kastoestoramadus/hocon-fmt-action`; then tag `v1` and tick "Publish this
Action to the GitHub Marketplace" on the release. **Code:** `action.yml` and a short script.

### Spotless (S, after `--stdin`)

Teams on Spotless add a step rather than a plugin. With `--stdin`, Spotless's `nativeCmd` step
runs the binary on each file; a snippet in [usage](usage.md) is the whole integration. An
in-process step through the Java API would avoid the binary but ties the step to Spotless's
internals and its configuration-cache rules.

### Dependency updates (S)

Four build tools pin versions (sbt, Gradle, Maven, Mill) plus GitHub Actions, and the CI log
already warns that `actions/checkout@v4` and `actions/setup-java@v4` run on a deprecated Node.
Renovate or Scala Steward would propose the updates as pull requests; which one covers all four
build tools is the first thing to find out. **Your part:** installing the chosen app.

### Mutation testing of core (stryker4s) (M)

The coverage report shows which statements the tests reach; it does not show whether the
assertions behind them would notice a change. Stryker4s mutates `core`'s code — flips a
comparison, drops a negation, swaps a branch — and reports how many mutants the suites kill;
statements covered yet not killed mark assertions that only exercise. Run it by hand before a
release rather than in CI: a full mutation run costs hours of CPU for a number that moves
slowly, and every surviving mutant needs reading to decide whether it is a real gap or an
equivalent mutant. **Code:** a `stryker4s.conf` for `coreJVM`, whose 97.68% statement coverage
is the reason to expect the kill score to be the informative number.

## Editors

### VS Code extension (M)

Format on save and "Format Document" for `.conf` and `.hocon`, running the Scala.js build inside
the extension, so nothing to install and nothing to spawn. The `web` module's API is most of it;
the extension needs a CommonJS build of it. A refusal shows as a diagnostic with its reason
instead of a silent no-op. **Your part:** a publisher account on the Visual Studio Marketplace
and Open VSX.

### Neovim, Helix, Zed (S, after `--stdin`)

Each takes an external formatter from configuration: conform.nvim's formatter list, Helix's
`languages.toml`, Zed's external `formatter` setting. Documenting the snippets is
enough; a pull request adding the formatter to conform.nvim's built-in list makes it one line
for users.

### Language server (M)

A small LSP server in the native binary: `textDocument/formatting`, and diagnostics saying why a
file would be refused. One integration then serves every LSP editor, including the ones above,
and refusals become visible while editing rather than at commit time.

### IntelliJ (M)

JetBrains' HOCON plugin formats with its own rules; an external-formatter plugin calling the JVM
core would bring this formatter's output and refusals into the IDE. Least urgent of the editors,
since IntelliJ users already have something.

## Build tools

### sbt 2 (M)

sbt 2 runs plugins on Scala 3, so a cross-build of the sbt plugin could call the core directly, as
the Mill plugin does, and drop the isolated class loader. Worth doing when sbt 2 is final and
sbt-scalafmt has moved.

### Mill without a trait (S)

`./mill eu.ww86.hoconfmt.mill.HoconFormatter/checkAll __.resources`, an external module like Mill's
own `ScalafmtModule/checkFormatAll`, so a build can check its files without changing its modules.

### Gradle version matrix (S)

The functional tests run on the Gradle that builds the plugin. TestKit's `withGradleVersion` can
run them on the oldest supported Gradle too, which is what the Mill plugin's integration test
already does for Mill.

## The formatter itself

### A parser of our own (L)

The largest item and the one that removes most limitations. Formatting through sconfig means
parsing into a configuration and rendering it again, which is why: `include` needs masking; every
sconfig rendering defect ([limitations](limitations.md)) becomes a refusal; `:` becomes `=`, `//`
becomes `#` and paths get flattened whether the author wanted it or not; and parsing is 27 times
slower on Scala Native than on the JVM. A concrete syntax tree for HOCON, parsed with cats-parse
and printed by our own printer, keeps every token the author wrote, so the formatter changes only
layout. It is also what a configurable style needs (next). The branch `spike/cst-cats-parse` was
started for it but holds no code. **Code:** the grammar from the HOCON specification, a
round-trip property (parse then print unchanged is the identity), then the printer; the existing
suites and properties keep their meaning.

### Configurable style (M, after the parser)

A `.hocon-fmt.conf` choosing `:` or `=`, indentation, and whether to flatten single-key
objects. Every channel would read it from the project root. Pointless while sconfig decides the
output.

### Keep comments above a blank line in the command line too (M)

Licence headers and banners are the commonest refusal in real files. The project page already
keeps them on the sconfig fork; the command line and the plugins wait for sconfig to release the
option (ekrich/sconfig#646/#647) and then flip. **Code:** see
[the investigation](investigations/blank-line-comments.md) and `docs/site.md`, "Returning to
upstream sconfig".

### Skip unchanged files (S)

A cache of content hashes, as scalafmt keeps, so a large repository checks in milliseconds. Only
the command line needs it; build tools and pre-commit already pass only relevant files.

### Report sconfig's defects upstream (S)

Each `library:` test in `SconfigDefectsSpec` is a reproduction against bare sconfig. Filing them
at [ekrich/sconfig](https://github.com/ekrich/sconfig) helps everyone on sconfig and may shrink
[limitations](limitations.md). **Your part:** filing them, or approving them to be filed.

### Render `+=` back as `+=` upstream (S)

Formatting expands the append shorthand to the specification's form, `a += 2` becoming
`a = ${?a}[` with the `2` and the closing bracket on their own lines below, recorded as an
intentional normalisation in [limitations](limitations.md). The value
is the same, but the spelling the author wrote is not kept, and sconfig is why: its parse tree
desugars `+=` at parse time, so the renderer cannot tell the shorthand from the expansion written
out. A render option that keeps the shorthand needs the parser to mark what it built.
**Your part:** proposing it at [ekrich/sconfig](https://github.com/ekrich/sconfig). **Code:** none
here; the normalisation entry goes away with the fix.

## The project

### A dependency-free shared file-identity module (M)

The `cats` and `zio` adapters mirror about a hundred lines of file-identity code — stage a copy
beside the original, give it the original's owner, group and every mode bit, prove it by reading
them back, rename over the original, and write in place only when the identity is unavailable, the
file or its directory unwritable, or the identity cannot be kept; a staging, writing or renaming
I/O error instead propagates and leaves the original intact — including the Scala
Native C `stat`/`chown`/`chmod` interop both carry (`FileIdentity` and the per-platform attribute
sources exist twice, `AtomicFiles` against `AtomicFile`/`PosixIdentity`). Calling one adapter from
the other is no fix: it would put fs2 and cats-effect on the ZIO classpath, or ZIO on the cats
one, against [architecture](architecture.md). A `file-identity` artifact depending on nothing but
the standard library would delete the mirror and stop the two adapters drifting; the cost is a
sixth published artifact — coordinates, release config, signing, a runbook line — for code with
two consumers. **Your part:** deciding the artifact earns its publication. **Code:** move the
identity record and the per-platform attribute sources, parameterise the write over the effect
type, point both adapters at it; the rename and hard-link tests each adapter carries today move
with it unchanged.

### Java style in the Gradle and Maven plugins (S)

The two Java builds are formatted by hand, not quite alike. google-java-format through Spotless
in both, checked in CI, as `scalafmtCheckAll` checks the Scala.

### Benchmarks that can gate (M)

CI reports benchmarks without failing, because shared runners are noisy. A dedicated machine or
a self-hosted runner, and a chart of `refs/notes/benchmarks` published to GitHub Pages, would let a
slowdown fail a pull request.

### Playground extras

Sharing an input by link, a diff view and a "report this refusal" button: see
[playground](playground.md#later).

### A site whose contribution list refreshes itself (S)

The project site lists the author's pull requests to sconfig and lightbend/config. The list ships
as a snapshot in the page and the browser refreshes each state from `api.github.com` after load,
which is free and needs no token, but it only shows the right state to a visitor who is not rate
limited and whose browser reaches the API. A scheduled workflow that regenerates the snapshot from
`gh` and redeploys Pages would keep the page itself current, so the live fetch becomes a bonus
rather than the only freshness. Public repositories pay nothing for it, GitHub Actions minutes on
standard runners being free for them. **Your part:** deciding the cadence, and whether the redeploy
may commit the refreshed snapshot to `main` or only publish it. **Code:** a `schedule` trigger in
the Pages workflow, a refresh script run by hand today, and a check that a changed snapshot is the
only difference before it deploys.

## From the formatter research

The [synthesis](research/formatters/REPORT.md#recommendations) ranks these; nothing here promises
implementation. Existing report/diff, cache, corpus-comment support, CST, editor/LSP, distribution,
benchmark and file-identity entries above remain the canonical entries, not duplicates.

| new idea | source (research + original URL) | why not now / trigger to revisit | cost |
|---|---|---|---|
| Second separator on every approved golden/example | [test architecture](research/formatters/REPORT.md#test-architecture), [Cue harness](https://github.com/cue-lang/cue/blob/master/cue/format/format_test.go) | the default-options second pass already exists per fixture; goldens and examples are pinned under `=` only, so a `:` pass is research only — next golden/test change, promote with mutation proof | S |
| Exact comment multiset including duplicates and explicit attachment slots | [property plan](research/formatters/REPORT.md#property-testing-plan), [rustfmt #5913](https://github.com/rust-lang/rustfmt/pull/5913) | loss-only guard already exists; next core safety change should add the converse and slot model | S/M |
| Independent value oracle with include/override and environment fixtures | [property plan](research/formatters/REPORT.md#property-testing-plan), [Black safety code](https://github.com/psf/black/blob/main/src/black/parsing.py) | avoid runtime dependency changes; next safety PR adds pinned Lightbend Config in JVM test scope | M |
| Budgeted licensed corpus with per-refusal ceilings | [test architecture](research/formatters/REPORT.md#test-architecture), [gofmt corpus](https://github.com/golang/go/blob/master/src/cmd/gofmt/long_test.go) | corpus inputs need pins/licences; before release, sample within a stated file/byte/time budget | M |
| Nightly seeded properties and failure minimization | [property plan](research/formatters/REPORT.md#property-testing-plan), [Black fuzz](https://github.com/psf/black/blob/main/scripts/fuzz.py) | CI cost unmeasured; after measuring the PR tier, schedule a capped exploration run | S |
| Source-level re-lex/delimiter/literal checks | [test architecture](research/formatters/REPORT.md#test-architecture), [taplo #456](https://github.com/tamasfe/taplo/pull/456) | value-tree equality misses text corruption; next masking/comment transform supplies permitted-normalization rules | M |
| Improvement-detecting known-bad ledger for our own refusals | [test architecture](research/formatters/REPORT.md#test-architecture), [Prettier exclusions](https://github.com/prettier/prettier/blob/main/tests/config/failed-format-tests.js) | `SconfigDefectsSpec` gives the signal for sconfig defects only; our refusals are asserted as the expected outcome, so a case that starts formatting reads as a regression — next refusal change seeds a ledger from the probe replay's refused rows and routes improvements to triage, never silent skips | S |
| All-verdict process contract and visible refusal totals | [product](research/formatters/REPORT.md#product), [gofmt #46289](https://github.com/golang/go/issues/46289) | existing CLI/acceptance tests cover much; next reporting change extends them instead of creating a parallel suite | S |
| Structured `--json` per-file verdicts/diagnostics | [product](research/formatters/REPORT.md#product), [Ruff CLI](https://github.com/astral-sh/ruff/blob/main/crates/ruff/src/args.rs) | schema would become an API; revisit for CI/editor consumption with a versioned schema | M |
| `path:line:col` diagnostics and Unicode offset discipline | [product](research/formatters/REPORT.md#product), [rustfmt #7029](https://github.com/rust-lang/rustfmt/pull/7029) | paths/lines already exist; revisit when precise spans are available, not guessed from rendering | M |
| `# hocon-fmt: off/on` scope and unmatched-marker policy | [product](research/formatters/REPORT.md#product), [Black #4033](https://github.com/psf/black/issues/4033), [dprint ignore fix](https://github.com/dprint/dprint-plugin-json/pull/69) | value tree cannot preserve arbitrary regions; revisit only with a source-preserving strategy, whole-file off first | L |
| Style-stability policy and repo-side required version | [process/docs](research/formatters/REPORT.md#process-and-docs), [Black stability](https://github.com/psf/black/blob/main/docs/the_black_code_style/index.md) | artifact pins exist; before first release publish policy, then consider a runtime version guard | S |
| Refusals explained as protection, counted visibly | [process/docs](research/formatters/REPORT.md#process-and-docs), [rustfmt warning fixtures](https://github.com/rust-lang/rustfmt/tree/main/tests/warning) | documentation added to limitations now; next report-format change aligns channel wording | S |
| Property tiers and admission rule with ten initial targets | [test budget](research/formatters/REPORT.md#how-not-to-overdo-tests), [dprint tests](https://github.com/dprint/dprint-plugin-json/blob/main/tests/test.rs) | no regression tests added by research; require a demonstrated failure and cost tier for each promoted probe | S |
| Cross-platform corpus bytes and no-panic arbitrary-byte core property | [property plan](research/formatters/REPORT.md#property-testing-plan), [hclwrite fuzz](https://github.com/hashicorp/hcl/tree/main/hclwrite/fuzz) | JVM probes establish no JS/Native claim; next property change extends existing ZIO byte coverage to core | M |
| BOM-tolerant style config policy | [safety/API study](research/formatters/safety-and-ux-history-tests-api.md#5-ideas-to-migrate-with-cost-and-value), [Prettier config history](https://github.com/prettier/prettier/commit/1a36a7de) | input BOM normalization does not specify config BOM policy; revisit with a Windows-authored config reproduction | S |
| Range formatting, extension plugin API, more style switches, sorting or comment reflow | [we will not do](research/formatters/REPORT.md#we-will-not-do), [Black range caveat](https://github.com/psf/black/issues/4033), [Prettier options](https://github.com/prettier/prettier/blob/main/docs/option-philosophy.md) | deliberately declined: nonlocal merge/include semantics and option interactions; revisit only with concrete demand and a safety design | L |

Existing "One report format" owns `--diff`; existing cache idea should include content **and every
option**, parser version and a no-cache escape hatch ([Black discovery](https://github.com/psf/black/blob/main/docs/usage_and_configuration/file_collection_and_discovery.md)).
Existing CST entry owns lossless parsing; existing benchmark entry owns performance gates. Editor snippets,
VS Code/LSP, GitHub Action, Docker and ecosystem builds retain their earlier costs and revisit triggers;
this research supplies evidence, not a reason to add another implementation before release.
