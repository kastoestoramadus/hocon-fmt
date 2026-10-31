# The site

The project page: the formatter in one line, a playground, how to use it, the known limits, and
— collapsed — the author's contributions to the libraries it depends on. Meant to be served as
`https://hocon-fmt.ww86.eu` from this repository's GitHub Pages. `sbt site/build` writes
everything to `site/target/site`, and the page also works from `file://` as every ww86.eu page
does.

## The module

`site` is a Scala.js project that depends on `coreJS` directly (the core against the sconfig fork
that carries the document engine, see [running ahead](#running-ahead-of-the-release)): the page
calls `HoconFormatter`
and `Verdict` itself, with no JavaScript API in between (that is what replaced the lab-directory
plan in [playground](playground.md); the `web` module and its script are unchanged). Laminar
17.2.1, the latest stable for `_sjs1_3`, renders it; sjavatime supplies the `java.time` sconfig
needs, the way `web` does.

`sbt site/build` links the app with fullOpt and writes six files:

- `index.html` — the markup and every style; no web fonts, no CDNs, no analytics.
- `main.js` — one classic script, not an ES module, so a `<script>` tag loads it from `file://`.
- `CNAME` — `hocon-fmt.ww86.eu`, for Pages.
- `NOTICE` and `Apache-2.0.txt` — attribution and licence for the embedded Pekko excerpt.
- `.nojekyll` — so a Pages branch serves the files as they are, without Jekyll in between.

## The page

One page, seven parts, all rendered by Laminar into `#root`. The order is the owner's: what
already works and how to use it first; the work behind it last, and collapsed.

1. **One line, the promise**: the header — a formatter for HOCON configuration files that
   would rather refuse a file than corrupt it, and so never writes a broken file.
2. **The playground**: input and output panes, formatting as you type with a 150 ms debounce, and
   one status line — "Formatted" with how many lines changed (input and output compared line by
   line), "Already formatted", or "Left unchanged" with the refusal, one sentence per refusal
   kind, and a link into [limitations](limitations.md). The privacy promise heads the section,
   stated as the page behaves: nothing pasted leaves the browser; the only network requests are
   the read-only GitHub lookups of the upstream section. Example buttons come from
   `examples/showcase/NN-slug/` in directory order, using `title` and `shows` from
   `example.conf`. The sbt generator embeds the same data that core’s `ExamplesSpec`
   pins on JVM, Scala.js and Native. `examples/catalogue/` also supplies tested fixtures and
   appears in a second “More examples” group when `playground-order` is set. See [shared examples](testing.md#shared-examples) for the schema.
   The six buttons tell stories about mixed styles, a dead duplicate, development
   defaults and production overrides, a verbatim Pekko excerpt, a typo, and the comment
   safety net. The original messy, includes, comments, not-HOCON and sconfig-defect
   buttons follow them, with no duplicate fixture files. A selected example’s `upstream`
   note is one short line: the published core’s result, the report, our fix, and the verified
   status. Refusals show a plain explanation and an unchanged-input promise, followed by the
   diagnostic on a separate, wrapping line. Style controls select separator, nesting and indentation. The duplicate
   report names the dead path and both lines below the status, even on refused text.
   Formatted preserves includes and optional substitutions; Resolved previews only
   local values, with includes unloaded and environment variables unset. Resolution
   errors are shown in the output pane; refused inputs stay untouched in both tabs.
3. **What v0.1.0 covers**: the JVM core and the sbt 1.x, Gradle and Maven plugins. Native
   binaries, npm, PyPI, Mill and sbt 2 plugins follow later.
4. **Use it**: install and one usage line per channel that exists today — command line (native
   binary from the Python wheel, npm package, JVM build), pre-commit hooks, sbt, Gradle, Maven
   and Mill plugins, and the Java API — each with a snippet copied from
   [usage](usage.md), then the choices a repository can make (`.hocon-fmt.conf`, the separator,
   the duplicate report) in a few lines.
5. **Refusals in this release**: the current refusal list ships as it is, without a hand review;
   the second release tightens it. The dated JVM corpus measurement is 310 of 1,257 files
   (24.7%, rounded), including parser test fixtures and non-HOCON `.conf` files, not a production
   prevalence estimate. A moved-include example explains the refusal and the unchanged file.
   See [limitations](limitations.md#refusals-in-v010) for the measurement source.
6. **Known limits**: five bullets — the refused defect inputs, preserved headers and remaining
   detached comments, intentional normalisations, the refused `.json`/`.properties` names, and what the duplicate
   report does not claim — each backed by [limitations](limitations.md).
7. **The work upstream**: a `<details>` collapsed by default, its summary one sentence. Inside:
   the author's pull requests on sconfig and lightbend/config, grouped by library and theme,
   each with its state, and the defect table tying every refusal of [limitations](limitations.md)
   to the pull requests that aim to fix it. The refresh starts on mount even closed, so the
   answer is there the moment the section is opened.

## The snapshot and the live refresh

The contribution list ships in the page as a typed Scala file,
`site/.../site/Contributions.scala` (62 entries, read from GitHub on 2026-10-14), so it renders
offline, from `file://`, and when the API is out of reach. States were derived from the searches
plus the release histories: sconfig v2.1.0 (2026-10-12) and lightbend/config v1.4.9 (2026-06-03)
are the latest releases, so merges that landed by the sconfig release read "in a release" and the
later ones "merged upstream, not yet in a release". A pull
request counts as fixed only in that released state.

The contributions section asks for the list when it is mounted: **one request per repository**, to
`https://api.github.com/search/issues?q=author:kastoestoramadus+type:pr+repo:<owner>/<repo>&per_page=100`,
and it folds the answer into the snapshot (`Merge`, a pure function with tests: open stays open
unless the search reports it merged or rejected, released and closed stand, an unknown pull
request lands under "other recent work", a malformed item is dropped). The unauthenticated search
limit is 10 requests a minute per IP, and the answers carry
`access-control-allow-origin: *` (re-verified 2026-10-07), so no token is needed — and none may
be embedded in a public page. Each answer is cached in localStorage for ten minutes, behind a
guarded `try`, so a reload — or a remount of the section — inside that window is answered from the
cache and asks nothing; a browser without storage, or one where the ten minutes have passed, asks
again on every mount.

Failure is quiet and visible: any non-200, a malformed body, no `fetch` at all, or a search that
does not answer within eight seconds leaves that repository's snapshot standing and the state line
says what happened — "refreshed from GitHub", "snapshot; GitHub did not answer for …", or "the
shipped snapshot". The "checking GitHub…" line always resolves, because every failure is an
answer, the deadline included.

## Refreshing the snapshot

Run by hand, never in the build:

```bash
scripts/refresh-contributions.py
```

It asks the same searches through `gh` and diffs them against the shipped entries, which it reads
out of `Contributions.scala` — the one place the snapshot lives, since the copy it used to keep
drifted from it. It prints ready-made Scala blocks for new pull requests, with the theme left to
place. Move the release dates forward when either repository publishes; that is what turns "merged
upstream, not yet in a release" into "in a release". Also update `Contributions.readOn` when you
re-read the data; the script says so when it reports anything.

## Testing

`sbt site/test` runs the suite on Node: the status model, the changed-line count, the refusal
sentences, the snapshot/live merge, the grouping, the fetch path against a fake `Http` and a fake
`Storage` (success, a 403, malformed bodies, a throwing storage). `BrowserSpec` goes a step further
and fakes the browser itself: `dom.window` is one global, so the test installs a window with its own `fetch` and localStorage and drives the real
`refreshBoard` — the search answering, a browser without `fetch`, a search that fails, a search
that never answers, and the ten-minute cache. That path is where the page was broken once while
every unit test was green. The snapshot has integrity tests: every defect row resolves to an
entry, every note is a sentence, every backticked input is balanced.

The Laminar components themselves are mounted in `ComponentSpec` against `FakeDom`, a small
hand-rolled document with no npm dependency: `document` and `window` are globals, so the test
installs a fake browser and renders the real `Playground` and `ContributionsView` into it. That is
what pins the wiring nothing else can see — the verdict the panes show for a burst of typing, and
the refresh a section owns from mount to unmount. It is not a browser: no HTML parsing, no layout,
no CSS. Rendering is still verified by running the page: served
(`python3 -m http.server -d site/target/site 4001`) with the live search answering, and from
`file://` in a fetch-less browser where the snapshot must stand.

The UI coverage audit adds behavioural checks for each style switch in the formatted view,
including switching back and leaving the other controls alone; tab `aria-pressed`, the output
pane title, the hidden resolution note and read-only output; refusal details/help links and their
removal after valid input; and partial/all GitHub failures with snapshot badges and links to
other recent work. The fake GitHub can answer each repository independently. `UseIt`'s snippets
have no copy buttons to exercise. Build-stamp formatting is covered by
`DeployStampSpec`, and the local stamp in the page header by `ComponentSpec`.

On Scala 3.9.0 / Scala.js 1.22.0, `sbt coverage site/test site/coverageReport` compiles the
instrumented sources but fails linking: `There were linking errors`, including missing
`java.security.SecureRandom` and concurrent collection internals. No Scala.js coverage
percentage is reported; the audit ranks source/suite gaps instead. Run ordinary tests with
coverage off and a private Maven repository (`-Dmaven.repo.local=<task dir>/m2`), after
`scripts/fetch-sconfig-fork.sh`.

The seven new component tests were checked against deliberate production mutations: frozen
separator/nesting/indent handlers, inverted formatted-tab `aria-pressed`, a refusal link opening
in the current tab, and partial/all failure labels claiming a refresh. Every new test failed at
its corresponding assertion: `Failed: Total 26, Failed 9, Errors 0, Passed 17` (seven new tests
and two existing style tests). Both production files were restored byte for byte; the unchanged
components pass `Passed: Total 26, Failed 0, Errors 0, Passed 26`. No production change or
browser dependency was needed. CSS, native keyboard/focus behaviour and bootstrap remain
manual browser checks, outside FakeDom's model.

## Laminar practices

Reviewed against [Laminar 17.2.1's tagged docs (including the Modifiers FAQ)](https://github.com/raquo/Laminar/blob/v17.2.1/website/docs/documentation.md),
[the official documentation](https://laminar.dev/documentation), and
[Airstream 17.2.1's README](https://github.com/raquo/Airstream/blob/v17.2.1/README.md).
Locations below are in `site/src/main/scala/eu/ww86/hoconfmt/site/` at the reviewed base
`08b38f5`; use the named methods after edits.

| Practice and source | Audit location | Decision and reason |
|---|---|---|
| [Ownership and memory safety](https://laminar.dev/documentation#ownership) | `ContributionsView.scala:26`, `apply` | Applied: replace the construction-time Future callback with a section-owned `-->` subscription. Detached sections must not receive late board updates. Unmount stops delivery; the existing request deadline still governs the underlying Future. |
| [onMountBind vs onMountCallback](https://laminar.dev/documentation#onmountbind) and [Modifiers FAQ](https://laminar.dev/documentation#modifiers-faq) | `ContributionsView.scala:26`, `apply` | Applied: start the refresh in `onMountBind`, returning its binder. Do not add binders inside `onMountCallback`: remounting would accumulate subscriptions. Use callbacks for effects such as focus. Every mount therefore starts its own refresh — the ten-minute cache is what usually answers a remount. `ComponentSpec` pins the refresh per mount; `BrowserSpec` pins the cache answering a remount within its window. |
| [Window ownership](https://laminar.dev/documentation#window--document-events) | `Main.scala:9`, `main` | Retained: `unsafeWindowOwner` is confined to the tab-lifetime DOM-ready bootstrap, as in the docs. Never use it for component subscriptions. No bootstrap redesign needed. |
| [Var, Signal, EventStream](https://github.com/raquo/Airstream/blob/v17.2.1/README.md#relationship-between-eventstream-and-signal) and [state placement](https://laminar.dev/documentation#redundant-vars) | `Playground.scala:14–20`, `ContributionsView.scala:22` | Retained: Vars hold input and board state at their component roots; derived Signals hold output/status; changes are events. Keep one source of state. If child components are extracted, pass Signals and Observers rather than copying state into child Vars. |
| [Observers and arrows](https://laminar.dev/documentation#binding-observables) | `Playground.scala:96,109–110`, `ContributionsView.scala:26` | Retained for input, applied to refresh: `-->` sends events to observers; `<--` renders values. Element binders manage ownership without manual `foreach`. |
| [Distinct signals](https://github.com/raquo/Airstream/blob/v17.2.1/README.md#distinction-operators) | `Playground.scala:16`, `verdicts` | Applied before debounce and before formatting: equal input must not restart the timer; a burst ending at the last settled text must not recompute the verdict or replace status DOM. Signals no longer deduplicate automatically. |
| [Debounce and throttle](https://laminar.dev/documentation#compose-and-flatmap-events) | `Playground.scala:18`, `verdicts` | Retained: 150 ms debounce waits for typing to settle. Throttle would format intermediate text and change the page's timing; do not substitute it. |
| [Effects belong in observers](https://github.com/raquo/Airstream/blob/v17.2.1/README.md#tapeach) | `Playground.scala:20,119,123`, `ContributionsView.scala:102` | Retained: reactive maps derive verdicts and presentation, with no network/storage or Var writes. DOM factories in maps are intentional presentation. Cache writes remain at the Future-based IO boundary, not in an Airstream map. |
| [Error recovery](https://github.com/raquo/Airstream/blob/v17.2.1/README.md#recovering-from-errors) | `ContributionsView.scala:28,105`, `boardUpdates`; `Browser.scala:32` | Applied: recover an unexpected refresh-stream error to a settled snapshot board. Keep per-library recovery, fetch deadline and guarded cache; ordinary failures remain visible data, not unhandled stream errors. |
| [Keyed split for dynamic lists](https://laminar.dev/documentation#performant-children-rendering--split) | `ContributionsView.scala:47,120`, `sections` | Deferred: the list is empty until the answer arrives, and each mount rebuilds it from the board — a remount replaces the whole list and keeps no DOM or focus, which nothing on the page needs today. Before adding polling/filtering, use library/theme keys and `(library, number)` PR keys, and bind item Signals so updates preserve DOM/focus. Showcase buttons and defect rows are static lists. |
| [Components as functions](https://laminar.dev/documentation#reusing-elements) | `Page.scala:8`, `Playground.scala:12`, `ContributionsView.scala:21` | Retained: functions return fresh elements; never reuse an element across parents. Splitting the page into more components now would add structure without a reuse need. |
| [Testing observables](https://github.com/raquo/Airstream/blob/v17.2.1/README.md#documentation) | `Playground.verdicts`, `ContributionsView.boardUpdates`, `ComponentSpec` | Applied: Node tests exercise the actual observable graph with explicit owners, including disposal and recovery, and `ComponentSpec` mounts the real components in a fake document to pin what a helper test cannot see. Keep the pure-model and fake-browser suites and the real-browser served/offline rendering checks; no jsdom dependency added. |

## Running ahead of the release

<!-- UPSTREAM-SCONFIG: delete this section and the checklist below once the option is released. -->

All formatter modules use the fork `kastoestoramadus/sHOCON` at the sha pinned in
`scripts/fetch-sconfig-fork.sh`. It combines the fork's existing fixes, including
`setKeepDetachedComments` ([#647](https://github.com/ekrich/sconfig/pull/647)), with
[`ConfigDocument.format` from #655](https://github.com/ekrich/sconfig/pull/655).
The CLI, Java API, build-tool plugins, web and playground share the same dependency version.

Pinned fork commit: `298311f25f723534fb5f10f98841a3ac8907dc8c` on `hocon-fmt/doc655` in
`kastoestoramadus/sHOCON`. The local artifact is `2.1.0-hocon-fmt-298311f25f`.
The document-engine input is PR #655 at `bb644a195007b197da7272abb4674867d5326b15`;
the fork history, including its comment fixes, is preserved by a merge.

- `scripts/fetch-sconfig-fork.sh` fetches the pinned sha from GitHub into
  `~/.cache/hocon-fmt/sconfig-fork-<sha10>` and publishes JVM, Scala.js and Scala Native
  artifacts for Scala 3.9.0 to local Ivy and Maven repositories. Run it before any build.
  A complete publication is reused; a missing jar or descriptor triggers publication again.
- `checkSconfigFork` runs before core resolves dependencies, checking jars and Ivy
  descriptors for all three platforms. Its failure names the bootstrap script.
- Every sbt workflow job prepares the fork first. CI and Pages caches include both local
  repositories and are keyed by the script's hash.
- Maven and Gradle resolve the same fork transitively through the Java API and core from
  Maven Local. sbt and Mill use local Ivy. The pre-commit test prepares the fork before
  building the CLI packages.

### Moving the fork pin

1. Merge the desired upstream changes into the fork, preserve the fork commits, and test it.
   Push that fork commit before pushing a formatter branch that pins it.
2. Set `SHA` in `scripts/fetch-sconfig-fork.sh` and `sconfigFork` in `build.sbt` to the full
   commit and `2.1.0-hocon-fmt-<first ten SHA characters>`. Update the pin above.
   Keep `++3.9.0!`: the fork's sbt 2 build must publish for the formatter's Scala version.
3. Run `python3 scripts/sconfig-fork-pin-test.py`, then `scripts/fetch-sconfig-fork.sh`.
   The default source is GitHub; `HOCON_FMT_FORK_REPOSITORY` can name a local clone for
   verification before pushing. `HOCON_FMT_FORK_CACHE` selects its separate source cache.
4. For isolated builds, set `HOCON_FMT_FORK_IVY_HOME` and
   `HOCON_FMT_FORK_M2_REPOSITORY` and give consuming tools the matching
   `-Dsbt.ivy.home` and `-Dmaven.repo.local` properties. Defaults are `~/.ivy2` and
   `~/.m2/repository`; the script also honours the release workflow's `MAVEN_REPO_LOCAL`.
5. With one sbt JVM at a time, run `sbt coreJVM/test acceptance/test site/test site/build
   scalafmtCheckAll scalafmtSbtCheck crossCompile coreSite/test`. Review changed expectations
   against the merged upstream changes before updating fixtures.

## Returning to upstream sconfig

<!-- UPSTREAM-SCONFIG: this checklist is the revert; delete it as its last step. -->

Everything that exists only for the fork carries the marker `UPSTREAM-SCONFIG:`; `git grep
UPSTREAM-SCONFIG` lists it. The signal is `KeepDetachedCommentsGuardSpec` (`sbt libraryDefects`):
the fork already has the option, so a green result on this branch does not establish release
availability. Switch `sconfig` in `build.sbt` to a release carrying both `ConfigDocument.format`
and `setKeepDetachedComments`, run the guard and the full formatter suites, then:

1. Delete `scripts/fetch-sconfig-fork.sh`, the `sconfigFork` value and `checkSconfigFork` in
   `build.sbt`, and every fork bootstrap step (with its cache) in `.github/workflows/ci.yml`,
   `pages.yml`, `release.yml` and `scripts/pre-commit-e2e.sh`. Replace the `sconfig = sconfigFork`
   alias with the released version, and delete `scripts/sconfig-fork-pin-test.py`.
2. Done by task 1174 (owner decision 2026-10-31: no site users, the page follows the command
   line): `site` depends on `coreJS`, and `coreSite`, `core/site-shared`, `CommentCarrier`,
   `Carried` and `PlaceholderTree` are deleted.
3. In `core`, enable the option in `HoconFormatter.parseOptions`. The seam it needed is already
   gone (1174), so this is the option alone. Then the two pinned refusals left (`trailing-comment-in-object`, the
   `commentAboveBlankLine` block split by a blank line) flip for the command line as well; the
   leading header case already formats, kept verbatim by the
   [preamble path](limitations.md#the-preserved-preamble), and one decision is left: with the
   option on, does the preamble path keep the header verbatim (blank lines and all) or does the
   parse's own attachment render it? Pinned expectations on both sides answer for the choice.
4. Delete the muted line under the playground (`Playground.scala`), update the showcase fork
   metadata and expected files, and drop the
   fork-only text where the marker sits: the "Comment carrier" section and the `coreSite` row in
   [architecture](architecture.md), the paragraph in [limitations](limitations.md), the `coreSite`
   bullet in [testing](testing.md), the script line in [README](../README.md), and the rule and
   `coreSite` mention in [AGENTS](../AGENTS.md); retitle the idea in [ideas](ideas.md) and refresh
   the closing note of [the investigation](investigations/blank-line-comments.md). Then delete
   `KeepDetachedCommentsGuardSpec` with its `libraryDefects` entry and `testOptions` exclusion.
5. Keep the Scala.js 1.22 bump and the #647 entry in the snapshot, and update `Contributions.readOn`.

## Publishing

Deployment is handled by [the Pages workflow](../.github/workflows/pages.yml) on every push to
`main`, or manually through `workflow_dispatch`: it runs `sbt site/build`, uploads
`site/target/site`, and deploys it to the `github-pages` environment. Deployments are serialized
without cancelling an in-flight run. The output includes `CNAME` for `hocon-fmt.ww86.eu` and
`.nojekyll`; relative links also keep local previews working.

Repository Pages must use the **GitHub Actions** source and the custom domain
`hocon-fmt.ww86.eu`, with a DNS CNAME pointing to `kastoestoramadus.github.io`.
Not done yet: the hub, ww86.eu, needs a card linking here.
