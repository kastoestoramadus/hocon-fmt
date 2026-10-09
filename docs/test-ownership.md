# Test ownership: ours, sconfig's, and what a release retires

The policy:

1. **Test only our domain.** A suite is ours when its subject is include masking, the comment
   carrier, a refusal or a verdict, the duplicate report, an option, a file write, the CLI, a plugin
   or the page. Everything else is sconfig's.
2. A case is **SCONFIG** when it passes or fails on bare sconfig — parse, render with the options
   `HoconFormatter` pins, no masking — even when it currently runs through our pipeline.
3. A case is **MIXED** when it is our verdict on top of a library behaviour; only the verdict half
   stays here.
4. SCONFIG cases move to `ekrich/sconfig` as tests-only PRs: no new dependencies, JVM/Scala.js/Native,
   the rules of its `AGENTS.md` and `docs/TESTING.md`.
5. A test that exposes a defect is added to `SconfigDefectsSpec` (red by design) and reported
   upstream; it is never worked around in `HoconFormatter`.
6. The red `library:` cases, and the refusals standing on them, stay here until a release turns them
   green — that is the release trigger, not a deadline.
7. Every sconfig patch this repository carries has an upstream retirement path, listed below.
8. The goal is few patches: the fork, the comment carrier and their tests go with the first release
   that carries `setKeepDetachedComments`.
9. A new suite names its owner in its header; a case that cannot be classified is ours, and says why.
10. Never duplicate upstream coverage; `#642` (parser bounds, renderer fixed point, origin lines)
    above all.

Evidence: counts are JVM runs on 2026-10-09 against sconfig 1.12.4 (`-Dmaven.repo.local=<task>/m2`);
bare-sconfig outputs are `probe/` in the task directory, seen through `scala-cli` with
`sconfig_3-1.12.4.jar` and the options `HoconFormatter` pins; the release check built sconfig main
`f351262a` (`sconfigJVM/publishLocal`, `++3.8.2!`) into a private Ivy home.

## 1. Inventory

Counts are executed cases. `libraryDefects` cases are excluded from `sbt test`; the site's
`ComponentSpec` 26 includes three style-switch cases driven by a loop.

| suite (where) | cases | class | notes |
|---|---:|---|---|
| `SconfigDefectsSpec` (core/shared) | 20 | SCONFIG | bare round trip; 19 red on 1.12.4, 14 on main |
| `KeepDetachedCommentsGuardSpec` (core/jvm) | 1 | SCONFIG | red until the option is released |
| `HoconSpecCoverageSpec` (core/shared) | 28 | 17 SCONFIG / 11 MIXED | normalised 8 + supported 9 SCONFIG; refusal groups 11 MIXED |
| `HoconFormatterInvariantsSpec` (core/jvm-native) | 22 | 17 OURS / 5 MIXED | marker 10, idempotence 3, re-parse 3, discovery 1; meaning 5 MIXED |
| `FormatterPropertiesSpec` (core/shared) | 9 | 8 OURS / 1 MIXED | 1000 generated documents × 8 option sets each |
| `DuplicateReportSpec` (core/shared) | 28 | 18 OURS / 10 MIXED | resolution assertions are sconfig's |
| `ExamplesSpec` (core/shared) | 29 | 22 OURS / 7 MIXED | one case per example (21) plus 8 suite tests |
| `IncludeDetectionSpec` (core/shared) | 23 | OURS | the detection contract |
| `IncludeOrderSpec` (core/shared) | 13 | OURS | moved include = our refusal |
| `IncludeCollisionSpec` (core/shared) | 235 | OURS | placeholder collision, every option set |
| `UnresolvedMergeSpec` (core/shared) | 24 | 8 OURS / 16 MIXED | variant rendering under include |
| `VerdictSpec`, `OptionsSpec`, `FormatOptionsSpec` (core/shared) | 30 | OURS | verdicts, pinned options, config parsing |
| `GoldenFileSpec` (core/jvm-native) | 3 | OURS | our output, byte for byte |
| `ShowcaseSafetySpec`, `TryAndFutureSpec` (core/shared) | 3 | OURS | |
| `CommentCarrierDefaultSpec` (core/default-shared) | 3 | OURS | the published no-op carrier |
| `CommentCarrierSpec` (core/site-shared) | 15 | OURS | the fork seam and its ledger |
| `FileFormatterSpec`, failure, consumer (cats, JVM) | 18 | OURS | writes, identity, atomicity |
| `CmdApiSpec`, `GitIgnoreSpec` (cli, JVM) | 64 | OURS | exit codes, walk, config lookup |
| zio suites (JVM; 24, with a property) | 24 | OURS | |
| `HoconFormatterJsSpec` (web, JS) | 8 | OURS | |
| site suites (JS) | 88 | OURS | page logic, merge, fake browser |
| java-api (Java/Kotlin) | 37 | OURS | |
| gradle functionalTest, maven `src/it`, mill, sbt scripted | 16 / 15 / 12 / 10 | OURS | integrations |
| `CliAcceptanceSuite` (acceptance) | 11 | OURS | processes, all three runtimes |

Core JVM totals 450 passing; with the two defect suites, 471, of which 20 are red today (19
`SconfigDefectsSpec` cases plus the guard; 20 defects on Scala.js, which has the nesting defect of
its own).

Evidence, one quote per class:

- **SCONFIG**, `HoconSpecCoverageSpec.normalised`: bare sconfig renders `// c\na : 1` as `# c\na = 1`
  and `a : { x : 1 } { y : 2 }` as `a {\n  x = 1\n  y = 2\n}` (`probe/bare-sconfig-1.12.4.txt`). The
  assertions are the library's, reached through our pipeline by convenience only.
- **MIXED**, `HoconSpecCoverageSpec.mustRefuse` "+=": bare sconfig renders `a : [1]\na += 2` as an
  unresolved-merge banner that does not parse again (`re-parses: no (String: 9: expecting a close
  parentheses ')' here, not: '${')`); the defect is upstream, the refusal is ours
  (`probe/bare-sconfig-mixed.txt`). A comment after the last field renders as plain `a = 1` with
  `kept: false` — `LostComment` is ours, the drop is sconfig's.
- **OURS**, `IncludeOrderSpec` "refuses a field after the include on its line" pins
  `Refusal.MovedInclude`, and `VerdictSpec` "a .json file is refused as JSON, not rewritten as
  HOCON" pins a name rule. Neither has a bare-sconfig counterpart: no library call produces either
  verdict.

## 2. Upstream candidates (tests to port, ranked)

Target files are in `sconfig/shared/src/test/scala/org/ekrich/config/impl/`. Sizes are the whole
change (one suite, tests only, shared source set). `#642` already owns parser bounds, the renderer
fixed point and origin lines; do not restate them.

| # | what to port | target file | size | value / cost |
|---|---|---|---|---|
| 1 | the 8 `normalised:` cases: `//`→`#`, `:`→`=` (and `:` on request), flattened paths, triple quotes, `+=` expansion, `1.5e3`→`1500`, `\u0041`→`A` | `ConfigDefaultRenderingTest` (colon case: `ConfigFormatOptionsTest`) | S, ~8 `@Test`s, ~70 lines | high: no upstream test pins number/escape canonicalisation or comment-marker conversion |
| 2 | the 9 `supported:` round trips: substitution, optional substitution, array/object concatenation, merging, dotted keys, null/booleans, trailing commas, empty collections | `ConfigDefaultRenderingTest` | S, ~50 lines | high: `checkEqualsAndStable` per construct; meaning equality after a render is only indirect today |
| 3 | the 10 resolution facts `DuplicateReportSpec` asserts: scalar replaces scalar, dotted replaces scalar and vice versa, object over a substitution merges, `+=` counts as substitution, an optional override only as the last definition | `ConfigSubstitutionSharedTest` | S, ~40 lines | medium: these decide our duplicate report; bare `resolve()` facts, no formatter code |
| 4 | the 5 `meaning preserved:` renderings from `HoconFormatterInvariantsSpec`: quoted keys, multi-line strings, substitutions, nesting, mixed types | `ConfigDefaultRenderingTest` (or extend `HoconPropertyTest`) | S, ~40 lines | medium: `#642`'s fixed point does not compare parse trees of input and render |
| 5 | the render-option defaults `OptionsSpec` relies on: comments on, JSON off, formatted, final newline, env values shown, empty text renders `{}` | `ConfigFormatOptionsTest` | S, ~30 lines | medium-low: pins defaults we pin again |
| 6 | the 9 still-red "render as the plain equivalent" cases | `ConfigDefaultRenderingTest`, with the fix | S each | blocked: they need the fix first; each is reported with the diff `SconfigDefectsSpec` prints |

Not portable, and why:

- `IncludeDetectionSpec`, `IncludeCollisionSpec`, `IncludeOrderSpec`, `IncludeMasking` cases: no
  include handling upstream; ours by the division of labour.
- The comment cases: blocked on `#646`/`#647`; a green test cannot be written before the option is
  merged.
- `GoldenFileSpec`, `ExamplesSpec` outputs: our pipeline's, byte for byte.

## 3. The red list: tests that must stay here

These look like sconfig's but pin **our** refusal of a defect. Each turns green, and the refusal is
dropped, only when the named upstream work is in a release.

| our tests (count) | pins | upstream | turns green when |
|---|---|---|---|
| `SconfigDefectsSpec` `shouldRenderLike` `+=`, self-reference, array/string concat (4) | correct render of an append or self-concatenation | `#598` merged 2026-09-30: the render now parses but stays unresolved | a release renders the resolved value (`a = [1, 2]`) |
| `shouldRenderLike` object concat, nested self-reference, substitution cycle (3) | correct render of a merge falling through a substitution | `#598` merged; the render now parses but shows both definitions; `#600` open | a release renders the merged value |
| `shouldRenderLike` fields sharing a line, fields of a one-line object (2) | a defined field order on one line | [Java #733]; the origin-line sort has no column, a fix must order by parse sequence | a release orders same-line fields |
| `shouldRenderLike`/`commentsWithNoFieldAfter`/`commentAboveABlankLine` (4) | a comment no field follows, a blank line detaching one | `#646` (issue), `#647` (draft) | a release keeps detached comments |
| `oneFieldObjectsInAnArray` (2), `envOverrides` (2) | rendered text parses and is a fixed point | `#598` merged, `#600` open | **already green on main**; a release containing `#598` |
| `rejectedOnInput` `[]` list suffix (1) | the specification's list expansion | `#605` merged 2026-10-06 | **already green on main**; a release containing `#605` |
| `should parse an array at the file root` (1) | a root array parses | none: the `Config` API returns an object | never, unless the API changes |
| `HoconSpecCoverageSpec` refusals `mustRefuse` (4), cycle (1), comments (6) | our `BrokenOutput`/`UnstableOutput`/`LostComment` verdicts on the above | as above | the corresponding release, then delete the case |
| `UnresolvedMergeSpec` (16) | the variant rendering of an unresolved merge under an include | `#598`/`#600` | a release formats those merges stably |
| `ExamplesSpec` catalogue refusals (7) | the pinned `now` of the defect examples | `#598`, `#605`, `#646`/`#647` | the examples move to `target` and the metadata's `upstream` note goes |
| `KeepDetachedCommentsGuardSpec` (1) | `setKeepDetachedComments` exists | `#647` | the released library carries the option; then the fork and carrier are deleted |

Checked on 2026-10-09 by running the two defect suites against sconfig main `f351262a`:
`Failed: Total 21, Failed 15, Passed 6` — of the 20 `SconfigDefectsSpec` cases, 14 stay red and **6
pass** (the four `#598` fixed-point cases, the `#605` suffix, and the JS-only nesting case that
already passed on the JVM). Nothing in `#590`/`#595`/`#596` (merged, unreleased) turns a red case
green, and the full `coreJVM` run on main fails exactly where a release will force edits:
`HoconSpecCoverageSpec` 5/28, `UnresolvedMergeSpec` 16/24, `ExamplesSpec` 8/29.

## 4. Patch inventory: everything carried because of sconfig

| patch | cost | upstream | release | removal |
|---|---|---|---|---|
| `scripts/fetch-sconfig-fork.sh` (34 lines, sbt 2 build, pinned sha) | a clone + JS publish per sha; a CI cache keyed by the script's hash | `#647` draft | with `setKeepDetachedComments` | delete script, `sconfigFork`, `checkSconfigFork` and the two workflow steps — [site](site.md#returning-to-upstream-sconfig) steps 1–2 |
| `coreSite` project, site wiring (48 build lines, 6 `coreSite` sites) | a second core dependency graph and suite | `#647` | same | point `site` at `coreJS`, drop from aggregates — step 2 |
| `CommentCarrier` (330 lines), `Carried` (15), `core/default-shared` (17), the call sites | the seam plus its two halves | `#647` | same | enable the option in `parseOptions`, delete the seam and `Variant` — step 3 |
| `CommentCarrierSpec` (154), `Variant` ledger (41), `KeepDetachedCommentsGuardSpec` (27) | 18 ledger entries to keep in step with the examples | `#647` | same | delete with the seam — step 4 |
| Refusal paths for library defects: `BrokenOutput`, `UnstableOutput`, `LostComment` checks and their docs | stays as the safety net; the red list above is what would shrink | `#598`/`#600`/`#605`/`#647` | one release at a time | the checks never go: a release only stops them firing for the retired cases; update `limitations.md`, the red list and the examples' `upstream` notes |
| `MovedInclude`/`LostInclude` guards, `IncludeMasking` (327 + 101 lines) | ours, kept forever: include handling is ours by the division of labour | [Java #733] relates, no fix | — | none |
| Pinned `sconfig = "1.12.4"` in `build.sbt` | one line, plus the artifact set | releases v2.0.0 and later | each release | bump; a release containing `#598`/`#605` also drops the refusals named above |
| Documented normalisations (`//`→`#`, flattening, canonical numbers, LF/no BOM, inline comments moving) | none: renderer behaviour we ask for | none | — | never; they are the pinned style |

[#733]: https://github.com/lightbend/config/issues/733

## 5. Porting backlog

In order; each is one upstream PR, tests-only, shared source set, `checkEqualsAndStable` for
rendering where it applies.

1. The 8 normalised cases (S) — highest value: new upstream coverage for the renderer's
   canonicalisation.
2. The 9 supported round trips (S).
3. The 10 resolution facts (S) — a test per precedence shape, no formatter involved.
4. The 5 meaning-preserved renderings (S) — after checking `#642` keeps the fixed point and this
   keeps equality.
5. The 5 render-option defaults (S).
6. The 9 red cases as fixes arrive, test and fix in the same upstream PR (S each), in this order:
   `#598` shapes first (already merged; verify on a release), then the same-line ordering, then the
   comment cases on `#647`.

Each ported case is deleted here in the same change as the release that carries its upstream side;
until then the red list is the release checklist.
