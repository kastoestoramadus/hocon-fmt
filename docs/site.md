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
   kind, and a link into [limitations](limitations.md). Five example buttons, each pinned by a
   test that runs it through the core on Scala.js (`ExamplesSpec`), so an example cannot quietly
   stop showing what its label promises.
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

After the page loads it makes **one request per repository** to
`https://api.github.com/search/issues?q=author:kastoestoramadus+type:pr+repo:<owner>/<repo>&per_page=100`
and folds the answer into the snapshot (`Merge`, a pure function with tests: open stays open
unless the search reports it merged or rejected, released and closed stand, an unknown pull
request lands under "other recent work", a malformed item is dropped). The unauthenticated search
limit is 10 requests a minute per IP, and the answers carry
`access-control-allow-origin: *` (re-verified 2026-10-07), so no token is needed — and none may
be embedded in a public page. Each answer is cached in localStorage for ten minutes, behind a
guarded `try`, so reloading does not burn the limit and a browser without storage formats the
same.

Failure is quiet and visible: any non-200, a malformed body, or no `fetch` at all leaves that
repository's snapshot standing and the state line says what happened — "refreshed from GitHub",
"snapshot; GitHub did not answer for …", or "the shipped snapshot". The "checking GitHub…" line
always resolves, because every failure is an answer.

## Refreshing the snapshot

Run by hand, never in the build:

```bash
scripts/refresh-contributions.py
```

It asks the same searches through `gh`, diffs them against the shipped entries, and prints
ready-made Scala blocks for new pull requests, with the theme left to place. Move the release
dates forward when either repository publishes; that is what turns "merged upstream, not yet in a
release" into "in a release". Also update `Contributions.readOn` when you re-read the data.

## Testing

`sbt site/test` runs the suite on Node: the status model, the changed-line count, the refusal
sentences, the snapshot/live merge, the grouping, the fetch path against a fake `Http` and a fake
`Storage` (success, a 403, malformed bodies, a throwing storage), and the five examples against
the real core. The snapshot has integrity tests: every defect row resolves to an entry, every
note is a sentence.

The Laminar components themselves are not covered by jsdom tests: wiring the jsdom npm module
into `sbt test` would add a network-time npm dependency to a build that must not silently skip a
platform, for coverage that a real browser check gives back with interest. The page is therefore
verified by running it: served (`python3 -m http.server -d site/target/site 4001`) with the live
search answering, and from `file://` in a fetch-less browser where the snapshot must stand.

## Publishing

Not done yet, deliberately: DNS and Pages are the user's to switch on. When it happens, serve
`site/target/site` from the `gh-pages` branch (or a Pages workflow), keep the `CNAME`, and the
relative links do the rest. The hub, ww86.eu, needs only a card linking here.
