# Formatter research synthesis

## Executive summary

- The priority is safety: changed meaning, deleted comments and crashes outrank layout polish.
- Ran 479 physical inputs with both separators: 958 CLI observations, plus 15 filesystem setups.
- 648 observations met the invariants; 306 were refused safely; four reproduced real bugs.
- Those four are two already documented include override-barrier failures, each in both styles.
- Both parse and stabilize while changing resolved values; the ownership is shared.
- No other invariant failures or CLI crashes were found in this JVM sample.
- CRLF inside a string is preserved; several profile expectations needed correction.
- Symlinks/read-only checks passed; killing the in-place fallback left an empty file, as documented.
- Add independent value checks, comment equality and bounded properties before expanding features.
- Keep few options, avoid ranges/extension plugins, and re-run this ledger before every release.

## Invariants first

Contracts come from [AGENTS.md](../../../AGENTS.md), [architecture](../../architecture.md),
[limitations](../../limitations.md), [testing](../../testing.md) and [usage](../../usage.md).

1. Keep each include verbatim and once, in its object and on the same side of definitions;
   do not open include targets during formatting. Preserve effective precedence.
2. Keep every comment occurrence, including repeated identical comments. The current check
   compares trimmed bodies without markers and only detects missing occurrences; it does not
   prove attachment or prohibit extra occurrences. End-of-line comments moving above are intentional.
3. Every accepted output is a fixed point under the same options: a second format changes nothing.
4. Accepted output re-parses **masked**, so validation neither opens includes nor depends on JS include support.
5. Preserve values, types, concatenation whitespace and override semantics. This is the intended
   invariant; production currently checks syntax/stability and include order, not general value equality.
6. Refuse rather than corrupt: invalid UTF-8, other named formats, missing comments/includes,
   collisions, broken/unstable output and moved includes leave the original bytes untouched.
7. File `--check` writes nothing and exits 1 exactly when formatting would change bytes, absent
   a read/config error or opt-in duplicate failure. Refusals count as no write.
8. File exits: 0 completed (including refused), 1 differences in check or opted-in duplicates,
   2 argument/config/read/write failure. Every readable file is still examined after an I/O error.
9. Stdin returns complete accepted text, including unchanged text, on stdout only; refusal returns
   empty stdout, stderr reason and exit 1; read/usage failure exits 2. Do not combine check and stdin.
   `--stdin-filename` exists and is diagnostic context only: no file access or ancestor config lookup.
10. No writes for refused/unchanged input, including no mtime change. Follow and retain symlinks.
    Stage beside the real target; replace atomically only after owner/group/mode readback succeeds.
    In-place fallback retains identity/inode but **can truncate on interruption**. Atomic replacement
    leaves other hard links on old bytes. Mill always writes in place. Atomicity is conditional.
11. Style is deliberately lossy in spelling: `//`→`#`, default `:`→`=`, flattening, numeric/escape
    canonicalization, triple quotes→escaped strings, LF/no BOM and inline-comment relocation.
    These normalizations must preserve values; they do not license changing string content.

## Bug-class matrix

Frequency is the number of tagged **rows/bullets in profile Bug classes**, not issue links,
tracker prevalence, confirmed defects or unique bugs. A row with two tags contributes once to each;
requests and uncertain reports stay in the research sample. History/API documents feed probes but
are not counted a second time. Overlapping tools across families are deliberately retained.
Audit: [tagged evidence](probes/bug-class-evidence.tsv), [machine-readable matrix](probes/matrix.json).
Safety profile read from PR #88, commit `63b3155`; other inputs from `3bf4c70` (main).

Score = (3×config + 2×data + 2×safety + classics) × severity × relevance.
Severity: 5 silent value/include corruption; 4 comment deletion/crash/failed safety protocol;
3 instability/movement/encoding/order; 2 usability/latency; 1 cosmetic or unavailable capability.
Relevance: 3 direct contract, 2 transferable adjacent concern, 1 distant/renderer-owned, 0 absent ranges.
Weights are judgment calls per class, displayed below; moving idempotence severity from 3 to 4
would put it second (612), so the exact third place is not a universal priority claim.

| class | config ×3 | data ×2 | safety ×2 | classics ×1 | weighted frequency | severity | relevance | score |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| semantic-change | 21 | 4 | 4 | 10 | 89 | 5 | 3 | 1335 |
| comment-loss | 5 | 9 | 2 | 10 | 47 | 4 | 3 | 564 |
| crash | 2 | 8 | 3 | 12 | 40 | 4 | 3 | 480 |
| check-exit-codes | 6 | 3 | 3 | 9 | 39 | 4 | 3 | 468 |
| idempotence | 7 | 6 | 4 | 10 | 51 | 3 | 3 | 459 |
| comment-movement | 11 | 5 | 4 | 11 | 62 | 3 | 2 | 372 |
| include-import-analog | 3 | 3 | 1 | 6 | 23 | 5 | 3 | 345 |
| parse-error-handling | 3 | 3 | 2 | 9 | 28 | 4 | 3 | 336 |
| whitespace-eol-encoding | 3 | 7 | 2 | 8 | 35 | 3 | 3 | 315 |
| config-ignore | 6 | 6 | 4 | 10 | 48 | 2 | 2 | 192 |
| ordering | 4 | 4 | 1 | 5 | 27 | 3 | 2 | 162 |
| performance | 3 | 1 | 3 | 4 | 21 | 2 | 2 | 84 |
| other | 7 | 2 | 3 | 4 | 35 | 2 | 1 | 70 |
| embedded-language | 4 | 2 | 2 | 6 | 26 | 2 | 1 | 52 |
| line-width-layout | 9 | 4 | 2 | 10 | 49 | 1 | 1 | 49 |
| partial-range | 0 | 1 | 3 | 6 | 14 | 1 | 0 | 0 |

The top three: **changed meaning** silently changes what a service loads, even when output parses;
**lost comments** deletes instructions/licences the value tree cannot represent;
**crashes** prevent the hook/editor from delivering a trustworthy result, and become dangerous
if they reach a truncating write. Fixed points and exit agreement remain cheap, mandatory defenses.


## Probe results

Recorded on main `3bf4c70`, JVM CLI 0.1.0, sconfig 1.12.4, Temurin 25.0.4, Linux uid 1000.
[Inputs/provenance](probes/manifest.json), [proposal mapping](probes/inventory.json),
[expected/actual/owner table](probes/probes.tsv), [exact observations](probes/actual.json),
[filesystem results](probes/filesystem-actual.json), [replay instructions](probes/README.md).
Headers live in `.source` companions so they cannot alter BOM/empty/EOF/comment probes.
Every supplied P ID (including P21; source has no P7), candidate/probe proposal and matrix has a
concrete input or an explicit abstract/API setup disposition. No research source was edited.

| observation | count | owner / interpretation |
|---|---:|---|
| Accepted, invariants met | 648 | shared core/library; observed, not a proof for all inputs |
| Refused, bytes/mtime kept, file exits 0 and stdin exits 1 | 306 | 182 sconfig rendering guards; byte/name/masking boundaries also classified in TSV |
| Accepted but meaning changed | 4 | shared; B1/B2 below, twice each |
| Initial file check 1 / 0 | 626 / 332 | ours; agrees with whether a write changes bytes for every case |
| Accepted value comparisons equal / changed / unavailable | 628 / 4 / 20 | bare sconfig resolver; 10 unresolved, 10 required-include IO cases unavailable |
| All examples and JVM/Native golden files, both styles | 116 (58 files) | 82 accepted fixed points, 34 refused; includes example metadata files |
| Extra filesystem setups | 15 | ours; symlink, read-only, ignores/config, mixed batches, hard links, kills |

### Every real BUG

| ID / minimal input | expected versus actual | invariant / severity | owner / evidence |
|---|---|---|---|
| B1: [438.conf](probes/438.conf): `include "f.conf"\no=3\no.c=7`; [f.conf](probes/support/f.conf) defines `o.retained=9` | expected `o={c:7}`; actual `include "f.conf"\no.c = 7\n`, resolves to `o={retained:9,c:7}` | Meaning/precedence preserved; **high**, silent config change | **shared**: sconfig's value tree drops `o=3`; our masked-include guard misses the barrier. Bare unmasked render retains correct values; no new upstream defect claimed |
| B2: [empty barrier](probes/case-include-empty-barrier.conf): `x.a=5\ninclude "scalar.conf"\nx {}`; [scalar.conf](probes/support/scalar.conf) defines `x=3` | expected `x={}`; actual `x.a = 5\ninclude "scalar.conf"\n`, resolves to numeric `x=3` | Meaning/type preserved; **high**, silent config change | **shared**, same guard gap: empty object disappears before restoring include; bare unmasked render is `x {}` |

Both styles: check-before **1**, write **0**, second write **0**, check-after **0**, stdin **0**.
The colon variant changes only separator spelling. Bare resolution is recorded beside the exact
output, with no formatter calls in `Oracle.java`. Both are known in limitations, now measured with
controlled includes. Our guard needs a follow-up; upstream value rendering is behaving as designed.
No specific upstream fix was located for either; [Java #733](https://github.com/lightbend/config/issues/733)
(ordering) and [#300](https://github.com/lightbend/config/issues/300) (document traversal) are related,
not fixes. No upstream messages were posted and no formatter implementation was changed.

### Surprises, not product bugs

- [CRLF literal](probes/465.conf): actual `s = "line1\\r\\nline2"` preserves both characters;
  source spec says literal content is unmodified. The data/markup profile's loss claim did not reproduce.
- [Apostrophes](probes/127.conf): `a='x'` is accepted as the literal unquoted string **including**
  apostrophes; single quotes are not a HOCON quoting delimiter. Its proposed NotHocon expectation was wrong.
- [Quoted substitution-looking text](probes/055.conf) remains literal; spaces there are not invalid syntax.
  [Comma/EOF comment](probes/024.conf) is refused for loss despite a profile's predicted formatting.
- Empty stays empty; whitespace-only becomes empty; CRLF outside values becomes LF. Several classics
  proposals expected preserved EOLs or nonzero file refusal exits; those conflict with the local contract.
- Pragma-like comments are ordinary comments today; trailing/detached ones may cause refusal.
  `--stdin-filename` exists but performs no style lookup (file `a: 1`, stdin `a = 1` in the setup).
- Read-only write: **2**, original bytes/mtime/mode intact. Symlink: **0**, link and target identity kept.
  Actual kill at staging kept 8,000,004 original bytes; kill at fallback truncation left **0 bytes**.
  That is an **ours** documented atomicity limitation, not a newly found breach of its conditional promise.
- Staged partial-write failure and cancellation suite: `Passed: Total 2, Failed 0, Errors 0, Passed 2`.
  Rename-failure injection is not represented by a distinct CLI fault-injection hook; the write/permission
  and interruption probes plus existing staged-failure suite establish only the outcomes stated here.

Full replay is the final check below; JVM evidence establishes no JS/Native result. The historical
external 1,650-file corpus was not re-run; local example/golden coverage tests the proposed mechanism.


## Recommendations

### Test architecture

Ranked by value/cost; S afternoon, M days, L longer. These are recommendations, not new guarantees.

1. **S, high:** every approved golden and example gets a second pass under both separators;
   existing core already checks stability on accepted input. Cue, gofmt and dprint run this at fixture level.
2. **S, high:** exact normalized comment **multiset equality**, including duplication; pair it with
   lexical boundaries so comment text cannot turn into code. Keep intentional marker/trim policy explicit.
3. **M, high:** an independent JVM value oracle, controlled includes and environment states. A reparse
   by the renderer's own parser is insufficient evidence of source meaning.
4. **M, high:** pinned licensed corpus, bounded by files/bytes/time, refusals per kind and ceilings;
   retain minimal cases instead of copying an unbounded external repository into CI.
5. **S, high:** a known-bad ledger asserts today's failure and fails when it improves. Existing
   `SconfigDefectsSpec` already supplies this; automate triage rather than add a second silent allowlist.
6. **S, medium:** nightly seeded property runs with saved minimal inputs. Promote each found invariant
   failure into the fast tier; do not make every PR pay the exploration budget.
7. **M, medium:** source-level re-lex checks compare delimiters, literals and comment membership;
   allow documented transformations explicitly. A literal-text multiset alone misses reordered values.

### Product

1. **S, high:** exit-code contract tests cover all verdicts and mixed batches through actual processes.
   File refusals must stay non-failing; stdin refusals must remain failures so editors retain buffers.
2. **S, high:** `--diff` builds on the existing report-format idea; define it as a non-writing view,
   test combinations, and distinguish refusal from a clean file. Prettier #6885 and taplo #416 show demand/traps.
3. **M, medium:** `--json` with versioned per-file verdict, changed flag, refusal kind and duplicate
   warnings; clean stdout, diagnostics stderr. Never imply that refused means successfully formatted.
4. **M, medium:** diagnostics `path:line:col` when known; current parse diagnostics already name paths
   and lines. Keep byte offsets distinct from display columns, especially Unicode (rustfmt #7029).
5. **L, defer:** `# hocon-fmt: off/on` needs a written contract before code: whole-file off is the
   cheapest honest start; paired regions would preserve exact bytes, delimiters and includes, reject
   unpaired/nested markers, ignore marker-looking strings, and still validate the whole result.
   sconfig's value tree cannot splice arbitrary original regions safely. Never bypass safety checks.
6. **Already present:** stdin filename; document its intentional lack of config discovery and use flags
   for editor style. Do not advertise filesystem style parity for an input filter that reads no files.

### Process and docs

1. **S, high:** publish a style-stability policy before release; announce output-changing fixes,
   show diffs and pin dependency/binary versions. Black's annual policy is evidence, not a schedule to copy.
2. **S, medium:** repository `required-version` or CLI guard fails clearly on mismatches; artifact/hook
   pins already exist. Treat a parser upgrade as a style/safety change even when APIs still compile.
3. **S, high:** explain that refusals are the selling point: a skipped unsafe rewrite protects the
   working file. Count refusals visibly, while keeping the documented exit policy.

### We will not do

- **Range formatting:** no planned range printer. Black disables equivalence checks for ranges;
  rustfmt/clang/prettier leak outside selections. HOCON merges and includes make local edits nonlocal.
- **Formatter extension plugins:** no extra language printers or user rewrite hooks. Existing sbt,
  Gradle, Maven and Mill integrations remain useful; this recommendation concerns a new plugin API.
  dprint/Biome plugin boundaries add byte/comment ownership and cache-version risks.
- **Many style options, sorting, or comment reflow:** no widening policy surface to imitate clang-format;
  sorting crosses include precedence; reflow changes external-tool directives. Prettier's frozen options
  and gofmt's fixed style support keeping our few existing switches.

## What sconfig costs us

States checked with GitHub on 2026-10-09; latest release v2.0.0 (2026-08-24); probes use 1.12.4.
Counts are snapshots, overlap and must not be added. Sources: [limitations](../../limitations.md),
[corpus catalogue log](../../improvement-log/2026-10-08-48-corpus-catalogue.md) and example `seen-in` metadata.
No individual corpus frequency was published for rows marked “not counted”. Bare rendering for every
text probe is recorded in `actual.json`; upstream attribution needs that evidence, not analogy alone.

| limitation/refusal | user-visible cost / corpus evidence | owner | upstream state / date | local cost and outlook |
|---|---|---|---|---|
| Detached header/comment above blank line | 20/23 reference.conf; 343/5,692 comments; 263/265 community configs; 413/1,650 catalogue header shape | sconfig | [#646](https://github.com/ekrich/sconfig/issues/646) open 2026-10-08; [#647](https://github.com/ekrich/sconfig/pull/647) draft open | temporary fork + carrier M; **uncertain** |
| EOF/object-end/comments-only drop; empty-array or trailing array comments | CLI refuses; array-shaped corpus files ~30 in fork investigation; other counts not separated | sconfig | #647 carrier handles object/file ends; array retention not promised; [Java #149](https://github.com/lightbend/config/issues/149) closed 2015 | refuses S, carrier M; **uncertain** |
| `+=` after earlier assignment | broken/unstable output, not counted separately | sconfig | [#598](https://github.com/ekrich/sconfig/pull/598) merged 2026-09-30, unreleased; [#600](https://github.com/ekrich/sconfig/pull/600) open | second-pass guard S; **fixable upstream soon**, release timing unknown |
| Self-reference `a=1; a=${a}` / nested `foo=${foo.a}` | broken output; not counted | sconfig | delayed-merge work #598 merged; no separately verified fix for every shape | refusal S; **uncertain** pending release probes |
| Array substitution concat `${path} [ /usr/bin ]` | broken output; not counted | sconfig | #598 related; exact shape not verified fixed on released version | refusal S; **uncertain** |
| String substitution concat `${path}":d"` | broken output; not counted | sconfig | #598 related delayed merges; no separate release guarantee | refusal S; **uncertain** |
| Object substitution concat `${g} {name=east}` | 7/1,650 corpus inputs refused | sconfig | #598 merged 2026-09-30, unreleased | refusal S; **fixable upstream soon** |
| One-field array object loses braces (substitution or nested commented value) | valid array becomes invalid text; not counted | sconfig | #598 tests merge-array shapes; no verified released fix for all variants | refusal S; **uncertain** |
| Substitution cycles | unresolved banner is not a fixed point; not counted | sconfig | #598 merged; [Java #868](https://github.com/lightbend/config/pull/868) related open draft in source ledger | second pass S; **uncertain** per cycle |
| Default/env override | 157 root broken + 200 nested unstable = 357/1,650 | sconfig | #598 merged, #600 open, neither in v2.0.0 | second-pass refusal S; **fixable upstream soon** |
| Include object later replaced | `LostInclude`; not counted | shared | value parser drops the object intentionally; no specific upstream fix identified | masking/order guard M; **may never land** as a value-renderer feature |
| Same-line fields/include and merged paths crossing include | `MovedInclude`; no per-shape corpus count | shared | [Java #733](https://github.com/lightbend/config/issues/733) open since 2021; line origins lack column | order validation M; **uncertain** |
| Override barrier beside include disappears | accepted semantic corruption, two documented shapes; not counted | shared | no specific upstream fix located; value-tree deletion itself is expected behavior | source-definition safety design M/L; **may never land** upstream |
| `[]` environment list suffix | 1 genuine HOCON case among 142 not-HOCON refusals in corpus | sconfig | [#29](https://github.com/ekrich/sconfig/issues/29); [#605](https://github.com/ekrich/sconfig/pull/605) merged 2026-10-06, unreleased | refusal S; **fixable upstream soon** |
| Root array | valid HOCON cannot become a Config object; not counted | sconfig | documented API restriction; no release fix verified | refuse S; **may never land** without a different API |
| JS nesting ≥32 / JS direct includes | broken render / parser NotImplementedError; no corpus counts | sconfig | documented platform gaps; no verified release fix | masking M plus refusal S; **uncertain**; JVM probe results do not prove JS behavior |
| Lost blank lines and canonical spelling | 1,713/11,132 reference.conf lines removed; not a refusal | shared | value tree has no trivia model; intentional style | document S; CST L; **may never land** as value rendering |
| Native parse speed, JS render speed | Native parser ~27× JVM; 330 KB ~2s Native vs ~0.1s JVM from existing benchmarks | sconfig | no verified fix identified | measured budgets S; parser L; **uncertain** |

The other refusals (`NotUtf8`, `OtherFormat`, `ReservedName`, valid `include`-looking string falsely
masked) belong to our byte/name/masking boundary, not an upstream release wait. Conditional in-place
atomicity belongs to our adapters. These costs should never be advertised as sconfig defects.


What would owning the parse tree cost?
A lossless CST grammar and trivia-preserving printer is L, plus all-platform parsing, a HOCON conformance
oracle and migration of every refusal/golden/property. It would remove masking and tree-loss limits.
It also makes us responsible for every precedence/merge/string corner; this report makes no parser decision.

## Property testing plan

`HoconGen` already knows comments/includes and generates ragged separators, paths, joined entries,
arrays, objects and substitutions. Extend its model before multiplying assertions:

| generated invariant | missing generator / oracle | owner | cost / tier |
|---|---|---|---|
| comments retained once, no extra copies | duplicate identical bodies; inline/key/separator/comma/array/EOF/detached slots | shared | S; commit 100 cases, PR 1,000 |
| attachment follows policy | explicit expected owner/position; include boundaries and comment-looking literals | shared | M; PR 1,000 |
| check equals write decision | arbitrary UTF-8/invalid bytes, styles, all verdicts; use `Verdict` fast, sample actual CLI | ours | S; commit pure, PR process samples |
| accepted second pass exact | repeated keys, appends, unresolved merges and option cross-product | shared | S; commit goldens, PR 1,000 |
| original/output values equal | finite key/value model, concatenation spacing, CRLF literals, include override fixtures | shared | M; independent Lightbend Config JVM test-scope resolver |
| no crash for bytes/depth | bounded malformed bytes, huge indices, Unicode boundaries, depth near platform limits | shared | S; PR bounded, nightly stress |

Use Lightbend Config only in JVM tests, never core's dependencies. It shares ancestry with sconfig,
so add hand-specified values to avoid correlated false positives. Disable system/environment lookup,
then test optional variables unset/set in subprocesses; pin include fixtures and compare real resolutions.
A refused valid input is a separate coverage result, never evidence that value preservation passed.
Budget estimates, **not measured gates**: commit ≤1 extra minute, PR ≤3 across three runtimes,
nightly ≤15 (20,000 seeded documents), release ≤30 corpus/probe minutes. Measure before enabling;
cap generated size/depth and retain seed/minimal input on failures. Avoid a Cartesian explosion of
style/platform/generator dimensions: pairwise options in PR, full cross-product on release.

## How not to overdo tests

Admit a test only if it names the invariant and bug class, fails under a deliberate relevant mutation,
adds coverage beyond an existing assertion and declares its runtime tier. A property must know what
was generated; reparsing with the producer alone is not an independent assertion. These probe observations
are evidence, not mutation-validated regression tests; promote selected cases with red/green proof later.

Top ten to add first, in order:

1. Include overridden scalar/object barrier with real included fields (semantic-change, PR).
2. Literal CRLF string value equality (semantic-change, commit).
3. Repeated identical comment equality and injected duplication (comment-loss/other, commit).
4. Every golden's approved output is a fixed point (idempotence, commit).
5. Each file verdict check/write/mtime agreement (check-exit-codes, PR).
6. Stdin unchanged/refused/invalid UTF-8 stdout and exits (check-exit-codes, PR; extend existing suite).
7. Key/separator/comma/array/end comment slots (comment-movement, PR).
8. Unresolved merge/appends under both separators (idempotence, PR).
9. Controlled include/environment differential value oracle (semantic-change, nightly).
10. Corpus refusal ceilings and improvement ledger (parse-error-handling/idempotence, release).

## How we keep this up to date

Run `scripts/run-research-probes.sh` before every release. It builds the real JVM CLI, uses a private
Maven repository and compares observations, including stdout/stderr/exits/bytes, with the baseline.
Use `--record` only after reviewing changed observations; an improving refusal requires ledger triage.
A full replay takes about 12 minutes on the recording host (12 JVM workers, 958 CLI observations plus
the filesystem setups) over 967 small files, heavy for PR CI; the runner disables JVM perf data and
normalizes the temporary path inside `bare_sconfig_hex`, so the recording reproduces on another host.
If either becomes a burden, one packed corpus file (inputs and provenance in a JSON/TSV the runner
unpacks; per-input `.source` files kept only for promoted cases) is the cheap shape.
Run the three-platform suites separately; these JVM observations establish no JS/Native result.
Re-check citations with `scripts/verify-research-urls.py` from PR #98 (when merged); record source SHA,
new dead links and title mismatches, without silently rewriting historical research.

Generated with gpt-6.1-sol/m through [Codex](https://developers.openai.com/codex).
