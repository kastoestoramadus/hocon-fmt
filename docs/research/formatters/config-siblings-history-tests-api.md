# Config-sibling formatters — history, tests, API (pass B)

**Family:** terraform fmt / hclwrite ([hashicorp/hcl](https://github.com/hashicorp/hcl)), [taplo](https://github.com/tamasfe/taplo),
jsonnetfmt ([google/go-jsonnet](https://github.com/google/go-jsonnet)), cue fmt ([cue-lang/cue](https://github.com/cue-lang/cue)),
[nixfmt](https://github.com/NixOS/nixfmt); HOCON's own tooling: [lightbend/config](https://github.com/lightbend/config),
[ekrich/sconfig](https://github.com/ekrich/sconfig), [pyhocon](https://github.com/chimpler/pyhocon), [AVSystem/intellij-hocon](https://github.com/AVSystem/intellij-hocon).

**Method.** Each repo was shallow-cloned (`--filter=blob:none --shallow-since=2022-01-01`, 2026-10-09) and mined with
`git log --grep`/`git show`; every fix was read at its own commit, PR/issue links were verified with `gh`, and
case-table sources are relative to each clone's HEAD (sha per tool). **Licences:** MPL-2.0 (hcl, nixfmt), MIT (taplo),
Apache-2.0 (the rest); no test text was copied — every HOCON rendering below is mine, the source cited for the idea
only. Thin trackers allow fewer than 10 fixes for pyhocon, intellij-hocon, hcl, taplo and go-jsonnet.

## 1. History mining

### lightbend/config (Java, Apache-2.0; head [`06a5271`](https://github.com/lightbend/config/tree/06a5271))

The reference implementation. Since 1.4.3 (2023) rendering round-trips became the active area: 1.4.6–1.4.9
(Feb–Jun 2026) are almost all render/resolve fixes, several of them regressions of each other.

- **2026-10-02** [705b241](https://github.com/lightbend/config/commit/705b241) / [#867](https://github.com/lightbend/config/pull/867) —
  every list comment gained one leading space per render-then-parse cycle; `SimpleConfigList` prepended `"# "`
  unconditionally while `SimpleConfigObject` already checked. *Lesson:* a renderer must be a fixed point on the
  **second** pass, and a comment's text is data (ported from sconfig #472, the same bug on the Scala side).
- **2026-10-05** [e84b043](https://github.com/lightbend/config/commit/e84b043) — a comment above an `+=` field was
  attached to a synthetic list element (`# two` above `a += 2`). *Lesson:* expansions you introduce must not become
  comment owners; hocon-fmt's `+=` expansion renders through sconfig, same shape.
- **2026-05-05** [b0a1e43](https://github.com/lightbend/config/commit/b0a1e43) / [#846](https://github.com/lightbend/config/pull/846) —
  a partially-shadowed object produced a nested delayed merge: a **regression of #839 shipped two weeks earlier**.
  *Lesson:* merge-stack changes need a property over "object with one shadowed field", not the reported example.
- **2026-04-24** [833bacb](https://github.com/lightbend/config/commit/833bacb) / [#839](https://github.com/lightbend/config/pull/839) —
  substitutions hidden by values from resolved objects were evaluated, changing meaning. *Lesson:* no comment check
  notices this; meaning preservation is the only net that does.
- **2026-06-03** [92fc7df](https://github.com/lightbend/config/commit/92fc7df) / [#853](https://github.com/lightbend/config/pull/853) —
  wrong origin line numbers for objects after a multi-line string. *Lesson:* hocon-fmt's include order compares
  line-based origins, so a parser line-number defect is a formatter defect.
- **2023-10-17** [3a4ebbf](https://github.com/lightbend/config/commit/3a4ebbf) / [#798](https://github.com/lightbend/config/pull/798) —
  `setShowEnvVariableValues` hides secrets in rendered output: a render option as a security surface. hocon-fmt's
  env-override refusal is the safe end of the same problem.
- **Closed 2015-04-02** [#149](https://github.com/lightbend/config/issues/149) "Preserve comments across newlines?"
  (2014-03-13 → 2015-04-02, 31 comments) — the tests' answer: a comment followed by a blank line attaches to nothing
  and is dropped (`ConfParserTest.trackCommentsForSingleField`). hocon-fmt refuses on that drop.
- **Open, still:** [#733](https://github.com/lightbend/config/issues/733) "Address long standing ordering/sorting
  issues." (since 2021-06-09, 29 comments), [#122](https://github.com/lightbend/config/issues/122) "Feature request:
  dynamic includes" (since 2013, 33 comments) and [#188](https://github.com/lightbend/config/issues/188) "include
  requires 'classpath()' or 'file()' to do the right thing" (25 comments) — ordering and includes are the decade-old
  user pain; hocon-fmt's "never sort fields" and include masking meet both head-on.

### ekrich/sconfig (Scala port of the above, Apache-2.0; head [`f351262`](https://github.com/ekrich/sconfig/tree/f351262))

The parser hocon-fmt runs on, so its rendering history *is* our bug history. Umbrella reports:
[#511](https://github.com/ekrich/sconfig/issues/511) "TWO BUGS with rendering, missing comments and failed
concatenation" (closed 2025-10-22 → 2025-12-06) and [#510](https://github.com/ekrich/sconfig/issues/510) "rendering
include \"other.conf\"" (closed 2026-09-11).

- **2026-09-30** [f47f841](https://github.com/ekrich/sconfig/commit/f47f841) / [#598](https://github.com/ekrich/sconfig/pull/598) —
  `setSimplifyNestedObjects` spliced a compressed key onto whatever was in the buffer, so `e = ${g} { name = east }`
  rendered `e = ${g}name = east`; unresolved merges grew a banner and a space every pass (208, 493, 1070, 2237 bytes
  for `a : [1]` / `a += 2`). Compression now runs only where the key is known, and the banner only where the value
  has no parseable spelling. *Lesson:* exactly the class `Refusal.BrokenOutput`/`UnstableOutput` exist for; the PR
  added `checkReparses` beside `checkEqualsAndStable` in `RenderingTestSuite.scala`, the idempotence+reparse pair as
  a shared helper.
- **2025-12-05** [22a1f7e](https://github.com/ekrich/sconfig/commit/22a1f7e) / [#515](https://github.com/ekrich/sconfig/pull/515) —
  comments deleted when multipath compaction (one nested entry → dotted path) was on. *Lesson:* every layout
  optimisation must be gated on comments; hocon-fmt sees the loss as `LostComment`.
- **2025-12-12** [77ecf7d](https://github.com/ekrich/sconfig/commit/77ecf7d) / [#525](https://github.com/ekrich/sconfig/pull/525) —
  the same optimisation still moved or dropped comments on the parent or leaf; the fix disables it whenever a comment
  is present. *Lesson:* disabling an optimisation near comments is the cheap correct answer — carrying comments
  through it took #515, #522 and #523 and still missed cases.
- **2025-12-09** [a6a6cbc](https://github.com/ekrich/sconfig/commit/a6a6cbc) / [#522](https://github.com/ekrich/sconfig/pull/522) and
  **2025-12-10** [9b99b3a](https://github.com/ekrich/sconfig/commit/9b99b3a) / [#523](https://github.com/ekrich/sconfig/pull/523) —
  the single-path optimisation broke on non-root objects and the fix needed a follow-up the next day.
  *Lesson:* three fixes in eight days for one optimisation is the strongest argument for a property test over
  comment-bearing nested objects (`FormatterPropertiesSpec` covers this today).
- **2025-12-05** [0cc0750](https://github.com/ekrich/sconfig/commit/0cc0750) / [#516](https://github.com/ekrich/sconfig/pull/516) —
  string concatenation rendered as broken text. *Lesson:* the output syntax check is not optional.
- **2025-09-30** [2153152](https://github.com/ekrich/sconfig/commit/2153152) / [#497](https://github.com/ekrich/sconfig/pull/497) —
  multipath rendering inside arrays. *Lesson:* object-in-array is where brace-keeping rules break; the open
  limitation "a one-field object inside an array loses its braces" is the same family.
- **2025-08-06** [1356ec3](https://github.com/ekrich/sconfig/commit/1356ec3) / [#466](https://github.com/ekrich/sconfig/pull/466) —
  an unquoted string ending in `.` threw while rendering; keys that look like numbers longer than an `Int` threw too
  ([951be42](https://github.com/ekrich/sconfig/commit/951be42), #460). *Lesson:* user data reaching an index helper
  must have a defined answer; hocon-fmt's "indices too long for an Int are the user's" is this fix on our side.
- **2026-09-10** [4571d4e](https://github.com/ekrich/sconfig/commit/4571d4e) / [#590](https://github.com/ekrich/sconfig/pull/590) —
  ports lightbend [#832](https://github.com/lightbend/config/pull/832) "Render list elements as non-root always" and
  #841. *Lesson:* sconfig tracks lightbend's render fixes with a lag of weeks to years; when a rendering defect
  appears, check the Java side first — it may only need porting.
- **Still open, 2026-10-08:** [#646](https://github.com/ekrich/sconfig/issues/646) "Comments separated from the next
  field by a blank line are dropped" — hocon-fmt's central limitation; the site fork works on it (draft #647).

### pyhocon (Python HOCON parser, Apache-2.0; head [`b11bd65`](https://github.com/chimpler/pyhocon/tree/b11bd65))

A parser with no formatter, so its tracker shows what HOCON users want from one: [#39](https://github.com/chimpler/pyhocon/issues/39)
"How to dump HOCON file?" (closed 2016) and [#139](https://github.com/chimpler/pyhocon/issues/139) "Possible to write
to a conf file in hocon format from within python script?" (open since 2017). Its CLI (`pyhocon/tool.py`) converts
HOCON to json/properties/yaml/hocon: a *converter*, re-emitting from the parsed tree, so comments are gone by design.
*Lesson:* the Python ecosystem has converters; comment-preserving formatting is a differentiator its users cannot
even request.

### AVSystem/intellij-hocon (IntelliJ plugin, Apache-2.0; head [`7a4fc46`](https://github.com/AVSystem/intellij-hocon/tree/7a4fc46))

The only public HOCON formatter besides ours, and its test data is the best public catalogue of comment attachment:
`testdata/parser/commentsBinding.test` and `documentationComments.test` pin which comment is "bound" to the
following key and which is "not bound" (a comment above a blank line, or before `}`/end of file, stays unbound) —
the same rule sconfig implements, which hocon-fmt refuses on. `testdata/formatter/` holds ~28 input→expected pairs
driven through IntelliJ's code-style engine, including four comment-position settings (keep comments at first column
on/off; hashes vs `//`) and wrap/align policies. Its own history since 2022 is features (structure view, find
usages), not correctness: the plugin formats the PSI it has, so a comment it cannot attach is a no-op rather than a
loss. *Lesson:* comment placement is exposed as a *setting* here — hocon-fmt has no such choice.

### hashicorp/hcl — hclwrite (Go, MPL-2.0; head [`a786e4f`](https://github.com/hashicorp/hcl/tree/a786e4f))

`hclwrite.Format` is the whole-file formatter behind `terraform fmt`. It only rewrites whitespace between tokens
(it never re-renders values), and the history shows what that saves.

- **2026-06-17** [6e0ffff](https://github.com/hashicorp/hcl/commit/6e0ffff) / [PR #810](https://github.com/hashicorp/hcl/pull/810)
  (merge `3464b27`) — a panic "on incorrect assumption of parsing an identifier"; the diff reads as a type assertion
  on object-construction keys that stopped holding once keys became general expressions. *Lesson:* never assume a
  node's concrete type for input-derived trees; refuse or generalize.
- **2026-08-11** [551aa90](https://github.com/hashicorp/hcl/commit/551aa90), [4875013](https://github.com/hashicorp/hcl/commit/4875013),
  [202219c](https://github.com/hashicorp/hcl/commit/202219c) — `Body.RemoveNewlineBeforeBlock` sliced `tokens[:count-1]`
  assuming the token before a block is a newline; the guard added is `tokens[count-1].Type == TokenNewline`, with the
  comment "It seems unlikely for this to be anything other than a newline. And yet, we verify." *Lesson:* verify a
  token's kind before deleting it, or whitespace surgery silently removes content.
- **2024-02-15** [d703862](https://github.com/hashicorp/hcl/commit/d703862) / [#658](https://github.com/hashicorp/hcl/pull/658) —
  `provider::framework::example()` was formatted `provider :: framework :: example`; `spaceAfterToken` had no rule for
  `::`. *Lesson:* a formatter keyed on token text must know every multi-char operator so one does not match another's rule.
- **2023-01-27** [5fe5697](https://github.com/hashicorp/hcl/commit/5fe5697) / [#584](https://github.com/hashicorp/hcl/pull/584) —
  the lexer emits several string-literal tokens for one template string with escapes, so a traversal key holding an
  escape parsed as an index expression. *Lesson:* a token run that does not re-lex to one logical literal breaks
  parse-back heuristics.
- **2022-01-31** [a26ee4f](https://github.com/hashicorp/hcl/commit/a26ee4f) / [#511](https://github.com/hashicorp/hcl/pull/511) and
  **2022-06-13** [63d288b](https://github.com/hashicorp/hcl/commit/63d288b) — data races in `formatSpaces()` and on the
  shared `nilToken`; both fixed by removing mutable shared state, and the tests began running with `-race`.
  *Lesson:* a formatter inside a build tool is run concurrently; keep per-file state per-file.
- **2026-05-30 → 2026-08-05** [5897067](https://github.com/hashicorp/hcl/commit/5897067), [c7aee6a](https://github.com/hashicorp/hcl/commit/c7aee6a),
  [7db7c2a](https://github.com/hashicorp/hcl/commit/7db7c2a), [30491e6](https://github.com/hashicorp/hcl/commit/30491e6) —
  `Attribute.LeadComments()/LineComments()` and `Block.LeadComments()` were exported only in 2026. *Lesson:* a
  formatter's comment-preservation story is part of its API; ours is `CommentCarrier`.

### tamasfe/taplo (Rust TOML toolkit, MIT; head [`08f343b`](https://github.com/tamasfe/taplo/tree/08f343b))

Formatter + LSP + JS/WASM API. Most of its correctness work is options interacting (`array_auto_collapse` vs
`array_auto_expand`, `align_entries` vs `align_comments`), and it has one catastrophic write bug in living memory.

- **2022-03-30** [#236](https://github.com/tamasfe/taplo/issues/236) "Bug: new version clears Cargo.toml" (closed the
  same day) — v0.6 emptied real `Cargo.toml` files through a neoformat hook. *Lesson:* the "never write a broken
  file" promise exists because this happened to a real editor integration within days of a release.
- **2022-10-08** [68a9da8](https://github.com/tamasfe/taplo/commit/68a9da8) (closes [#315](https://github.com/tamasfe/taplo/issues/315)) —
  a comment after a table header produced parse errors; the diff shows the token cursor was stepped implicitly by
  `token_as()`, over-advancing past `]]`. *Lesson:* cursor movement must never swallow tokens the formatter must keep.
- **2023-08-14** [cb156c3](https://github.com/tamasfe/taplo/commit/cb156c3) / [#456](https://github.com/tamasfe/taplo/pull/456)
  (failing test first, [d87d4e7](https://github.com/tamasfe/taplo/commit/d87d4e7), merged `051ce9f`) — an array
  comment containing `]` (`# [x]`) suppressed the following newline because newline emission was gated on the
  *rendered* text ending with `]`. *Lesson:* never derive token-level structure from output characters.
- **2024-05-16** [acec15f](https://github.com/tamasfe/taplo/commit/acec15f) / [#609](https://github.com/tamasfe/taplo/pull/609) —
  re-aligning a comment tripped a debug assertion: the same trailing comment reached entry attachment twice.
  *Lesson:* attachment must be idempotent under repeated application; alignment rewrites text lengths mid-pass.
- **2024-02-14 → 2024-07-27** [c950bdc](https://github.com/tamasfe/taplo/commit/c950bdc) / [#527](https://github.com/tamasfe/taplo/pull/527),
  reverted by [4d08035](https://github.com/tamasfe/taplo/commit/4d08035) — widening node spans to cover trailing
  comments changed key formatting elsewhere; the revert "Fixes #634" and "Re-open #464".
  *Lesson:* comment ownership is cross-cutting; prefer narrow, opt-in handling and name both issues when you revert.
- **2022-05-31** [b6989a2](https://github.com/tamasfe/taplo/commit/b6989a2) — "skip errors while formatting": the
  formatter threads parser error ranges through so it rewrites around broken regions, still available as `--force`.
  *Lesson:* the opposite of hocon-fmt's refusal; a formatter that formats around errors cannot promise meaning
  preservation.
- **2023-09-03** [191852c](https://github.com/tamasfe/taplo/commit/191852c) / [#450](https://github.com/tamasfe/taplo/pull/450) —
  table indentation came from the previous sibling alone, wrong for repeated tables; replaced by a per-path history.
  *Lesson:* nesting state must come from full ancestor history, and two options both controlling indentation is one
  option too many (hocon-fmt's four render options are the same risk, pinned in `OptionsSpec`).
- **Open, still:** [#390](https://github.com/tamasfe/taplo/issues/390) "Array always auto collapses even when
  configured not to" (since 2023-03-27) and [#535](https://github.com/tamasfe/taplo/issues/535) "Feature request:
  pre-commit hook for formatter" (since 2024-01-20, 20 comments — the highest-comment open feature issue).
### google/go-jsonnet — jsonnetfmt (Go, Apache-2.0; head [`567b61a`](https://github.com/google/go-jsonnet/tree/567b61a))

The most conservative formatter here: it is deliberately not a canonical reformatter — `--string-style`,
`--comment-style`, `--max-blank-lines` and `--indent 0` all default to "leave". Its lesson is about promises.

- **2026-02-24** [209e9f0](https://github.com/google/go-jsonnet/commit/209e9f0) and
  [5400c70](https://github.com/google/go-jsonnet/commit/5400c70) — with `UseImplicitPlus=false`, rewriting
  `{ f(x):: x*x } { a: 1 }.f(99)` to `{…} + {…}.f(99)` dropped the parentheses that keep precedence: the formatted
  program errors at run time instead of 9801. The test commit adds 92 lines of parent-context cases (`Apply`, `Index`,
  right operand of `+`, unary `+`). *Lesson:* an AST rewrite must preserve the parse tree wherever the parent can
  re-associate; this is a **semantic-change** bug no syntax check catches.
- **2025-03-06** [6d83c91](https://github.com/google/go-jsonnet/commit/6d83c91) — an out-of-bounds array access while
  formatting `#\n{}\n` (golden `//\n{}\n`), from reading `(*comment)[1]` before checking its length.
  *Lesson:* the empty comment is a real input; `OptionsSpec` covers empty text and properties generate empty objects.
- **2024-06-09** [c8d95b9](https://github.com/google/go-jsonnet/commit/c8d95b9) / [#724](https://github.com/google/go-jsonnet/pull/724) —
  a string the formatter's style pass could not unescape (an input regex) hit `panic("Badly formatted string, should
  have been caught in lexer")`; validation moved into the lexer and the case is a static error now.
  *Lesson:* formatter passes must never panic; validation belongs to the parser, and the error surfaces as a refusal.
- **2025-01-20** [e6f64e8](https://github.com/google/go-jsonnet/commit/e6f64e8) / [#773](https://github.com/google/go-jsonnet/pull/773) —
  the unparser always wrote `|||` and assumed a three-character opener, so chomped blocks (`|||-`) were re-indented
  wrongly. *Lesson:* handle every value shape the grammar permits, or refuse to reformat.
- **2025-08-07** [2946b6c](https://github.com/google/go-jsonnet/commit/2946b6c) — a missing trailing newline when both
  `--multi` and `--string` were given: a second output path bypassed the common termination. *Lesson:* every output
  mode shares one line-termination guarantee; CLI flags multiply, so combinations need their own tests.
- **2025-03-06** [106c8f0](https://github.com/google/go-jsonnet/commit/106c8f0) — the formatter tests were switched to
  `DefaultOptions()` because a hand-built option set had drifted from what users get. *Lesson:* pin the shipped
  defaults, not a locally convenient variant.
- **Open:** [#496](https://github.com/google/go-jsonnet/issues/496) "Auto-replace implicit + with explicit + in
  jsonnetfmt." (since 2021) — a style change deliberately *not* shipped; [#222](https://github.com/google/go-jsonnet/issues/222)
  "Preserve object key ordering when generating output" (closed 2018) resolved as "order is not preserved", the same
  stance hocon-fmt takes for field order.

### cue-lang/cue — cue fmt (Go, Apache-2.0; head [`a4f52c23`](https://github.com/cue-lang/cue/tree/a4f52c23))

The most active formatting development of the family: a new printer (`internal/pretty`, Wadler-Lindig) behind the
`formatv2` experiment, with a 2026 run of comment and blank-line fixes — evidence that a printer rewrite re-opens
every comment bug once.

- **2024-04-22** [fdbd563a](https://github.com/cue-lang/cue/commit/fdbd563a) (Fixes #2274, Updates #2567) — a list
  element with a trailing comment printed the comma *after* the comment, so the comma became part of it: a syntax
  error. The class returned at [b423a275](https://github.com/cue-lang/cue/commit/b423a275) (2023-12-20, #2567),
  [79305ff5](https://github.com/cue-lang/cue/commit/79305ff5) (2026-01-26, #4238) and
  [eaaba8b6](https://github.com/cue-lang/cue/commit/eaaba8b6) (2026-01-08, #1447) — four times in four years.
  *Lesson:* punctuation is emitted outside comment text; a comment swallows the rest of its line.
- **2026-06-19** [2f004a26](https://github.com/cue-lang/cue/commit/2f004a26) (Fixes #4409) — a multi-line string
  interpolation was collapsed and a `//` comment beside `\(`/`)` dropped entirely, because the printer read only text
  fragments. *Lesson:* every AST field a printer ignores is potential data loss.
- **2026-10-06** [a484f683](https://github.com/cue-lang/cue/commit/a484f683) — a bare expression in a declaration
  slot printed its comments **twice**, two converter layers both emitting them. *Lesson:* exactly one component owns
  comment emission; duplication and loss are the same bug with different signs.
- **2026-07-29** [b1ab143d](https://github.com/cue-lang/cue/commit/b1ab143d) — the opener's trailing comment in
  `import ( // c)` moved to the closing paren and could merge with a comment already there. *Lesson:* relocating
  within the same syntactic slot can still fuse two comments into one.
- **2026-06-23** [3bfd5931](https://github.com/cue-lang/cue/commit/3bfd5931) (Fixes #4405) — an unnamed import spec's
  trailing comment (recorded at `PosSuffix`) detached onto its own line and broke idempotency. *Lesson:* attachment
  position must be interpreted where the printer looks; both sides read one field.
- **2026-06-25** [5e276edd](https://github.com/cue-lang/cue/commit/5e276edd) and **2026-07-11** [2e77fc72](https://github.com/cue-lang/cue/commit/2e77fc72)
  (Fixes #4404) — a blank line right after `{` was deleted, and a comment-only file gained a trailing blank line.
  *Lesson:* vertical structure the user wrote is not whitespace to normalise. hocon-fmt drops all blank lines
  (sconfig's tree holds none) — the most visible formatting licence we take.
- **2026-06-16** [e57987ae](https://github.com/cue-lang/cue/commit/e57987ae) (Fixes #2677) — `< -num` became `<-num`,
  which does not parse. *Lesson:* whitespace removal across an operator boundary changes tokenization; re-parse always.
- **2026-06-19** five reverts in one day ([ebe63286](https://github.com/cue-lang/cue/commit/ebe63286),
  [17a029b0](https://github.com/cue-lang/cue/commit/17a029b0), [d4365c24](https://github.com/cue-lang/cue/commit/d4365c24),
  [7cce9cac](https://github.com/cue-lang/cue/commit/7cce9cac), [35307ed6](https://github.com/cue-lang/cue/commit/35307ed6)) —
  field alignment, list alignment, spurious empty lines, tab width and interpolation indentation, all reverted before
  the release. *Lesson:* layout rules are one system; "fix one alignment" changes another's input.
- **2026-07-17** [1ce2b5f5](https://github.com/cue-lang/cue/commit/1ce2b5f5) — `tools/fix`'s alias fixer looped forever
  because a pass reported a change it had not made. *Lesson:* report "no change" (or refuse) for shapes you cannot
  rewrite; never loop on a promise you do not keep.
- **2026-10-01** [0edbb5b8](https://github.com/cue-lang/cue/commit/0edbb5b8) — a closer moved onto its own line
  without the trailing comma that pass 2 then added. *Lesson:* one decision ("break the closer") must drive every
  dependent token, or the second pass disagrees with the first.

### NixOS/nixfmt (Haskell, MPL-2.0; head [`616ca00`](https://github.com/NixOS/nixfmt/tree/616ca00))

The boldest safety design in the family: a built-in `--verify` (output re-parses and re-formats to itself), tests
run through it, and CI formats all of nixpkgs and diffs the result. The 1.3.0 changelog lists six idempotency
regressions found by *nixfmt-rs*, a Rust reimplementation used as a differential oracle.

- **2026-05-05** [5ce4b46](https://github.com/NixOS/nixfmt/commit/5ce4b46) (PR #387) — `a / /* sh */ "" p` rendered
  `a //* sh */ "" p`; `//` re-lexes as the update operator, so the output does not parse. *Lesson:* a comment landing
  on an operator changes tokenization; insert the separating space unconditionally.
- **2026-05-05** [7f283af](https://github.com/NixOS/nixfmt/commit/7f283af) (PR #387) — leading blank lines were
  stripped at output but still steered layout, so formatting the stripped text differed. *Lesson:* layout must be a
  pure function of the text that will be printed. (hocon-fmt's mirror risk: layout sees the masked text.)
- **2025-12-30** [5b32b0a](https://github.com/NixOS/nixfmt/commit/5b32b0a) (PR #363) — in empty lists/sets with only
  comments, a block comment on the opener turned into a line comment and moved inside on the next pass; printing had
  mutated the attachment. *Lesson:* if the printer changes attachment, the AST no longer describes the text.
- **2024-07-12** [c834f33](https://github.com/NixOS/nixfmt/commit/c834f33) (PR #220) — empty trailing lines in star
  comments were stripped on pass 2, not pass 1. *Lesson:* canonicalise comment text in the pass that prints it;
  "it settles after the first format" is not a fixed point.
- **2024-07-11** [6cb8544](https://github.com/NixOS/nixfmt/commit/6cb8544) (PR #219) — trivia merging in parameter
  lists dropped the following parameter's trailing comment (`baz # qux` lost `# qux`). *Lesson:* a merge must carry
  every slot it consumes; a `Nothing` in a comment slot is a silent loss.
- **2024-07-11** [baa4bb7](https://github.com/NixOS/nixfmt/commit/baa4bb7) (PR #217) — `--verify` itself recursed
  infinitely on `{ foo # bar , baz # qux }: null`. *Lesson:* the safety net must terminate on every tree it can
  meet; a crash inside verify is worse than a reported diff.
- **2026-07-07** [41ec460](https://github.com/NixOS/nixfmt/commit/41ec460) (PR #425) — hoisting a comment out of a
  function application fused `/* bash */` with a `#` comment, demoting the annotation on pass 2. *Lesson:* comment
  kind can be semantic; two adjacent comments are not one comment.
- **2024-12-02** [ebb3b25](https://github.com/NixOS/nixfmt/commit/ebb3b25) (PR #270) — the previous change moved
  trailing comments after `let` bindings upward for aesthetics and was reverted because "the downsides were deemed
  too high". *Lesson:* moving a comment to a different token is never worth an aesthetic gain.
- **2024-08-29** [0baa6da](https://github.com/NixOS/nixfmt/commit/0baa6da) (PR #247) — single-line comments kept
  trailing spaces; the standard requires trimming. *Lesson:* comment text is normalised by the formatter; decide
  each normalisation once, in one pass. (hocon-fmt normalises nothing inside a comment and compares its text
  verbatim, which is the safe half.)
- **2026-07-17** [6a68b45](https://github.com/NixOS/nixfmt/commit/6a68b45) / [#435](https://github.com/NixOS/nixfmt/pull/435)
  and **2026-07-21** [14c006e](https://github.com/NixOS/nixfmt/commit/14c006e) — exponential parse time for nested set
  parameter defaults. *Lesson:* performance is correctness for a pre-commit hook. Related: the
  `/*nixfmt:disable*/`/`enable` pragma added in 1.4.0 ([#388](https://github.com/NixOS/nixfmt/pull/388)) — the
  family's answer to "this region must not be touched".

**Regression frequency.** The comment/trivia class returns everywhere: lightbend/config three 2026 rendering fixes
touching comments or delivery order (705b241, e84b043, 92fc7df) plus a regression of a fix shipped two weeks earlier
(#839 → #846); sconfig three fixes for one optimisation in eight days (#515, #522, #523) plus #525; nixfmt 11
idempotency commits in 2026, six in one release (1.3.0); cue four "comment + punctuation" bugs across four years
(fdbd563a, b423a275, 79305ff5, eaaba8b6); taplo a fix reverted six months later (#527 → #464/#634).
**Property worth writing:** *(output re-parses) and (output is a fixed point)* over comments in every position —
before a field, after a field, after a comma, inside an array, above a blank line, at the end of an object or file.
`HoconFormatterInvariantsSpec` and `FormatterPropertiesSpec` are that shape; the tables below feed them.

## 2. How they test, and reusable cases

Architecture per tool, then cases that translate to HOCON; "expected" is what hocon-fmt should do today.

### lightbend/config and ekrich/sconfig (shared design)

**Architecture.** `ConfParserTest.trackCommentsForSingleField`/`trackCommentsForMultipleFields` assert comment
*attachment* (which comment lands on which value's origin) with exact expectations, including the blank-line drop;
`ConfigDocumentTest` asserts `configDocument.render() == origText` on edit round-trips and exact `withValueText`
output. sconfig's `ConfigDocumentFactoryTest` does the file-level version on `src/test/resources/test03.conf`, an
include-heavy file; `RenderingTestSuite.scala` adds `checkEqualsAndStable`/`checkReparses`, and `ConfigFormatOptionsTest`
pins each render option's exact text. No fuzzing and no real-file corpus, which is why the renderer's defects needed
user reports (#511, #646).

| source (path @ HEAD) | exercises | HOCON rendering | hocon-fmt today |
|---|---|---|---|
| `config/.../ConfParserTest.scala` `trackCommentsForSingleField` | comment above a blank line | `# banner\n\nkey = 1\n` | refuses (`LostComment`) — upstream drops it |
| same | comment after the value on its own line | `key = 1\n# c\n` | refuses (`LostComment`) |
| same | comment on the value's line / after the separator | `key = 1 # c\n` / `key = # c\n1\n` | formats |
| same | comment between key and separator | `key # c\n= 1\n` | formats |
| same | empty object with comments before and after | `# pre\n{}\n# post\n` | refuses (both attach to nothing) |
| same | comments on array elements | `a = [\n# pre\n1 # post\n]\n` | refuses (nothing can hold them) |
| same | comma on its own line after the value | `a = 1\n, # c\n` | formats; `# c` is not attached upstream |
| same | empty array with a comment before it | `a =\n# c\n[]\n` | formats (comment attaches to `a`) |
| `config/.../ConfParserTest.scala` `acceptBOM*` | BOM at start and inside a quoted value | `\uFEFFkey = 1\n`, `key = "\uFEFFx"\n` | formats; leading BOM dropped, inner kept |
| `sconfig/.../test03.conf` (file round-trip) | comments and includes interleaved in an object | `{ a = 1\ninclude "x.conf"\n# c\nb = 2 }\n` | include masked and kept; `# c` above `b` formats, `# c` last refuses |

### pyhocon

**Architecture.** `tests/test_config_parser.py` is a hand-written parse-level suite (~2500 lines) including
`test_parse_with_comments` (comments before keys, after values, after commas, inside `{}` and `[]`, `#` and `//`
mixed) and `test_with_comment_on_last_line`; `test_tool.py` covers the CLI converter. No renderer, so no
idempotence or round-trip tests exist to copy; the value here is the comment-position inventory.

| source (path @ HEAD) | exercises | HOCON rendering | hocon-fmt today |
|---|---|---|---|
| `tests/test_config_parser.py::test_parse_with_comments` | comment before a nested brace | `a = {\n# c4\nb = 1, # c5\n}\n` | refuses (`# c5`) |
| same | `//` and `#` mixed across a file | `// a\n# b\nk = v # c\n` | formats (both become `#`) |
| same | comment after a comma inside an array | `t = [1, # c\n2, # d\n3]\n` | refuses |
| `tests/test_config_parser.py::test_with_comment_on_last_line` | comment at end of file | `a = 1\n# the end\n` | refuses (`LostComment`) |
| `tests/test_config_parser.py` (trailing-ws case, ≈line 2372) | concatenation with a comment after trailing space | `foo = "a" "b" // c\n` | formats |
| `tests/test_config_parser.py` (newline case, #324) | value on the line after `=` | `a =\n  1\n` | formats |
| `samples/animals.conf` | substitutions plus blank lines | `animals.dog.legs = 4\n\nanimals.dog.sound = woof\n` | formats; blank lines dropped |
| `samples/aws.conf` | dotted keys and includes | `include "common.conf"\naws.region = eu\n` | formats (include masked) |
| `tests/test_periods.py` | duration values | `timeout = 10 seconds\n` | formats |
| `tests/test_config_tree.py` | duplicate keys | `a = 1\na = 2\n` | formats; duplicate report names `a` |

### AVSystem/intellij-hocon

**Architecture.** `testdata/formatter/` holds settings-XML + input + expected triples run through IntelliJ's
code-style engine (`HoconFormatterTest`); `testdata/parser/` holds input + PSI tree dumps (`HoconParserTest`), plus
resolution/usages/completion fixtures. No idempotence check (the editor re-runs on every keystroke, so
non-idempotence shows as cursor drift). This is the most copyable HOCON *formatting* corpus in the public ecosystem.
| source (path @ HEAD) | exercises | HOCON rendering | hocon-fmt today |
|---|---|---|---|
| `testdata/parser/commentsBinding.test` | bound vs unbound (blank line, before `include`) | `# not bound\n\n# bound\nkey.more = value\n` | refuses (first comment) |
| `testdata/parser/documentationComments.test` | `#doc` lines with no space | `#doc\n#more doc\nkey = value\n` | formats |
| `testdata/parser/fieldSeparators.test` | `:`, `=`, object-without-separator | `a: 1\nb = 2\nc { d = 3 }\n` | formats (`:` → `=`) |
| `testdata/parser/multilineKeyValue.test` | key and value on separate lines | `key =\n  value\n` | formats |
| `testdata/parser/multilineInclude.test` | include split across lines | `include "a.conf",\nb = 1\n` | refuses (include shares a line) |
| `testdata/parser/danglingComma.test` | trailing comma at the end of an object | `a = 1,\n` | formats |
| `testdata/parser/unclosedMultilineString.test` | unterminated `"""` | `a = """x\n` | refuses (`NotHocon`) |
| `testdata/parser/substitutions.test` | `${?b}`, `${d.e}`, `${a[]}` | `a = ${?b}\nc = ${d.e}\n` | formats; `${a[]}` refuses |
| `testdata/formatter/keepCommentsAtFirstColumn.test` | hash and `//` comments inside an object | `obj {\n# c1\n// c2\n}\n` | refuses (nothing follows the comments) |
| `testdata/formatter/spaceWithinSubstitutionBraces.test` | spaces inside `${ }` | `a = ${ ?b }\n` | refuses (`NotHocon`) |

### hashicorp/hcl (hclwrite)

**Architecture.** `hclwrite/format_test.go::TestFormat` is an inline source→expected table (comments, blank lines,
newlines); `round_trip_test.go` asserts `WriteTo` of a parsed file is byte-equal to the source for verbatim files
(`TestRoundTripVerbatim`) and that `JustAttributes` survives a format (`TestRoundTripFormat`, `RawEquals` before/after);
`TestRoundTripSafeConcurrent` runs under `-race`, and `hclwrite/fuzz/fuzz_test.go` requires either diagnostics or a
successful write for arbitrary bytes. The verbatim round-trip + fuzz pair is the closest analogue to our suites.

| source (path @ HEAD) | exercises | HOCON rendering | hocon-fmt today |
|---|---|---|---|
| `hclwrite/round_trip_test.go` (cases 1–3) | empty file, one attribute, no final newline | ``, `foo = 1\n`, `foo = 1` | formats (final newline added) |
| same (case 4) | leading blank line, aligned `=`, comments, nested blocks | `# c\n\nfoo = 1\nbar = 1\nb {\na = 1\n}\n` | refuses (`# c` above a blank line) |
| `hclwrite/format_test.go::TestFormat` | comment-only line between blocks | `a { }\n# between\nb { }\n` | formats |
| same | trailing comment on a block's line | `a { } # after\n` | formats (comment moves above) |
| same | blank lines inside an object | `a {\n\nb = 1\n\n}\n` | formats; output has no blank lines |
| same | CRLF line endings | `a = 1\r\n# c\r\nb = 2\r\n` | formats; normalised to `\n` |
| same | alignment of `=` across entries | `a = 1\nlong = 2\n` | formats, no alignment |
| `hclwrite/fuzz/fuzz_test.go` corpus | arbitrary bytes never panic the writer | random bytes | refuses (`NotHocon`), never panics |
| [#285](https://github.com/hashicorp/hcl/issues/285) | many trailing newlines at EOF | `a = 1\n\n\n` | formats; output ends with one `\n` |
| [#211](https://github.com/hashicorp/hcl/issues/211) | multi-line string content | `a = """x\ny"""\n` | formats; triple quotes become `\n` escapes |

### tamasfe/taplo

**Architecture.** `crates/taplo/src/tests/formatter.rs` holds ~40 inline expected-string tests, several per option
(`comment_indentation`, `comment_after_entry`, `test_comment_in_array`, `test_comments_in_array`, `test_align_comments`,
`test_nested_arrays`), many asserting `format(input) == input`; `test-data/rewrite/` holds
`<name>.toml`/`<name>_expected.toml` pairs for the CLI rewrite path; `test-data/invalid/` files must fail. PR
[#647](https://github.com/tamasfe/taplo/pull/647) added a CI step that formats twice and fails on a diff (a fixed-point
gate), and `toml-test` integration gives differential testing against TOML's conformance suite.

| source (path @ HEAD) | exercises | HOCON rendering | hocon-fmt today |
|---|---|---|---|
| `crates/taplo/src/tests/formatter.rs::comment_indentation` | a comment at column 0 inside an indented object | `o {\n# at zero\n  a = 1\n}\n` | formats |
| `…::comment_after_entry` | comment on the entry's own line | `a = 1 # c\n` | formats (moves above) |
| `…::comment_before_entry` | comment on its own line before a field | `# c\na = 1\n` | formats |
| `…::test_comment_in_array` | one comment inside an array | `a = [\n# c\n  1\n]\n` | refuses (`LostComment`) |
| `…::test_comments_in_array` | comments before and after elements | `a = [1, # x\n2 # y\n]\n` | refuses |
| `…::test_align_comments` | consecutive trailing comments | `a = 1 # one\nbb = 2 # two\n` | formats; alignment not offered |
| `…::test_nested_arrays` | nested arrays | `a = [[1, 2], [3]]\n` | formats |
| `test-data/rewrite/nothing.toml` | empty document | `` (empty) | already formatted |
| `test-data/rewrite/table.toml` | a table with an entry and a comment | `[t]\n# c\na = 1\n` | formats |
| `test-data/invalid/*` | documents that must fail | `a = = 1\n` | refuses (`NotHocon`) |

### google/go-jsonnet (jsonnetfmt)

**Architecture.** `formatter/formatter_test.go::TestFormatter` walks `formatter/testdata/*.jsonnet` against
`*.fmt.golden` (error messages are golden too, per #724), and `TestFormatNoImplicitPlus` evaluates input and
formatted output with the interpreter, comparing values — a semantic round-trip. The golden flag comment notes
"No support yet for custom formatter config/flags in the testdata/ driven tests", which is how the `--multi --string`
bug survived; no `format(format(x)) == format(x)` check exists.

| source (path @ HEAD) | exercises | HOCON rendering | hocon-fmt today |
|---|---|---|---|
| `formatter/testdata/empty_comment.jsonnet` + golden | empty comment, nothing after the marker | `#\na = 1\n` | formats |
| `…/regular_expression.jsonnet` + golden | a string the style pass cannot unescape | n/a | — |
| `…/object_implicit_plus1.jsonnet` + golden | implicit `+` between braces | `a = ${b} { c = 1 }\n` | refused (sconfig renders `${b}c = 1`) |
| `formatter/formatter_test.go::TestFormatNoImplicitPlus` | precedence-preserving rewrite contexts | n/a | — |
| `TestFormatter` goldens | comment style conversion `#` → `//` | `// c\n# d\nk = v\n` | formats (all become `#`) |
| same | string quote style `'` → `"` | `a = 'x'\nb = "y"\n` | formats (quoting normalised) |
| same | a file that is one comment and nothing else | `# only\n` | refuses (`LostComment`) |
| `TestFormatter` goldens | long object with many fields | `o { a = 1\nb = 2\n … }\n` | formats; no width logic |
| `TestFormatter` goldens | trailing whitespace after a value | `a = 1   \n` | formats |
| `TestFormatter` goldens | no final newline / CRLF variants | `a = 1`, `a = 1\r\n` | formats; `\n` and one final newline |

### cue-lang/cue (cue fmt)

**Architecture.** `cue/format/format_test.go::TestFiles` runs txtar archives from `cue/format/testdata/` and, after
writing each golden, **re-runs the formatter and asserts byte equality** ("%s is not formatted idempotently");
`internal/pretty/pretty_test.go::checkIdempotent` re-parses the printed text and re-prints it, failing on a re-parse
error (added with the pretty-printer rewrite). Options have their own tests (`TestV2Options`, `TestKeepRelPos`,
`TestCompact`, `TestPostfixSpread`, `TestFuncParamComments`, `TestClosingBracketComma`), and fuzzing covers
`cue/format`, `ast.Walk` and `astutil.Apply` with the rule that they "cannot fail or panic on input that worked with
`cue/parser`".

| source (path @ HEAD) | exercises | HOCON rendering | hocon-fmt today |
|---|---|---|---|
| `cue/format/testdata/comments.txtar` | comments before fields, after values, after the opener | `o { # opener\n# before\na = 1 # after\n}\n` | refuses (`# after`) |
| same | comment as the only content of a block | `o {\n# c\n}\n` | refuses (`LostComment`) |
| `…/list-commas.txtar` | commas and their absence in lists | `a = [1, 2, 3]\n` | formats; `[1 2 3]` refuses |
| `…/expressions.txtar` | operator spacing, unary operators | `a = 1+2\nb = -1\n` | formats (`1+2` stays as written) |
| `…/issue3291.txtar` | blank line at a section start | `a = 1\n\nb = 2\n` | formats; blank line dropped |
| `…/imports.txtar` | trailing comments on import specs | `include "x.conf"\n# c\n` | formats; same-line `include "x.conf" # c` refuses |
| `…/interpolation.txtar` | comments inside multi-line strings | `a = """x\n# not a comment\n"""\n` | formats (comment text kept verbatim) |
| `…/list-commas-simplify-v16/v17.txtar` | same input, two language versions | n/a | no version notion; one rendering |
| `…/spread.txtar`, `…/functions.txtar` | postfix spread, multi-line parameter lists | n/a | — |
| `…/issue1544.txtar` | historical negation case | `a = -1\n` | formats |

### NixOS/nixfmt

**Architecture.** `test/correct/` (19 files) must be fixed points, checked by running the real binary with
`--strict --verify` and diffing (`test/test.sh`); `test/diff/` (57 `in.nix` → `out.nix`, plus `out-pure.nix` for
`--strict`) pins transformations; `test/invalid/` (8 files) must fail. `--verify` (format, re-parse, re-format,
compare) is in `src/Nixfmt.hs`, the same net as our output check. A workflow formats all of nixpkgs and publishes
the diff — the strongest corpus test in this family — and nixfmt-rs is a separate differential oracle.

| source (path @ HEAD) | exercises | HOCON rendering | hocon-fmt today |
|---|---|---|---|
| `test/correct/*.nix` (19 files) | files already canonical | a well-formatted app config | already formatted |
| `test/diff/**/in.nix` (trailing comment, #387) | comment after an operator | `a = 1 # c\n+ 2\n` | formats; comment moves above |
| same (star comment, #220) | blank lines inside a block comment | `a = 1\n/* x\n\n   y */\n` | formats; comment text kept verbatim |
| same (empty list, #363) | comment-only list/object | `a = [\n# c\n]\n` | refuses (`LostComment`) |
| same (let-in, #270) | comment before the closing brace | `o {\nx = 1\n# c\n}\n` | refuses |
| same (language annotation, #425) | annotation comment glued to a value | `a = 1 # c\n` | formats |
| same (leading blank lines, #387) | file starting with blank lines | `\n\na = 1\n` | formats; leading blank lines dropped |
| `test/diff/**/in.nix` (integer selection, #387) | `1 .a` vs `1.a` | `a = 1 .b\n` | refuses (`NotHocon`) |
| `test/correct-indent-4.nix` | indentation option | `o {\na = 1\n}\n` | formats (no option; style is fixed) |
| `test/invalid/*.nix` | syntax errors | `a = = 1\n` | refuses (`NotHocon`) |

**What the family lacks and we have:** includes as a first-class statement, `--check` with "never write on
refusal" (taplo and hcl write around errors), and a duplicate report.

## 3. API and integrations

| tool | entry points | check / diff / stdin | config & style | errors & exit | integrations |
|---|---|---|---|---|---|
| terraform fmt (hclwrite) | `hclwrite.Format([]byte) []byte`, `ParseConfig`, `Tokens*` generators, `LeadComments()/LineComments()` (2026) | `-check` (non-zero + file list), `-diff`, `-list`, `-write=false`, `-recursive`; stdin `-` | none: "intentionally opinionated and has no customization options"; new rules are not breaking changes | parse diagnostics; no write under `-check` | every Terraform pipeline, editor plugins, CI |
| taplo | `taplo format`, library crates, LSP, JS/WASM | `--check`, `--diff` (ANSI, 7 lines of context), stdin `-` + `--stdin-filepath`, `--force` formats broken input | `.taplo.toml`: 20 options (align, reorder, indent, crlf, trailing_newline, allowed_blank_lines) with per-path `[[rule]]` scopes; VS Code camelCase | parse errors printed with spans; unformatted/unparseable exits non-zero | VS Code extension, LSP, npm, Docker; pre-commit request open (#535) |
| jsonnetfmt | `jsonnetfmt`, `formatter.FormatNode`, `unparse` in the Go API | `--test` (exit failure if changed), `-i/--in-place`, `-o`, `-e/--exec`, stdin `-` | 12 flags, each with a "leave" default (`--indent 0`, `--max-blank-lines 0`, `--comment-style l`) | parse error → non-zero, file untouched | go-jsonnet bindings, Bazel, editors via external formatter |
| cue fmt | `format.Source/Node/NodeInPlace` with `Simplify`, `Version`, `Indent*`, `LineWidth`, `KeepRelPos`, `Compact`, `TabIndent` | `--check` (non-zero + file list), `--diff`, `--files`, stdin `-`; packages or paths | per-module language version (`cue.mod`) decides comma rules; no width by default | `ErrPrintedError` under `--check`; parse errors abort before any write | `cue` CLI, LSP, Gerrit CI |
| nixfmt | `nixfmt`, `Nixfmt.format`, `formatVerify`, `printAst/printIR` | `--check`, `--verify`, `--strict`, `-`/stdin (bare stdin deprecated 1.3.0), `--filename`, `--quiet` | `--width`, `--indent`, `/*nixfmt:disable*/`/`enable`; RFC style is the only style | non-zero on parse or verify failure; `--check` writes nothing | pre-commit hook file, `git-hooks.nix`, treefmt, Neovim/VSCode, nixpkgs CI |
| lightbend/config | `ConfigFactory.parse*`, `ConfigRenderOptions` (comments, originComments, formatted, json, showEnvVariableValues), `ConfigDocumentFactory` (render/withValueText/withoutPath) | none (library) | render options only | exceptions (`ConfigException.Parse`, `BugOrBroken`, `NotResolved`) | Akka/Play/Pekko, sbt, all JVM |
| ekrich/sconfig | same API in Scala, `ConfigFormatOptions` (keepOriginOrder, doubleIndent, colonAssign, newLineAtEnd, simplifyNestedObjects) | none (library) | five options, no file config | same exception types; Scala.js/Native subsets | scalafmt native, hocon-fmt's `coreSite` fork |
| pyhocon | `ConfigFactory.parse_*`, `HOCONConverter.to_*`, CLI (`-f json/properties/yaml/hocon`, `-n`, `-c`) | none | converter options only | parse exceptions; converter writes what it parsed | Python apps |
| intellij-hocon | IntelliJ plugin: code style settings, formatter, structure view, find usages | none | ~28 settings incl. comment first-column, wrap/align, spaces around `:`/`=`/`${}` | n/a (editor) | IntelliJ IDEA/PyCharm; the only HOCON editor integration |

**Ideas to migrate** (cost S/M/L, value for hocon-fmt users):

1. **In-file opt-out pragma** `# hocon-fmt: off` … `on` (M, high) — nixfmt's `/*nixfmt:disable*/`, the only way to
   format a file with one hand-written region; mask it like an include and count it in the comment check.
2. **Corpus check in CI, not only by hand** (S, high) — nixfmt's nixpkgs diff and taplo's double-format CI step; a
   reference.conf corpus in CI would catch what examples miss.
3. **Name what was refused, per file, in `--check`** (S, high) — every sibling prints the file list; keep ours stable
   and test it as an API.
4. **`--diff` mode** (M, medium) — taplo, cue, terraform and jsonnetfmt all have one; today users diff by hand.
5. **Differential oracle against lightbend/config's renderer** (L, medium) — nixfmt-rs found six idempotency bugs in
   one release; a JVM test could compare `render()` results for one tree, as `EquivalentsTest` does for resolve.
6. **Publish the integration matrix** (S, medium) — taplo's top open feature request is a pre-commit hook (#535);
   hocon-fmt's two hook families, four plugins and two channels are documented in separate places.
