# Safety checks, tests and integration APIs: prettier, black, rustfmt, gofmt, scalafmt, ruff

Pass B of the formatter research (safety and UX slice). Pass A profiles each tool's bug classes from
its tracker; this file covers the other three questions the owner asked: **(a)** the safety checks and
test architecture as implemented (idempotence, AST equivalence, corpus runs, known-bad ledgers), **(b)**
the integration APIs (CLI, config discovery, pragmas, range formatting, style pinning, pre-commit,
LSP/build tools) and what users asked for most, and **(c)** the fixes in each tool's recent history
that a formatter built on someone else's parser should learn from.

Every claim below was settled by reading the cloned source or running `git`/`gh` in it on 2026-10-09;
paths are repo-relative and line numbers are from the clones named in the snapshot. Nothing here was
taken from memory, and issue/PR numbers appear only where a commit message or `gh` output carried them.
This research adds no tests to the repository; the case tables in §3 are proposals.

## Snapshot

| tool | repo | HEAD in this research | license | what it is |
|---|---|---|---|---|
| prettier | github.com/prettier/prettier | c3d36525, 2026-10-09 | MIT | JS/TS/… opinionated formatter, `--debug-check`,
 `--range-start/--range-end`, plugin API |
| black | github.com/psf/black | c9b1148, 2026-10-07 | MIT | Python; `assert_equivalent`/`assert_stable`, `--line-ranges`, `--required-version` |
| rustfmt | github.com/rust-lang/rustfmt | 9f96727, 2026-10-08 | MIT OR Apache-2.0 | Rust; `--emit`, `--file-lines`, `style_edition` |
| gofmt | github.com/golang/go (`src/cmd/gofmt`, `src/go/printer`) | 72edc307c, 2026-10-09 | BSD-3-Clause | Go; deliberately no config, no pragma, corpus idempotence test |
| scalafmt | github.com/scalameta/scalafmt | 91694e7d, 2026-10-08 | Apache-2.0 | Scala; `.scalafmt.conf` with pinned `version`, bitmask exit codes, community corpus suites |
| ruff (`ruff format`) | github.com/astral-sh/ruff | 5b27c1ccf, 2026-10-09 | MIT | Python; formatter stable since 0.9, per-fixture stability check, `ruff server` LSP |

Clones are shallow (`--shallow-since=2022-01-01`, `--filter=blob:none`), so §2 sees 2022 onward only;
where a bug predates the clone, the commit that fixed it is still in range and says so.

## 1. Safety checks as implemented

### 1.1 What each tool actually runs on its fixtures

| check | prettier | black | rustfmt | gofmt | scalafmt | ruff |
|---|---|---|---|---|---|---|
| second pass identical (idempotence) | yes, every fixture, `FULL_TEST` only | `assert_stable`, every file unless `--fast` | `idempotence_tests` + self-test | golden re-print + corpus double-format | property suite + corpus (`*2` passes) | yes, every fixture |
| meaning/AST equivalence | yes, every fixture, `FULL_TEST` only | `assert_equivalent`, unless `--fast` | — (parses output; AST not compared) | — (parse only) | `assertFormatPreservesAst` in the property suite | `ensure_unchanged_ast` per fixture, two documented normalisations |
| golden/snapshot files | snapshots, `.snap` | `# output` in `tests/data/**` | `tests/source`→`tests/target` | `testdata/*.golden` | `*.stat` resources with `<<<` headers | `insta` snapshots, in-tree (`tests/snapshots`) |
| CRLF test over every fixture | yes | cases only | cases + `newline_style` config | `crlf.input/.golden` + `TestCRLF` | **yes**, every case re-run with CRLF (`[WIN]` names) | `carriage_return/**` fixtures |
| BOM test over every fixture | yes | none found at HEAD | — | no fixture (the scanner skips BOM) | — | none found |
| range formatting excluded from checks | yes (`test-second-format.js` skips) | yes (`assert_stable` returns early, issue #4033) | no — the ungated phases were the bug (79a23d2) | — | — | yes (`fixtures.rs:92`) |
| corpus over real projects | external repo `prettier/prettier-regression-testing` | `diff-shades` over open-source projects, posted as a PR comment | `tests/coverage` + self-format + `check_diff/` | all of GOROOT (`long_test.go`) | `scalafmt-tests-community` (pinned repos) | `scripts/formatter_ecosystem_checks.sh` (Black similarity) |
| known-bad ledger that fails when fixed | `failed-format-tests.js` (15 entries) | — | — | one whitelisted file (`issue22662.go`) | budgets in `ScalafmtProps.main` | — |
| fuzzing | — | `scripts/fuzz.py` (Hypothesis/Hypothesmith), cron `fuzz.yml` | — | — | — | cargo-fuzz targets for the formatter, not run in CI |

Roughly: **prettier and ruff check idempotence and meaning on every fixture they own** rather than in a
separate suite, **gofmt and scalafmt run the check over a whole corpus**, and **prettier is the only one
that fails when a known-bad case improves** (see 1.2).

### 1.2 The mechanisms worth copying, with their sources

- **Per-fixture second pass, opt-in for the whole corpus.** prettier's runner puts six checks around
  one fixture — `format`, `ast compare`, `second format`, `end of line (CRLF)`, `end of line (CR)`,
  `BOM` — in `tests/config/format-test/run-test.js:53-90`; `ast compare` and `second format` are
  skipped unless `FULL_TEST` is set (`:54`, `:60`), while `test-ast-compare.js:24-30` asserts
  `expect(formattedAst).toStrictEqual(originalAst)` and `test-second-format.js:41-44` asserts
  `expect(secondOutput).toBe(firstOutput)`. ruff makes the second pass unconditional for every fixture
  (`crates/ruff_python_formatter/tests/fixtures.rs:101,374` calling
  `ensure_stability_when_formatting_twice` at `:386`), and skips it only where the ranges are unknown
  (`:92`).
- **A ledger of known-bad fixtures that cannot rot.** prettier's `failed-format-tests.js` holds maps of
  `unstableTests` and AST-unstable files; when a listed file starts passing,
  `test-second-format.js:29-32` throws `Unstable file '<path>' is stable now, please remove from the
  'unstableTests' list.` scalafmt's budget is the weaker form: `scalafmt-tests/jvm/src/test/scala/org/
  scalafmt/ScalafmtProps.scala:101-103` asserts `idempotency <= 41`, `searchState <= 34`,
  `badOutput <= 4` over its fastparse corpus, which fails when the count grows but passes silently when
  it shrinks — a regression tripwire, not a ledger that forces the improvement to be recorded. Ours has
  the same property as prettier's in the reverse direction: `SconfigDefectsSpec` fails when *sconfig*
  improves, and prettier's fails when *they* improve.
- **Corpus-wide idempotence with a named exception.** `src/cmd/gofmt/long_test.go:56-97` formats every
  `.go` file under GOROOT twice and errors with `gofmt %s not idempotent`, except one file it logs as
  a known bug: `if strings.HasSuffix(filename, "issue22662.go") { t.Log("known gofmt idempotency bug
  (Issue #24472)") }`. A single named exception in a corpus run is the cheapest honest form of this
  check; ours would be `reference.conf`-style files, where the corpus run currently only reports
  comments lost.
- **Golden output must itself be a fixed point.** `src/go/printer/printer_test.go:38,133-143` has an
  `idempotent` test mode: the golden file re-printed must equal itself (`"golden is not idempotent: %s"`),
  so a golden that pins unstable output cannot be committed. Idempotence is therefore checked on the
  *expected* text, not only on the input — a property our `GoldenFileSpec` could assert in one line
  (`format(expected) == expected`) without re-running the formatter's input path.
- **A corpus run pins work, not only output.** scalafmt's `CommunitySuite` clones each repository at a
  pinned ref and asserts three things: zero errors, the exact file count
  (`stats.checkedFiles == build.checkedFiles * 2`, i.e. two passes per file), and, on non-Windows, the
  exact number of search states per style (`assertEquals(statesVisited, x.expectedStatesVisited)` from
  `TestStats.Style`). The state count is a deterministic work budget: it fails on a change that only
  makes formatting slower. We have benchmarks (reported, never gating); a state/size pin on a corpus
  fixture would be the gateable version of it.
- **A range-formatting request disables the second pass by necessity.** prettier skips `second format`
  for cases with `rangeStart`/`rangeEnd`/`cursorOffset` (`test-second-format.js:57-64`) and ruff states
  why for ranges (`fixtures.rs:92`). If range formatting ever comes to hocon-fmt, its stability
  story has to be defined before the second pass can cover it.

### 1.3 Where hocon-fmt stands, and the three gaps

Ours already covers the two strongest patterns in different words: `HoconFormatterInvariantsSpec`
(idempotence, output re-parses, meaning preserved), `FormatterPropertiesSpec` (1000 generated documents
per platform, seed-printing failures) and `ExamplesSpec` (every catalogued input's exact output and
verdict, on all three platforms). The gaps this research suggests, in order of value:

1. **A corpus idempotence run over real files**, as a separate non-gating-but-red-when-broken suite
   (gofmt's model): format each file of a pinned reference-config corpus twice and require equality,
   reporting refusals separately instead of failing on them. Our properties cover generated documents;
   the corpus cases that found the real refusals (blank-line comments, env overrides) came from ad-hoc
   sweeps, not from a suite.
2. **An `expected` fixed-point check in `GoldenFileSpec`**: `format(expected) == expected` for every
   golden, no input re-run.
3. **A budget for the corpus run** (scalafmt's `main`): counts of known non-idempotent or
   lost-comment documents asserted with `<=`, so a regression in our own code fails the suite. It does
   not force an improvement to be recorded (that is prettier's ledger), but it is the cheap tripwire
   the corpus run needs on the day it is added; `SconfigDefectsSpec` already fails on the reverse event
   (sconfig improving).

## 2. History: the fixes that instruct

Window 2022-01-01 to 2026-10-09 (the shallow clones' range); every row was read with `git show` at
that sha. Commit URLs are `<repo>/commit/<sha>` and issue/PR numbers in a subject come from the
subject itself. Rows are chosen for what they say to a formatter that renders from someone else's
parse tree and refuses rather than corrupts.

### 2.1 prettier — comments and exit codes

| date | sha | what broke (exact subject) | minimal trigger | root cause → lesson |
|---|---|---|---|---|
| 2026-06-03 | 794ca266 | "Fix unstable comments around parenthesized expressions (#19273)" | `// prettier-ignore` over `(a = 1);` | attachment changed and the fix *deleted* an entry from the unstable list → a fix that shrinks the ledger is a red flag, and this one was reverted 13 min later |
| 2026-06-03 | 121d0d3d | `Revert "…" (#19274)` | same | revert restored the entry and the fixture → comment fixes regress fast; land them *with* the ledger, never by deleting from it |
| 2026-07-26 | b9231386 | "Fix embedded template literal idempotency (#19725)" | `html`${getText({...})}`` printed a different indent on pass 2 | the embedded fragment's indentation base had itself changed → the second pass must be run on masked/embedded pieces, not only on the whole file |
| 2026-08-15 | d753f456 | "Avoid corrupting Markdown URL with `<`, `(`, `\` (v2)" | a URL needing both `<>` wrapping and `\` escaping | the escaper dropped a needed backslash; the "(v2)" is a first attempt that was wrong too → escaping/quoting is where silent corruption lives |
| 2026-09-11 | c150b07c | "Handlebars: Fix invalid output for `<style>` containing mustaches (#20030)" | `color: {{foo}}` inside embedded CSS printed `color:{{foo}}}` | the embed path read `{{…}}` as CSS text → every embed/mask boundary needs a print-then-reparse check |
| 2026-09-22 | 1a36a7de | "Support configuration files with UTF-8 BOM (#20127)" | BOM at the start of `.prettierrc.json` | the loader parsed raw bytes; fix added `hasBom`/`stripBom` | a BOM is not only an input-file problem: config, ignore and style files carry it too (relevant to `.hocon-fmt.conf`) |
| 2023-08-30 | 094b30c0 | "Use `process.exitCode` instead of `process.exit()` (#15327)" | `--cache` with stdin | `process.exit(2)` truncated pending output → never exit mid-run; set the code and return |
| 2025-06-02 | d8ab2b1f | "Fix exit code and result message when parser can not infer (#17505)" | `--check` on a file with no inferable parser printed "All matched files use Prettier code style!", exit 0 | the error branch returned before the counters → an error path that does not feed the summary makes CI green on a broken run |
| 2026-08-18 | 45a4a75e | "Fix range formatting expanding to a non-source-element node (#19880)" | range over two indented lines produced `};);` | the range widened to a node not in the source → range mode needs its own output-validity check (if we ever get one) |
| 2025-10-31 | 48100c32 | "Fix duplicated comments on `prettier-ignored` super class (#18168)" | `class A extends /* a */ /* prettier-ignore */B {}` | the ignored-node path printed retained comments twice and manually marked them printed → an "ignore" branch needs its own comment bookkeeping, as our masking does |

**Recurrence:** comment placement/stability is the largest class (794ca266, 121d0d3d, 5a0fdd97,
24a616d0, 52d17053, 48100c32); corruption under escape/embed repeats (d753f456, c150b07c, b3973c02);
process/exit-code mistakes repeat (094b30c0, d8ab2b1f). At HEAD, 15 fixtures sit permanently in
`tests/config/format-test/failed-format-tests.js:5-34` under a TODO, and the AST-instability map is
empty — the ledger is the product feature that keeps those 15 from being forgotten.

### 2.2 black — comments, safety checks, encodings

| date | sha | what broke (exact subject) | minimal trigger | root cause → lesson |
|---|---|---|---|---|
| 2024-02-12 | 8af4394 | "fix: Don't remove comments along with parens (#4218)" | a comment attached to a redundant paren while the parens are made invisible | the paren leaf was blanked and the comment prefix moved only sometimes → deleting a token is not deleting its trivia; move trivia explicitly, and let the output check catch what you miss |
| 2024-03-01 | e4bfedb | "fix: Don't move comments while splitting delimiters (#4248)" | a line that must be split exactly at a comment | the splitter rebuilt the line without re-attaching comment leaves in order → a layout pass must carry comments as first-class items |
| 2024-03-22 | 836acad | "Improve AST safety check (#4290)" (CHANGES: "Fix unwanted crashes caused by AST equivalency check") | a standalone string expression whose indent is normalised | the comparison folded whitespace only under some parents → an over-strict equivalence check is a false-refusal machine; encode exactly what the formatter may change |
| 2024-05-15 | 9c1fd46 | "Make sure Black doesn't crash when `fmt:off` is used before a closing paren (#4363)" | `# fmt: off` immediately before `)` | ignored-node generation ran on a leaf inside closing brackets → a keep-verbatim directive must degrade to a no-op on nonsense placement, never crash |
| 2025-06-22 | 60d734a | "Fix backslash carriage return comments (#4663)" | backslash, lone `\r`, then a comment | `re.split("\r?\n", …)` never saw a bare `\r` as a line end → treat CR, CRLF and LF as line ends at the boundary |
| 2025-11-22 | efad911 | "Fix comments being removed before fmt:off/on blocks (#4845)" | a comment immediately before `# fmt: off` (Jupytext `# %% [markdown]`) | the standalone prefix was cut at the wrong offset → "keep this region" must include the comments leading into it |
| 2026-01-23 | fe875c0 | "Don't double-decode input, causing non-UTF-8 files to be corrupted (#4964)" | a file whose in-band encoding declaration is not UTF-8 | a decoded `str` was re-encoded and then decoded again from the declaration → decode once at the byte boundary; an in-band hint must never re-decode text |
| 2026-07-21 | 42ed836 | "Fix unstable formatting with comments on optional parens (#5241)" | inline comment on the opening paren of an optional-paren RHS | omitting invisible parens re-parented the comment on the next parse → test stability on the parse of the *output*; forbid transforms that move trivia across a re-parse |
| 2026-10-04 | 34bfc69 | "Fix duplicated comments when string_processing rewrites a line (#5449)" | inline comment on a line that string_processing rebuilds | `new_line.comments = line.comments.copy()` plus appending leaves again → comments need one owner; clone-then-append duplicates them |
| 2026-10-05 | a3cb9e1 | "Fix `--line-ranges` crash on `# fmt: skip` before a closing bracket (#5477)" | `--line-ranges` with an unselected statement ending in `# fmt: skip` right before `)` | `str(node)[:-1]` assumed a trailing newline, swallowing the bracket into the comment → range/partial modes have their own trivia hazards; black still skips the stability check for ranges |

**Recurrence:** trivia lost/moved/duplicated is by far the largest class (8af4394, e4bfedb, 34bfc69,
efad911, 9f9c675, c970a49, 5809338, 18c17be, 5ee5541, 59f16b5); `fmt:` directive mishandling often
crashes (9c1fd46, 6ea4edd, 6a1c077, 1b342ef, f37758e, 0f376e0, 2a45cec); encoding/EOL/BOM repeats
(fe875c0, b801776, dd76ff8, 626b32f, 60d734a); stability repeats (42ed836, a24e1f7, 95e77cb, 32dd9ec,
159984a); the AST checker itself caused false alarms (836acad, f4b006f, 011942a — #5271 records that
`type: ignore` is per-line, so merging comments dropped an entry).

### 2.3 rustfmt — spans, panics, and range phases

| date | sha | what broke (exact subject) | minimal trigger | root cause → lesson |
|---|---|---|---|---|
| 2026-06-23 / 2026-08-07 | 1595b5e + 44ca78d | "[Test] Add failing regression test for non-idempotent block doc comments" then "[Comment] Make block doc comment closer rewrite idempotent" | `/**First comment.\n*/` before an item: pass 1 aligned `*/`, pass 2 added a space | the closer's indent came from a different notion of indent than the opener → the test-first commit is the model: land the reproducer red, then fix, and keep it in the `tests/target` tree that must be a fixed point |
| 2023-09-20 | a1fabbf | "Bugfix/comment duplication (#5913)" (fixes #5871) | a parenthesised expression with a comment | a pre-span pointed at the original node, not the rewritten one → every span used to recover or re-insert a comment must point at the node actually printed |
| 2025-06-11 | 334670e | "fix: skip removing `self` in imports if they are stacked like `use self::self;`" | `use self::self::self;` | each run normalised one more `self` (`use self::self;` → `use self;` → removed) → a normalisation that shrinks monotonically can never be idempotent; leave the pathological form alone |
| 2024-11-17 | 68099e2 | "fix panic on failure to format generics in enum" (issues 5738, 6137, 6318, 6378) | an enum with generics whose rewrite fails | `unwrap()` on a failed rewrite instead of propagating `RewriteError` → on any rewrite failure fall back to the original snippet (our refuse path); never unwrap |
| 2026-08-14 | 6d6cd71 | "fix: prevent char boundary panics when reporting formatting errors" (fixes 6850) | a tab or non-ASCII line with `error_on_line_overflow` | a display-column range was used as byte offsets, slicing mid-character → diagnostics need their own tests; a panic in the reporting path hides the error |
| 2026-08-01 | 8f2a9c3 | "Reject `--config=emit_mode=...` when `--check` is set" (regression #6999) | `rustfmt --check --config emit_mode=files f.rs` | the `--emit`+`--check` rule was enforced per flag, so the config route bypassed it and wrote the file → decide "check never writes" once, after every config source is merged, and test each source with the same dangerous setting |
| 2026-03-24 / 2026-04-17 | 79a23d2 + e28eb9e | "Respect `--file-lines` when making various whitespace-related changes." / "Don't format statements that are outside of the selected file-lines range" (#5136, #6867) | leading newlines, missing final newline, space after a doc comment, all outside the range | whitespace/EOF fixups ran after range masking and never consulted `file_lines` → in range or in-place modes every phase, including final newline/blank-line tidy-up, must be gated by the same selection |
| 2026-10-01 | 3297756 | "fix(items): format comments after where using clause_shape budget" | a comment following a `where` clause | the comment was formatted without the width budget the clause had already spent → comments consume width; measure the line with the comment included |
| 2024-12-11 | 6f7aeed | "fix: wrap_comments creating invalid code blocks" | a doc comment longer than `max_width` followed by a code fence, with `wrap_comments = true` | the wrap dropped the separator newline and glued prose to the fence → test option *interactions*; valid-in-invalid-out lives in the product of options |
| 2023-07-05 | f2bad9c | "fix: handle skip_macro_invocations from config file" (issue-5816) | `skip_macro_invocations=["*","println"]` in `rustfmt.toml`, not `--config` | a derived `Deserialize` did not match the config-file string form → a setting must be tested through every source (flag, config file, defaults); same value, different parser |

**Recurrence:** non-idempotent comment rewrites (1595b5e, 44ca78d, a1fabbf, 334670e); panics on
unusual-but-valid input (68099e2, 6d6cd71, 7e56db3, 53bf773 — and ICE issues 7033, 7034, 7158 still
open at HEAD); valid-in-invalid-out by token removal (6f7aeed; open 7058 "comma is removed from
`#[cfg_attr(` which causes syntax error"); range phases not gated (79a23d2, e28eb9e); one option's
check defeating another's (open 6954 "`rustfmt::skip` sometimes ignored when using
`error_on_line_overflow` and `error_on_unformatted`").

### 2.4 gofmt — comment text is API, and platform math is a bug

| date | sha | what broke (exact subject) | minimal trigger | root cause → lesson |
|---|---|---|---|---|
| 2022-01-29 | 078cc6a04 | "go/printer: format doc comments" | any doc comment: text is reflowed into canonical godoc form (proposal #51082) | a new `formatDocComment` in `src/go/printer/comment.go` applies go/doc/comment while printing → rewriting comment *text* is a proposal-level, versioned change; "keep the text or refuse" is the conservative opposite |
| 2022-04-11 | 0605bf605 | "go/ast, go/printer: recognize export and extern line directives" | `//export foo` printed as `// export foo` | `isDirective` knew only `//line `, so a directive was treated as prose → recognise directive-shaped comments before any normalisation; HOCON has none, so never reflow or merge a comment |
| 2022-05-24 | 557244cef | "go/printer: if comment gets formatted away, don't squash" | a doc comment deleted entirely left the following declaration glued to the previous line (#53059) | an early return on the empty formatted comment skipped the prefix writer → a pass that can *delete* a comment must leave surrounding layout untouched |
| 2023-05-15 | b9baf4452 | "go/printer: error out of Fprint when it would write a '//line' directive with a multiline file path" | `SourcePos` mode and a filename containing `\n` (#60167) | `//line` cannot escape newlines, so the printer returned an error and **no output** → refuse (error, zero bytes) rather than emit text that cannot mean the input: the direct precedent for `broken-output` |
| 2024-09-04 / 09-10 | c3f16307b then ad6ee21bb | "go/printer: do not treat comments inside a ast.Decl as godoc" then `Revert "…"` ("it turned out to also introduce a change to the formatting as described in issue #69382, which wasn't intended") | a `//go:directive2` comment block between two text lines inside a decl changed again on the second pass | an idempotence fix was reverted rather than ship unrelated churn → reverting is a valid outcome, and the golden tests caught it |
| 2025-10-06 | d945600d0 | "cmd/gofmt: change -d to exit 1 if diffs exist" (#46289) | `gofmt -d file.go` with a diff exited 0 before | a sentinel `errFormattingDiffers` maps to exit 1, other errors to 2; `-l` still does **not** change the exit code (`src/cmd/gofmt/gofmt.go:287-291`) → exit codes are user-visible API; #76405 ("proposal: cmd/gofmt: also exit 1 for `-l`") is open |
| 2026-03-09 | a8f99ef1f | "go/printer: use architecture-independent math for alignment decisions" | `math.Exp(math.Log(95))` is exactly 95.0 on arm64 but 94.999… on amd64, flipping a column decision (#77959) | floating-point heuristics made formatting platform-dependent → for a cross-built formatter (JVM/JS/Native) every layout decision must be exact/integer |
| 2026-05-29 → 06-24 | 5f711bfdb, eb343e2fa, 0af88e584 | "cmd/gofmt: fix symlink file truncation"; `Revert "…"` ("Doubts about correctness of fix. Will revisit."); "cmd/gofmt: correctly truncate symlinks instead of buggy mangling" | `go fmt` on a symlink compared written bytes to the *link path length* (#79735) | the fix was reverted the same day and redone three weeks later with a script test → file-writing edge cases need their own tests, and a rushed fix can be worse than the bug |

**Recurrence:** comment/doc-comment handling (078cc6a04, 0605bf605, 557244cef, c3f16307b,
ad6ee21bb); invalid output for synthetic trees (ff7986d67, 0c64ebce7, 700920bbb); write-path safety
(2693ade1f, 5f711bfdb, eb343e2fa, 0af88e584); reverts after long-test/unintended-churn failures
(267b50a83, ad6ee21bb, eb343e2fa).

### 2.5 scalafmt — rewrites against comments, bit-flag exit codes

| date | sha | what broke (exact subject) | minimal trigger | root cause → lesson |
|---|---|---|---|---|
| 2022-01-17 | 96173fac | "Scalameta: upgrade to 4.4.33 (scala3 comment fix)" | scala3 `if`/`else` with comments in optional braces | the comment placement lived in scalameta's tokenizer/parser; the fix is a dependency bump → comment bugs are often upstream: pin the exact parser version and keep boundary cases in your own suite (our `SconfigDefectsSpec` discipline) |
| 2022-02-05 | 3d22d640 | "Format{Ops,Writer}: fix trailing comment indent" | a trailing `/* … */` after the last token of a body | expiry used the pre-comment token and the writer took `max` of two indents → a trailing comment is part of the construct; compute expiry and indent against the next non-comment token |
| 2024-10-02 | 47edbafb | "RedundantBraces: keep if a comment is attached" | redundant braces whose comment is attached to the block | `okComment` only considered a previous non-trailing comment, so the brace could go and re-anchor its comment → a rewrite that removes delimiters must treat comments as content and refuse to remove |
| 2024-11-07 | 471be92d | "Router: don't fold after single-line comment" | `x => … // comment` with the closing `)` folded onto that line | folding after a `//` comment put code on the comment's line and the output no longer parsed; the old expected text literally read "test does not parse: … `)` expected but `end of file` found" → never split code onto a single-line-comment line; check the output parses, not only that it differs |
| 2025-01-30 | 1c91ef2a | "RegexCompat: fix comment patterns for `\r`" | any CRLF file (comment wrapping/trailing-space handling) | the patterns matched `\h+$`/`\n` without `\r`, on three engines → CRLF is a first-class test axis per platform, not a corner case (our walk mirrors this: every case runs on JVM/JS/Native) |
| 2026-01-14 | dac17483 | "FormatWriter: fix formatting of multiline comments … would occasionally match, after the leading asterisk, a newline and thus would jump over the nex line" | a multiline comment whose lines do not all start with `*` | `[*][^*]` could consume the newline → a regex over comment text that consumes delimiters drops content; add end guards and one case per comment shape |
| 2026-01-30 | 9b8b2ed7 | "RedundantParens: keep parens if comment before `)`" | paren removal with a comment before the closing paren | the removal path checked only trailing commas; the fix extracted `okCommentBeforeClose` and reused it in `RedundantBraces` → check comments at both delimiters before removing anything |
| 2026-07-25 | 89a7c620 | "Don't let a tolerated error mask a real one" ("A parse error is reported but doesn't fail --test (#1835) … also threw away a TestError earned by a different, perfectly parseable file … then exited 0") | one unparseable file plus one misformatted file in `--test` | tolerated-error handling reset the whole exit code to `Ok`; the fix clears only the `ParseError` bit → exit codes as bit flags: forgiving one file must not forgive another file's failure |
| 2026-07-25 | 61602058 | "ScalafmtRunner: report the failure it exited on" (`--check` "reported an arbitrary subset — 8 to 18 of 20 misformatted files across five runs") | `--check` over 20 misformatted files | every file that raced in before the fail-fast latch printed its diff → fail-fast must bind the reported file to the code you exit with; test the report, not only the code |

**Recurrence:** comment-preserving rewrites (47edbafb, 9b8b2ed7, 0e49db12, e90bdd56); comment
placement in Router/Writer/Splits (3d22d640, dac17483, 885eb5db, 0b706660); non-idempotence first
recorded by a test then fixed (713e9c4d, bf3b152a, 87f10c4f); CLI exit-code/reporting defects
(89a7c620, 61602058, 1b7452e2); cross-platform comment regexes (1c91ef2a, dac17483). scalafmt's test
suite once *encoded* unparseable output as expected text (`test does not parse: …`); hocon-fmt's
`broken-output` refusal is what makes that class impossible here.

### 2.6 ruff — attachment drift, and a check that is not in CI

| date | sha | what broke (exact subject) | minimal trigger | root cause → lesson |
|---|---|---|---|---|
| 2024-02-02 | 4f7fb566f | "Range formatting: Fix invalid syntax after parenthesizing expression (#9751)" | `--range` over an expression the formatter must parenthesize | range printing sliced the source map at the wrong place when the range layout differed from the whole-file layout → range mode is a second printer; a safety check that runs only on whole files does not cover it |
| 2024-03-09 | 4bce80106 | "Fix unstable with-items formatting (#10274)" | a `with` item that may be parenthesized (issue 10267) | the formatter flipped between two alternative shapes whose layouts were incompatible → idempotence is a property of the set of alternatives, not of one output: alternatives must agree before they are chosen between |
| 2024-05-31 | 9b6d2ce1f | "Fix incorect placement of trailing stub function comments (#11632)" | `.pyi` stubs with a comment between one-line `def`s (issue 11569) | comment attachment treated the trailing-stub case like an ordinary statement slot → a comment on the wrong node is not lost, it is moved: check placement, not only presence |
| 2024-06-05 | 5806bc915 | "Fix formatter instability for lines only consisting of zero-width characters (#11748)" | a docstring line whose only content is U+0015 | the printer decided "line is empty" by `line_width > 0` rather than by the emitted buffer, so zero-width characters produced double newlines → measure the text you emitted, not a derived width |
| 2025-02-18 | 31180a84e | "Fix unstable formatting of trailing end-of-line comments of parenthesized attribute values (#16187)" | `(a.b)  # trailing` inside a parenthesized expression | attachment was decided in one place and printed in another and the two drifted → decide attachment once and make both passes read the decision |
| 2025-11-17 | 8156b4517 | "Avoid syntax error when formatting attribute expressions with outer parentheses, parenthesized value, and trailing comment on value (#20418)" | `variable = (something  # comment` / `.attr` (issue 19350) | a "needs parentheses" answer of `Never` ignored that a trailing comment forces a line break → a parenthesis decision must account for comments that force breaks: comments change whether the output parses |
| 2026-05-26 | 9c6536e73 | "Fix invalid IR when flattening nested `BestFitting` elements (#25398)" | a lambda in an f-string interpolation (issue 25397) | a lowering pass inlined a nested element's `Start`/`End` tags → IR invariants must hold under every lowering pass; a "simplify" pass is where unmatched brackets hide |
| 2026-07-06 | 40a62bc69 | "Excessive whitespace trimming with comment formatting (#26455)" | a comment whose text begins with a tab (`#\tTabbed`) | `normalize_comment` called `content.trim_start()` on text it did not own → never normalise inside opaque user text; the `#` and the one space after it are yours, the rest is not |

**Recurrence:** comment attachment/placement drift (954a48b12, 9d705a441, 9b6d2ce1f, 31180a84e,
cbc6863b8, 8156b4517, f3714fd3c, 0bec5c036); idempotence/instability (4bce80106, 5806bc915, 31180a84e,
1ecb7ce64, 954a48b12); panics on exotic input (cbc6863b8, 8b630c748, 94b87c00e); output that does not
parse (4f7fb566f, 8156b4517, c84c690f1, 02f81b1ad, and the test-side guards 9cd0cdefd, 4b0fa5f27);
pragma semantics fixed three times (60ba7a7c0, 62343a101, 880513a01).

Two caveats about ruff's own checks, read from source (not run):
`crates/ruff_python_formatter/src/comments/mod.rs:362-386` makes `assert_all_formatted` a no-op in
release builds, so the comment guard exists only in debug; and
`fuzz/fuzz_targets/ruff_formatter_validity.rs:71-74` asserts
`linter_result.has_invalid_syntax()` for the *formatted* output under the message "formatter
introduced a parse error" — inverted, since `has_invalid_syntax` is `!has_valid_syntax`
(`crates/ruff_linter/src/linter.rs:53-55`). The assertion has been there since the file was added
(14d3fe6bfa, #9448, 2024-01-11), the only later changes to it are rustfmt/CI-formatting commits, and
no workflow runs the formatter targets: `.github/workflows/daily_fuzz.yaml` fuzzes the Python parser
under `python/py-fuzzer`, and CI only *builds* the cargo-fuzz targets (`ci.yaml:619`). A check that
cannot run is a comment.

## 3. Test inputs to translate to HOCON

Case tables: `\n` in the HOCON column is a newline in the file, `\u00a0` a non-breaking space; the
HOCON is written here, not copied from the fixture. Expected behaviour is one of `formats` (rewritten,
possibly with normalisations), `unchanged` (output equal to input) and `refuses:<kind>` using the
kinds in `Refusal`. Rows marked **(probe)** rest on documentation only and should be run once through
`cliJVM/run` before they become fixtures. URLs are
`https://github.com/<owner>/<repo>/blob/<HEAD>/<path>` at the snapshot shas in §1. Licenses: prettier
MIT, black MIT, rustfmt MIT OR Apache-2.0, scalar/ruff details in §5.7; nothing is copied, the fixture
is cited for the idea only.

### 3.1 prettier (`c3d36525`, MIT)

| source fixture | exercises | HOCON input | expected hocon-fmt |
|---|---|---|---|
| `tests/format/js/comments/blank.js` | a file that is only comments and blank lines, incl. a bare `//` comment line | `# first\n\n# second` | refuses:lost-comment (a comment with no field after it, and a blank line detaching the first) |
| `tests/format/js/comments/emoji.js` | emoji value and a comment block after the last item | `name = "💖"\n# This comment\n# should not get collapsed` | refuses:lost-comment (comment after the last field) |
| `tests/format/js/comments/emoji.js` | the same value with the comment above it | `# This comment\nname = "💖"` | formats (comment kept; emoji is text) |
| `tests/format/js/comments/trailing_space.js` | trailing spaces inside a comment are not trimmed | `# trailing space ->   \nkey = 1` | formats (comment text preserved byte-for-byte) |
| `tests/format/js/comments/dangling.js` | a comment with no node to attach to, before a closing brace | `a {\n  # dangling\n}` | refuses:lost-comment (comment no field follows) |
| `tests/format/misc/empty/format.test.js` | empty input | (empty file) | unchanged (empty text is a pinned option, `OptionsSpec`) **(probe)** |
| `tests/format/js/bom/` (harness `test-bom.js`) | BOM must survive the round trip | `\ufeffkey = 1` | formats — hocon-fmt documents the opposite: "a UTF-8 byte-order mark is dropped" (`docs/limitations.md:190`) |
| `tests/format/js/end-of-line/example.js` (harness `test-end-of-line.js`) | CRLF/CR input, output EOL follows the option | `a = 1\r\nb = 2` | formats (`\n` only; no `endOfLine` option to honour) |
| `tests/format/js/ignore/ignore.js` | `// prettier-ignore` suppresses the next node | `# hocon-fmt-ignore\nkey   =   1` | formats (no pragma exists; the comment is ordinary and survives, the field is normalised) |
| `tests/format/js/range/ignore-indentation.js` | a range starting inside a multi-line template string | `s = """line one\n  line two"""`, whole-file | formats (no range flags; the whole file is in scope, so the "range only" safety net does not exist) |
| `tests/format/js/comments/return-statement.js` | one of the 15 permanent unstable-list fixtures | `a {\n  # first field comment\n  b = 1\n}` | formats (a comment above a field is kept; this is the shape the ledger protects) |
| `tests/format/js/_errors_/invalid/format.test.js` | syntax errors are *expected* failures per parser | the same `.conf` name with an nginx-style body | refuses:not-hocon |

### 3.2 black (`c9b1148`, MIT)

| source fixture | exercises | HOCON input | expected hocon-fmt |
|---|---|---|---|
| `tests/data/cases/empty_lines.py` | blank lines around definitions and a leading comment block | `# header\n\na = 1` | refuses:lost-comment (blank line ends the attachment; the commonest refusal in real files) |
| `tests/data/cases/empty_lines.py` | blank lines between fields | `a = 1\n\n\nb = 2` | formats (blank lines are dropped entirely, `docs/limitations.md:63-65`) |
| `tests/data/cases/jupytext_markdown_fmt.py` | a marker comment (`# %% [markdown]`) directly above a `fmt:` block | `# %% setup\n\na = 1` | refuses:lost-comment |
| `tests/data/cases/comments.py` | a licence-style comment block at the top of the file | `# Copyright …\n# …\n\na = 1` | refuses:lost-comment (matches the Akka `reference.conf` corpus finding) |
| `tests/data/cases/comments_in_double_parens.py` | a comment inside nested brackets, before the closer | `a = [1, 2 # inner\n]` | refuses:lost-comment (comment before the closing `]`) |
| `tests/data/cases/comments_non_breaking_space.py` | NBSP inside an inline comment | `a = 1  # note\u00a0x` | formats (comment text kept; trailing comment moves to its own line) **(probe)** |
| `tests/data/cases/comment_after_escaped_newline.py` | a backslash continuation followed by a comment | `a = 1 \\\n# why\nb = 2` | refuses:not-hocon (no line continuation in HOCON) **(probe)** |
| `tests/data/miscellaneous/missing_final_newline.py` | a comment-only file with no final EOL | `# only a comment` (no newline) | refuses:lost-comment |
| `tests/data/cases/format_unicode_escape_seq.py` | `\uXXXX` and `\UXXXXXXXX` escapes | `a = "\u0041"` | formats (`A`; unicode escapes are resolved) |
| `tests/data/miscellaneous/string_quotes.py` | escape-heavy quoting | `a = "he said \\"hi\\""` | formats (quoting normalised, meaning kept) |
| `tests/data/cases/string_quotes_escaped_trailing_quote.py` | a triple-quoted string ending in an escaped quote | `a = """line1\nline2"""` | formats (escape resolution; multiline content preserved) |
| `tests/data/cases/trailing_comma.py` | magic trailing comma / trailing separator | `a = [1, 2, 3,]` | formats (probe what sconfig does with the last comma) |
| `tests/data/cases/backslash_before_indent.py` | a backslash on its own line before an indented statement | `a = 1\n\\\nb = 2` | refuses:not-hocon |

### 3.3 rustfmt (`9f96727`, MIT OR Apache-2.0)

| source fixture | exercises | HOCON input | expected hocon-fmt |
|---|---|---|---|
| `tests/target/skip.rs` | `#[rustfmt::skip]` keeps an item byte-identical | `# hocon-fmt: skip\na=1` | formats (no skip pragma; the comment must survive and the field is normalised) |
| `tests/source/issue-3665/not_skip_attribute.rs` | a near-miss marker is not skip | `# hocon-fmt::skip\na   =   1` | formats (a near-miss marker must not be honoured) |
| `tests/source/empty_file.rs` | empty input in the source→target tree | (empty file) | unchanged **(probe)** |
| `tests/source/comment_crlf_newline.rs` | CRLF after comments, no final newline | `a = 1\r\n# c\r\n` | formats (`\n`, final newline added) |
| `tests/source/preserves_carriage_return_for_windows.rs` | `newline_style` config | the same CRLF file with no config available | formats (no newline-style option exists) |
| `tests/source/issue-5136-3.rs` / `-4.rs` | whitespace/EOF outside `--file-lines` preserved | file with leading blank lines and no final newline | formats (no range mode: the whole file is in scope) |
| `tests/warning/source/lost_comment.rs` | a comment the formatter cannot retain → kept verbatim + `LostComment` | `a = 1\n\n# after the blank` | refuses:lost-comment (the exact analogue of the pinned rustfmt error) |
| `tests/source/comments_unicode.rs` | non-ASCII and Unicode whitespace in comments | `# café ✓ — naïve\na = 1` | formats (comment text preserved) |
| `tests/source/unicode.rs` | unicode escapes and identifiers | `a = "\u0041"\nb = "日本語"` | formats (escape resolved; non-ASCII text kept) |
| `tests/source/string-lit-2.rs` | multi-line string internals left alone | `a = """line1\n  line2"""` | formats (triple-quoted becomes an escaped one-line string; content preserved) |
| `tests/source/trailing_commas.rs` | trailing commas in lists | `a = [1, 2, 3,]` | formats **(probe)** |
| `tests/source/remove_blank_lines.rs` | blank-line collapsing | `a = 1\n\n\nb = 2` | formats (blank lines dropped) |
| `tests/source/issue-5586.rs` | deeply nested blocks | 31 and 32 nested objects | formats at 31; refuses:broken-output at 32 (Scala.js only) |

### 3.4 gofmt (`72edc307c`, BSD-3-Clause)

| source fixture | exercises | HOCON input | expected hocon-fmt |
|---|---|---|---|
| `src/go/printer/testdata/comments.input` | "lone comments" before the closing brace of a composite literal and a comment as the file's last entry | `o {\n  a = 1\n  # lone\n}` | refuses:lost-comment |
| `src/go/printer/testdata/empty.input` | a file whose only content is a comment | `# head\n# tail` | refuses:lost-comment (documented: every comment of a file that holds nothing else) |
| `src/cmd/gofmt/testdata/crlf.input` (+ `TestCRLF`) | CRLF terminators must become LF | `a = 1\r\nb = 2\r\n` | formats |
| `src/go/printer/testdata/complit.input` | nested composite literals with **no final newline** | `v1 = { F1 = "hello", f2 = 1 }` (no trailing newline) | formats (output gains the final newline) |
| `src/cmd/gofmt/testdata/stdin5.input` | a line comment after the last token, no final newline | `i  =5 # c` (no trailing newline) | refuses:lost-comment |
| `src/go/printer/testdata/declarations.input` (non-ASCII literals) | non-ASCII strings and `'\uff16'`/`'\U0000ff16'` escapes | `s = "a\u00e9b"\nt = "é"` | formats (escapes resolved; value unchanged) |
| `src/cmd/gofmt/testdata/stdin6.input` / `stdin7.input` | raw/multi-line strings whose inner indentation survives a re-indent | `s = """\nline 1\n  line 2\n"""` | formats (one-line escaped form; both newlines and the two spaces must survive) |
| `src/cmd/gofmt/testdata/composites.input` (`//gofmt -s`) | simplifying composite literals, blank lines between them | `a { b = 1 }` | formats (default flattening writes `a.b = 1`; `--no-simplify-nested-objects` leaves it unchanged) |
| `src/cmd/gofmt/testdata/import.input` | unsorted/duplicated imports in a group, a comment before the group | `a = 1\na = 2` | formats plus the duplicate warning (the later definition wins, the earlier text is dropped) |
| `src/go/printer/testdata/go2numbers.input` | Go 2 number literals (`0b`, `0o`, `_` separators) | `a = 1.5e3` | formats (`1500`; number literals are canonicalised) |
| `src/go/printer/testdata/generics.input` | nested bracketed type lists — the closest thing to deep nesting | a 33-level nested object | refuses:broken-output (Scala.js: 32+ levels render as text that does not re-parse; 31 formats on every platform) |

### 3.5 scalafmt (`91694e7d`, Apache-2.0)

| source fixture | exercises | HOCON input | expected hocon-fmt |
|---|---|---|---|
| `scalafmt-tests/shared/src/test/resources/unit/Comment.stat` ("Inline comment binds left") | an end-of-line comment bound to the code it follows | `a = 1 # note` | formats (the comment moves to its own line above) |
| `…/unit/Comment.stat` ("comment respects 2x newline") | a comment followed by a blank line | `# banner\n\na = 1` | refuses:lost-comment |
| `…/unit/Comment.stat` ("two comments in a row") | consecutive comments before a value | `# one\n# two\na = 1` | formats |
| `…/unit/Comment.stat` ("commented out code stays to the left") | commented-out entries must not be re-indented or merged | `#a = 1\nb = 2\n#a = 3` | formats (comment text kept verbatim) |
| `…/unit/Comment.stat` ("Offset comment before )") | a comment immediately before a closing delimiter | `a = [1, 2 # note\n]` | refuses:lost-comment (no field can stand before `]`) |
| `…/unit/Comment.stat` ("comments with blank lines") | a blank line inside a `/* … */` block | a block comment with an empty line, then `a = 1` | refuses:lost-comment (the part above the blank line goes with it) |
| `…/unit/FormatOff.stat` ("identity matrix", `#3027`) | `// format: off/on` around unformatted code | `# format: off\na={b=1}\n# format: on` | formats (no pragma; comments kept, body normalised) |
| `…/test/Unicode.stat` (#1033, #1110) | unicode escapes and NBSP | `a = "\u00A0"` | formats (escape resolved; the value keeps the NBSP) |
| `…/test/StripMargin.stat` ("Align \| margin 1") | indentation inside multi-line strings | `a = """line1\n  line2"""` | formats (triple-quoted becomes a one-line escaped string; the two spaces stay) |
| `…/rewrite/AsciiSortImports.stat` ("ascii sorting") | an ordering rewrite, with a format-off variant | `b = 1\na = 2` | formats (field order preserved — sorting is never offered) |
| `scalafmt-tests/shared/src/test/scala/org/scalafmt/EmptyFileTest.scala` | empty and whitespace-only input, three line-ending modes | zero-byte file | unchanged **(probe)** |
| `…/FormatTests.scala:30-38` + `LineEndingsTest` | every case re-run with CRLF | `a = 1\r\nb = 2\r\n` | formats (line endings become `\n`) |

### 3.6 ruff (`5b27c1ccf`, MIT)

| source fixture | exercises | HOCON input | expected hocon-fmt |
|---|---|---|---|
| `crates/ruff_python_formatter/resources/test/fixtures/ruff/empty_now_newline.py`, `empty_whitespace.py` | empty document, whitespace-only document | (empty), then `"   \n\n"` | unchanged **(probe)** (whitespace-only is a valid empty config) |
| `…/black/miscellaneous/missing_final_newline.py` | document end with no final newline | `server.port = 8080` (no newline) | formats (final newline added) |
| `…/ruff/carriage_return/string.py` | CRLF *inside* multi-line strings | `a = "line1\r\nline2"` | unchanged (bytes inside the value are the value; only line endings outside are the formatter's) |
| `…/ruff/docstring_non_visible_characters.py` | a line that is only a control character | `# \u0015` only | refuses:lost-comment (comment-only file); with a field after it, formats |
| `…/ruff/newlines.py`, `…/black/cases/empty_lines.py` | blank-line handling between fields | `a = 1\n\n\nb = 2` | formats (blank lines are not kept) |
| `…/ruff/comment_prefixes.py` | `#!`, `#:`, `#\|` and missing-space comment prefixes | `#!/usr/bin/env hocon-fmt\nserver.port = 8080` | formats (the shebang text must survive byte-for-byte) |
| `…/ruff/statement/module_comment.py`, `module_dangling_comment1.py` | a comment after the last statement | `a = 1\n# trailing note` | refuses:lost-comment |
| `…/ruff/range_formatting/comment_only_range.py` | a comment-only region above code | `# leading comment\n\nserver.port = 8080` | refuses:lost-comment (blank line ends attachment; there is no `--range` to test) |
| `…/ruff/fmt_skip/reason.py` | `# fmt: skip` with a reason on the same line | `a = [1,2,3] # fmt: skip` | formats (no pragma support; the comment is ordinary) |
| `…/black/cases/trailing_comma.py` | trailing commas in collections | `a = [1, 2, 3,]` | formats **(probe)** |
| `…/black/cases/tricky_unicode_symbols.py` | non-ASCII keys and values | `ключ = "значение"\nemotion = "🚀"` | unchanged |
| `…/ruff/statement/long_type_annotations.py` | very long lines | a 120-character single-line value | unchanged (no reflow; long values are left alone) |

## 4. API and integrations

### 4.1 Capability comparison

`✓` = present, `✗` = absent, `~` = partial/planned here. hocon-fmt's column is today's documented
state (`docs/usage.md`), not a wish list.

| capability | prettier | black | rustfmt | gofmt | scalafmt | ruff | hocon-fmt |
|---|---|---|---|---|---|---|---|
| stdin → stdout | ✓ | ✓ `-` | ✓ | ✓ | ✓ | ✓ `-` | ✓ `--stdin` |
| stdin filename for diagnostics | `--stdin-filepath` | `--stdin-filename` | ✗ | ✗ (path omitted means stdin) | `--assume-filename` | `--stdin-filename` | ✓ `--stdin-filename` |
| check mode, files named | `--check` / `-l` | `--check` | `--check` (prints diff) | `-l` lists, `-d` diffs | `--test` / `--check` | `--check` | ✓ `--check` |
| exit codes | 0/1/2 | 0/1/123 | 0/1 (no distinct usage code) | 0/1 (`-d` only)/2 | bitmask: 0,1,2,4,8,16, OR-ed | 0/1/2 | ✓ 0/1/2 |
| diff output | ✗ (asked for, #6885) | `--diff` | `--check` prints a diff | `-d` | diff on `--test` failure | `--diff` (+ formats) | today `--check` prints the whole text; `--diff` is planned |
| machine-readable output | `--file-info` | ✗ | `--emit json` (not with `--check`) | ✗ | ✗ | `--output-format` | ✗ |
| range/partial formatting | `--range-start/--range-end`, `--cursor-offset` | `--line-ranges` | `--file-lines` (nightly/unstable) | ✗ | `--range` (hidden, experimental) | `--range` | ✗ (and sorting/formatting fields is never offered) |
| config file | `.prettierrc*`, `package.json`, upward | `pyproject.toml` `[tool.black]`, upward + user-level | `rustfmt.toml`/`.rustfmt.toml`, upward + `$HOME`/XDG | ✗ none (by design) | `.scalafmt.conf`, cwd or git root only | `pyproject.toml`/`ruff.toml`/`.ruff.toml`, hierarchical | ✓ `.hocon-fmt.conf`, upward, stops at `.git` |
| ignore file / walking rules | `.prettierignore` + `.gitignore`, `--ignore-path` | include/exclude regexes, per-directory gitignore | ✗ | ✗ | ✗ | `.gitignore` + `--respect-gitignore`, excludes | ✓ `.gitignore`, hidden entries skipped, named files always examined |
| ignore pragma | `// prettier-ignore`, `requirePragma`/`insertPragma` | `# fmt: off/on/skip`, `# yapf:` | `#[rustfmt::skip]`, `// rustfmt-…` (tests) | ✗ none (only `//go:build` relocation) | `// format: off/on`, `// scalafmt: {…}` | `# fmt: off/on/skip`, yapf aliases | ✗ none (a comment is a comment) |
| style/version pinning | exact dev-dependency; no global config | `--required-version`, `--preview`/`--unstable` | `style_edition`, legacy `version` | "execute a specific version" (`go/format` docs) | `version` required since v3.1.0, `scalafmt-dynamic` loads it | stable vs `--preview`, style changes in minor releases | one version everywhere (`docs/releasing.md`); no repo-side pin |
| LSP / editor entry | VS Code extension; no official LSP | no psf LSP (MS extension, `python-lsp-black`) | rust-analyzer | gopls (`go/format`) | Metals, IntelliJ, VS Code | `ruff server` (range + diagnostics) | ✗ (planned: small LSP in the native binary) |
| pre-commit | ✗ in-repo (mirrors-prettier archived 2024-04-11) | ✓ hooks + mirror | ✗ in-repo | ✗ in-repo (TekWizely hook) | ✗ in-repo (third-party) | separate `ruff-pre-commit` repo | ✓ hooks ship in wave 2 |
| official GitHub Action | ✗ (recommends autofix.ci) | ✓ | ✗ | ✗ | ✗ | ✓ `astral-sh/ruff-action` | ✗ (planned) |
| Docker image | ✗ | ✓ | ✗ | official Go images ship the toolchain | ✓ | ✓ | ✗ (planned, needs glibc+libstdc++) |
| build-tool plugins | plugin API (jest, etc.) | — (language-level) | cargo-fmt | `go fmt` | sbt, Gradle, Maven, Mill, coursier | — (language-level) | ✓ sbt, Gradle, Maven, Mill + Java/cats/ZIO APIs |
| skip-unchanged cache | ✓ `--cache` | ✓ `--cache-dir` | ✗ | ✗ | ✗ | ✓ `--no-cache` to disable | ✗ (planned) |

Per-tool notes on the surprising cells: **rustfmt** rejects `--emit` together with `--check` at HEAD,
so JSON diagnostics come from `--emit json` alone and its exit code is 0 even with diffs
(`src/bin/main.rs:619-622`); **scalafmt**'s exit code is a bitmask merged across files
(`ExitCode.scala:22-43`), so `1|2 = 3` is normal and its `--check` fail-fast once reported only 8 to 18
of 20 files (`61602058`); **black**'s safety checks live in the file path, not `format_str`
(`src/black/__init__.py:1237` vs `:1326`), and are skipped for `--line-ranges` (`:1874-1880`, issue
[#4033](https://github.com/psf/black/issues/4033)); **prettier** ships no official pre-commit hook or
Action; **gofmt** has no config file, no
pragma and no range, and `-l` still exits 0; **ruff** is the only one with an in-repo LSP, a
`--output-format` for CI annotations, and a documented preview→stable style policy (`docs/versioning.md`).

### 4.2 What users asked for most (reactions verified through the GitHub API on 2026-10-09)

| tool | top requests (exact titles, reactions, state) |
|---|---|
| prettier | "Resist adding configuration" 1223 closed; "Change `useTabs` to `true` by default" 870 open; "Wrap comments" 357 open; "Feature: handle .prettierignore location like .gitignore and .npmignore" 324 open; "Add diff in output of --check (for CI use cases)" 154 open |
| black | "Cython grammar support" 183 open; "Use optional parentheses more often" 60 open; "Switch to a new parser" 51 open; "Turn off magic trailing commas by default" 44 open; "Black's public API" 17 open |
| rustfmt | "[unstable option] `imports_granularity`" 221 open; "Gives up on chains if any line is too long." 186 open; "[unstable option] `group_imports`" 168 open; "format macros" 152 open; "Could rustfmt format Cargo.toml ?" 119 open |
| gofmt | "cmd/gofmt: don't rewrite into smart quotes" 43 closed; "cmd/gofmt: remove leading/trailing blank lines from function bodies" 38 open; "x/tools/cmd/goimports: support gofmt option to simplify code" 34 open; "cmd/gofmt: consider sorting imports in the same way as goimports" 34 open; "cmd/gofmt: change -d to exit 1 if diffs exist" 25 closed (implemented in 2025) |
| scalafmt | "Support Scala 3" 54 closed; "Support Scala Native" 28 closed; "scalafmt: [v3.7.16] corrupted class path" 26 closed; "[Feature Request] Auto-sort imports (statements)" 17 closed; "Enforce or remove trailing commas" 13 closed |
| ruff | "Unified command for linting and formatting" 415 open; "Implement autoformat capabilities" 241 closed; "ruff formatter: one call per line for chained method calls" 202 closed; "Formatter: `string_processing` preview style" 103 open; "Formatter: Keep right-hanging comments aligned" 47 open, 59 comments (the most-commented formatter issue) |

Themes: raw reaction counts are dominated by style opinions for the opinionated tools (prettier's
tabs/config debates, rustfmt's import grouping), which is the fight hocon-fmt avoids by having three
options and no more. The asks that recur where safety meets CI are small and concrete: **a diff in
`--check`** (prettier #6885, implemented by ruff in 2b1d3c60f), **exit-code correctness** (gofmt #46289,
scalafmt 89a7c620/61602058), **ignore-file semantics** (prettier #4081), and **listing what did not
change** (prettier #15480). Comment preservation/placement is the biggest safety-adjacent theme
everywhere (prettier "Wrap comments" 357, black's `F: comments` label, ruff #7684), and formatting a
*config* language is a real user request (rustfmt #4091 "Could rustfmt format Cargo.toml ?") — the
demand hocon-fmt answers for HOCON.

## 5. Ideas to migrate, with cost and value

Cost: S an afternoon, M a few days, L more (the scale `docs/ideas.md` uses). Value: what it changes for
a user. Entries already in `docs/ideas.md` are marked and stay there; this list adds what the six
trackers and codebases argue for.

| idea | source of the idea | cost | value |
|---|---|---|---|
| `--diff` on `--check` (and a refusal reason per file in the summary) | prettier #6885 (154 reactions), ruff 2b1d3c60f, docs/ideas.md "one report format" | S | high for CI: the file, the reason, and the change in one screen |
| Count every refusal in the run summary (nothing silently outside the numbers) | prettier d8ab2b1f ("All matched files use Prettier code style!", exit 0 on a parser error) | S | high: a summary that cannot lie is what makes a pre-commit hook trusted |
| `format(expected) == expected` for every golden, asserted in `GoldenFileSpec` | gofmt `printer_test.go:133-143` (golden must be a fixed point) | S | high: pins that the approved output is stable, not only the input |
| A no-panic property over arbitrary bytes on all three platforms | rustfmt's four span/panic fixes (68099e2, 6d6cd71, 7e56db3, 53bf773) | S | high: a formatter that refuses never crashes; the ZIO adapter already runs 1000 random byte sequences, core does not |
| Corpus idempotence run over pinned real files, refusals counted separately, with `<=` budgets | gofmt `long_test.go` (GOROOT), scalafmt `ScalafmtProps` (`idempotency <= 41`) | M | high: turns the ad-hoc reference.conf sweeps into a suite that catches our own regressions |
| BOM tolerance in `.hocon-fmt.conf` (and a test that says what happens) | prettier 1a36a7de (BOM in `.prettierrc.json`) | S | medium: editors on Windows write BOMs; today the config file is read as strict UTF-8 |
| Skip-unchanged cache for the CLI, keyed on content + options | black `--cache`, docs/ideas.md "Skip unchanged files" | S | medium: large repositories check in milliseconds |
| A repo-side version pin, e.g. `required-version` in `.hocon-fmt.conf` | black `--required-version`, scalafmt's required `version`, rustfmt `style_edition` | S | medium: before 1.0 the style moves; a repository can refuse a formatter it was not formatted with |
| Machine-readable per-file output (`--output-format json` / `github`) | ruff `--output-format`, CI annotations | M | medium: annotations in a PR instead of a log line |
| GitHub Action with one annotation per unformatted file or refusal | ruff `astral-sh/ruff-action` (prettier ships none) | S | medium: the commonest CI entry point for a formatter |
| Docker image (glibc + libstdc++, per `docs/ideas.md`) | ruff/scalafmt images; docs/ideas.md | S | medium: CI that runs containers |
| Editor snippets (conform.nvim, Helix, Zed) documented, then an upstream list entry | every tool's editor docs; docs/ideas.md | S | medium: works today through `--stdin` |
| VS Code extension over the `web` build, refusals as diagnostics | ruff VS Code, black's MS extension | M | medium: format-on-save without a binary |
| Small LSP in the native binary (`textDocument/formatting` + refusal diagnostics) | ruff server; docs/ideas.md | M | high for editors, and it makes refusals visible while editing |
| A `# fmt: off`-style pragma | black's `F: fmtoff` crashes (7 fixes), ruff's 3 `fmt: skip` fixes, scalafmt's `FormatOff` | L | risky: a pragma is a promise you can break; if ever, decide its scope (field vs object vs file) in one place, test every shape after a parse or attachment change, and never let it skip the output check |
| Range formatting | prettier, black, rustfmt, ruff, scalafmt all have it, all have bugs (45a4a75e, a3cb9e1, 79a23d2/e28eb9e, 4f7fb566f) | L | not now: it is a second printer with its own validity and stability rules (ruff skips the stability check, black skips the AST check for ranges), and our `--stdin` integration is the 90% case |

Two ideas explicitly **not** recommended: sorting fields (it moves includes across definitions and is
never offered — `docs/limitations.md`), and reformatting comment *text* (gofmt's doc-comment rewrite
needed a proposal and a revert; scalafmt's streaming rules show the bug surface).

## 6. Method, licenses and open ends

Method: six shallow clones (`--filter=blob:none --shallow-since=2022-01-01`) under the task directory,
to their 2026-10-09 HEADs; history read with `git log --grep`/`-S` and `git show`; tests, safety code,
CLI flags and config code read at file:line in the clones; issue titles and reaction counts fetched
with `gh`/the GitHub API on 2026-10-09 (`reactions.total_count`, comments as returned). Nothing was
copied from a test suite: every HOCON input in §3 is written for this document, and the fixture is
cited for the idea only. Licenses of the cited sources: prettier MIT, black MIT, rustfmt MIT OR
Apache-2.0, gofmt BSD-3-Clause, scalafmt Apache-2.0, ruff MIT — all permit reading and re-implementing
the idea; none of their text is reproduced.

Known gaps: (a) every expectation in §3 marked **(probe)** is derived from `docs/limitations.md` and
`docs/usage.md` and was not run; the synthesis step should run those through
`sbt "cliJVM/run --check <file>"` before they become fixtures. (b) The shallow clones see 2022 onward
only, so a bug fixed before 2022 appears here only through the fix's own message. (c) Reaction counts
are a snapshot and GitHub caps comment counts on some endpoints (the numbers above come from the REST
API, which is not capped). (d) Background/CI integration claims are read from workflow files, not
observed on a live run. (e) No test in this repository was added or changed by this research.

