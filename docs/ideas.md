# Ideas

What could come next, kept for review rather than promised. Each entry says what it is, why it
would be worth it, and what it takes, split where it matters into **your part** (accounts,
repositories, decisions) and **code**. Sizes are rough: S is an afternoon, M a few days, L more.
Written 2026-09-26, after the plugins, the web script and the release runbook; publishing itself
is in [releasing](releasing.md), not here.

## Found while trying the channels

### A file that cannot be read fails the run (S)

`hocon-fmt --check missing.conf` prints
`ERROR: cannot format, leaving unchanged: .../missing.conf (missing.conf)` and exits 0. A typo in
a CI script's path therefore passes silently, and the reason in brackets is the exception's
message, which for a missing file is only its name. A missing or unreadable file is not a refusal
of its content: it should say `cannot read <path>: no such file` and exit 2, as a usage error
does, leaving 1 to mean "unformatted". **Code:** a new `Outcome` for unreadable files, tests in
`CmdApiSpec` first, on all three platforms.

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

### Directories and ignores (S)

`hocon-fmt src/` walking for `*.conf` and `*.hocon`, skipping what `.gitignore` excludes, as
ruff and prettier do; today the caller expands globs. Maybe
`--strict`, turning a refusal into a failure, for teams that want every `.conf` to be HOCON.

### Report dead duplicate keys (M)

Sconfig merges repeated keys: a later plain value silently overrides an earlier one, a
"dead duplicate" that is often a mistake. A CLI report could point at both definitions,
without refusing formatting; this is analysis rather than formatting, so it may belong behind a
separate flag. Exclude the common, deliberate `x = default` then `x = ${?ENV}` idiom and
object merges (`a { x = 1 }` then `a { y = 2 }`), which kill nothing. A sweep of real files from
GitHub (October 2026) found the idiom in 626 of 1,650 GitHub files and dead
duplicates in 138; for HMRC's 1,168 files, 990 and 70 respectively. These are heuristic counts.
[Sunbird's `route.domain`](https://github.com/Sunbird-Knowlg/knowledge-platform/blob/abda3e345c2a18c139f2469653a3ef6120eb4a23/content-api/content-service/conf/application.conf#L456)
changes from `localhost:8182` to `localhost:9042`; other examples are
[DataDog's `ssl-config.logger`](https://github.com/DataDog/system-tests/blob/beaae93100abf7e55d884019369f7c6f597ac79d/utils/build/docker/java/akka-http/src/main/resources/reference.conf#L646)
and [Macrometa's `akka.logging-filter`](https://github.com/Macrometacorp/macrometa-connector-databricks/blob/d5354e2d02a8a8573faec150efa2fb47233b13fe/app/src/main/resources/application.conf#L5).

**Code:** the merged `Config` loses overridden values' origins, so inspect the pre-merge
`ConfigDocument` tree or use a parse-time hook. In sconfig 1.12.4, `ConfigDocument` preserves
syntax but exposes only editing, path checks and rendering; `ConfigNode` has no public accessor,
and `ConfigParseOptions` offers no field hook. Neither route is available through the public API:
an upstream tree-traversal API or hook is needed; its implementation cost is unknown.

## Distribution

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
in-process step through `JvmFacade` would avoid the binary but ties the step to Spotless's
internals and its configuration-cache rules.

### Dependency updates (S)

Four build tools pin versions (sbt, Gradle, Maven, Mill) plus GitHub Actions, and the CI log
already warns that `actions/checkout@v4` and `actions/setup-java@v4` run on a deprecated Node.
Renovate or Scala Steward would propose the updates as pull requests; which one covers all four
build tools is the first thing to find out. **Your part:** installing the chosen app.

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

`./mill ww86.hocon_fmt.mill.HoconFormatter/checkAll __.resources`, an external module like Mill's
own `ScalafmtModule/checkFormatAll`, so a build can check its files without changing its modules.

### Gradle version matrix (S)

The functional tests run on the Gradle that builds the plugin. TestKit's `withGradleVersion` can
run them on the oldest supported Gradle too, which is what the Mill plugin's integration test
already does for Mill.

## The formatter itself

### A parser of our own (L)

The largest item and the one that removes most limitations. Formatting through sconfig means
parsing into a configuration and rendering it again, which is why: `include` needs masking; every
sconfig rendering defect ([limitations](limitations.md)) becomes a refusal; `=` becomes `:`, `//`
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

### Skip unchanged files (S)

A cache of content hashes, as scalafmt keeps, so a large repository checks in milliseconds. Only
the command line needs it; build tools and pre-commit already pass only relevant files.

### Report sconfig's defects upstream (S)

Each `library:` test in `SconfigDefectsSpec` is a reproduction against bare sconfig. Filing them
at [ekrich/sconfig](https://github.com/ekrich/sconfig) helps everyone on sconfig and may shrink
[limitations](limitations.md). **Your part:** filing them, or approving them to be filed.

## The project

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
