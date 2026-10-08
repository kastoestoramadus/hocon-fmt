# The site

The project page: the formatter presented, a playground, and the author's contributions to the
libraries it depends on. Meant to be served as `https://hocon-fmt.ww86.eu` from this repository's
GitHub Pages; until Pages is enabled, `sbt site/build` writes everything to `site/target/site`,
and the page works from `file://` as every ww86.eu page does.

## The module

`site` is a Scala.js project that depends on `coreJS` directly: the page calls `HoconFormatter`
and `Verdict` itself, with no JavaScript API in between (that is what replaced the lab-directory
plan in [playground](playground.md); the `web` module and its script are unchanged). Laminar
17.2.1, the latest stable for `_sjs1_3`, renders it; sjavatime supplies the `java.time` sconfig
needs, the way `web` does.

`sbt site/build` links the app with fullOpt and writes four files:

- `index.html` — the markup and every style; no web fonts, no CDNs, no analytics.
- `main.js` — one classic script, not an ES module, so a `<script>` tag loads it from `file://`.
- `CNAME` — `hocon-fmt.ww86.eu`, for Pages.
- `.nojekyll` — so a Pages branch serves the files as they are, without Jekyll in between.

## The page

One page, three parts, all rendered by Laminar into `#root`.

1. **What it is**: what HOCON is, what the formatter adds to a plain parse-render round trip
   (includes survive, refuse rather than corrupt), where it runs, and the privacy promise, stated
   as the page behaves: nothing pasted leaves the browser; the only network requests are the
   read-only GitHub lookups of part three.
2. **The playground**: input and output panes, formatting as you type with a 150 ms debounce, and
   one status line — "Formatted" with how many lines changed (input and output compared line by
   line), "Already formatted", or "Left unchanged" with the refusal, one sentence per refusal
   kind, and a link into [limitations](limitations.md). Example buttons come from
   `examples/showcase/NN-slug/` in directory order, using `title` and `shows` from
   `example.conf`. The sbt generator embeds the same data that core’s `ExamplesSpec`
   pins on JVM, Scala.js and Native. `examples/catalogue/` is reserved for tests and
   never appears on the page. See [shared examples](testing.md#shared-examples) for the schema.
3. **The work upstream**: the author's pull requests on sconfig and lightbend/config, grouped by
   library and theme, each with its state, and the defect table tying every refusal of
   [limitations](limitations.md) to the pull requests that aim to fix it.

## The snapshot and the live refresh

The contribution list ships in the page as a typed Scala file,
`site/.../site/Contributions.scala` (58 entries, read from GitHub on 2026-10-07), so it renders
offline, from `file://`, and when the API is out of reach. States were derived from the searches
plus the release histories: sconfig v2.0.0 (2026-08-24) and lightbend/config v1.4.9 (2026-06-03)
predate the 2026-09/10 merges, so those read "merged upstream, not yet in a release". A pull
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

## Laminar practices

Reviewed against [Laminar 17.2.1's tagged docs (including the Modifiers FAQ)](https://github.com/raquo/Laminar/blob/v17.2.1/website/docs/documentation.md),
[the official documentation](https://laminar.dev/documentation), and
[Airstream 17.2.1's README](https://github.com/raquo/Airstream/blob/v17.2.1/README.md).
Locations below are in `site/src/main/scala/ww86/hocon_fmt/site/` at the reviewed base
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

## Publishing

Not done yet, deliberately: DNS and Pages are the user's to switch on. When it happens, serve
`site/target/site` from the `gh-pages` branch (or a Pages workflow), keep the `CNAME`, and the
relative links do the rest. The hub, ww86.eu, needs only a card linking here.
