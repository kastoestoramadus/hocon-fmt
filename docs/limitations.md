# Known limitations

Refused files are always left unchanged. File runs report refusals without failing by default,
so a green `--check` means no failing condition under the chosen policy, rather than a promise
that every selected file was formatted. `--fail-on-refusal` (or `fail-on-refusal = true` in
`.hocon-fmt.conf`) makes any selected refusal fail both format and check; `--no-fail-on-refusal`
clears it. The policy is independent of `fail-on-duplicates`: disabling one leaves the other
active. I/O, usage and config errors take precedence as exit 2; otherwise a check change or an
enabled refusal/duplicate failure gives 1. Stdin refusals already exit 1 under either policy.
The CLI summary counts skipped (refused) files. See [usage](usage.md#fail-on-refusal).

## Formatted documents and resolved previews

The Formatted pane and CLI preserve the written document: root arrays format, statements that
share a line keep that line and their commas, and spellings such as an unusual Unicode escape
or a value concatenation may be kept as written. The site's Resolved pane instead previews local
values with includes unloaded and environment variables disabled. It keeps source key order,
but resolution collapses duplicate definitions, substitutes values, omits comments, and may
normalize those spellings or reject a value the document parser can preserve (for example,
`a : [1]suffix`). The current fork already rejects malformed Unicode escapes such as
`"\u+041"` during document parsing; keeping spelling does not bypass syntax validation. This difference
is a preview limitation, not a new formatter refusal; Formatted retains the original document.

## Refusals in v0.1.0

The first release is the JVM core and the sbt 1.x, Gradle and Maven plugins. Native binaries,
npm, PyPI, Mill and sbt 2 plugins follow later. v0.1.0 ships with the current refusal list:
the formatter refuses files it cannot prove safe. The list has not been reviewed by hand yet;
the second release tightens it. Refusal never writes or breaks a file.

The JVM `--check` corpus refresh on 2026-10-13 at `c94d49e` measured **310 refusals out of
1,257 files (24.7%, rounded)**, with 869 files needing formatting and 78 already formatted.
It used 24 public repositories at pinned commits. The corpus includes parser test fixtures
and non-HOCON `.conf` files, so this is not a production prevalence estimate. These are JVM
CLI verdicts, not a measurement of the fork-backed playground. The measurement and source
files are recorded in [the improvement log](improvement-log/2026-10-13-release-scope-and-refusals.md).

For example, `include "defaults.conf", zone = "us"` is refused when formatting would move
the include across the field, potentially changing which value wins. File runs report the refusal and leave the original bytes
untouched. The playground uses a fork with the same-line ordering fix and formats this input
with the include still before `zone`. The full reproduction is under [moved includes](#sconfig-defects-the-formatter-refuses).

## Comment order on repeated keys

When a key appears more than once, plain sconfig 2.0.0 merges the objects and gathers their
comments above the merged key in reverse order. The comment check compares which comments are
present, not their order, so such a file is accepted. For example, `# first`, `a { x = 1 }`,
`# second`, `a { y = 2 }`, `# third`, `a { z = 3 }` formats to `# third`, `# second`, `# first`,
then one `a` with `x`, `y` and `z`. No comment is lost; they stay in the file, only their order
changes, and a marker comment may end up away from the lines it bracketed. This hits a small share
of the files that format today. It is not refused in v0.1.0; the second release is expected to
refuse or preserve it.

## sconfig defects the formatter refuses

The refused defects below are reproduced with a bare sconfig parse-render round trip, with no
code of ours involved. `format` verifies its own output and returns a `Refusal`, so no integration
writes the file. The same defect suite also tracks the accepted numeric limitation below.

Each has a **failing** test in `SconfigDefectsSpec` asserting what sconfig ought to produce. The
plain-equivalent cases first assert that both forms resolve to the same value; the numeric cases
compare string conversion or value type before and after rendering. A failure therefore prints a diff ready to paste into an upstream issue. Run them with
`sbt libraryDefects`, on all three platforms (plus the separate JVM detached-comment guard).
The 10,000-level library reproduction added for the depth guard was run only on the JVM;
Native deep-input reproductions require a process timeout and memory cap.
When a sconfig release fixes one, its test turns green: update the corresponding refusal or
numeric output pin and the entry here. The numeric cases below remain accepted. sconfig 2.0.0
was tried on 2026-09-25: the regular suites pass on it, and the defects then known remain.

Rendered as text that cannot be read back safely (`Refusal.BrokenOutput`) — the inputs below are
the shapes while the merge stands alone, and a field beside the merge or a leading comment can flip the verdict
([which one](#re-parseable-but-not-a-fixed-point-refusalunstableoutput)):

- **`+=` field separator**: `a : [1]` then `a += 2`
- **Self-referential substitution**: `a : 1` then `a : ${a}`
- **Array concatenation with a substitution**: `path = [ /bin ]` then `path = ${path} [ /usr/bin ]`
- **String concatenation with a substitution**: `path : "/bin"` then `path : ${path}":d"`
- **Nested self-reference**: `foo : { a : 1 }` then `foo : ${foo.a}`
- **Object concatenation with a substitution**: `g { x = 1 }` then `e = ${g} { name = "east" }`,
  the ordinary config-inheritance idiom. The one worth reporting upstream first: short, obviously
  wrong, and common.
- **A one-field object inside an array that does not fit on one line** loses its braces: holding
  a substitution, `a : [ { b : ${?X} } ]` renders as `a = [ b = ${?X} ]`; so does one whose field is
  an object and that holds a comment. Two fields, or a field that fits on one line, keep them.
- **An unpaired surrogate escape in a value or quoted key**, such as `a = "\ud83d"` or
  `"\udc00" = 1`: sconfig renders the raw UTF-16 half instead of an escape. The in-memory text
  parses again, but the UTF-8 encoder replaces the half with `?`, corrupting the written file.
  The output check refuses it before encoding. A paired escape (`"\ud83d\ude00"`) formats;
  backslash escapes in triple-quoted strings and comments remain literal text and format too.
  String API callers can also supply raw unpaired halves in those contexts or an include target;
  output containing one is refused. No rendering workaround is applied.
  Only the adjacent spelling of a pair formats: `a = "\ud83d" "\ude00"` concatenates two
  strings, renders as two halves separated by a space, and is refused for the same reason.

Rendered without a comment, or with it twice (`Refusal.LostComment`); a comment has no meaning to
compare, so only this check notices, and it compares the input's comments with the output's as
multisets — a comment is refused as much when the output prints it twice as when it drops it.
sconfig attaches a comment to the field after it; one that ends up attached to no field is
dropped:

- **A comment no field follows**: after the last field of the file or of an object, and every
  comment of a file that holds nothing else.
- **A comment on an appended field**: `# c` above `a += 2` is copied onto both halves of the
  desugared expansion — above the key and again inside the appended array — so the output holds a
  comment the author never wrote. Found in 12 of the 1,257 corpus files, all of them `+=` fields;
  eight of them formatted before the multiset check, and the write would have added a second copy
  of the comment. `SconfigDefectsSpec` pins the library's rendering of the same input.
- **A comment a blank line follows**: the blank line ends the attachment, so the comment is
  dropped although a field does come after it, and a blank line inside a comment block takes the
  part above it with it. This is what real files hit, since nearly all open with a licence header
  or a banner followed by a blank line: 20 of 23 reference.conf files from Akka, Pekko, Play,
  Kamon, Gatling and ssl-config are refused over one, 343 of their 5692 comments lost; so are
  263 of the 265 `.conf` files in the scala/community-build, which each open with `// <repo url>`
  and a blank line — strip those comment blocks and 261 of the 265 format. Nothing on sconfig
  main, among its open pull requests, or on the sHOCON `bugs-comments` branch fixes it (checked
  2026-10-08), and the gap is the same on sconfig 2.0.0.
  **A block at the very top of the file, a blank line under it, is kept by the command line** as a
  [preserved preamble](#the-preserved-preamble); everywhere else in the file these still refuse.
  The project page's playground runs on a sconfig fork that keeps them and masks the rest, see
  below; the investigation is in
  [investigations/blank-line-comments.md](investigations/blank-line-comments.md).

## Excessive nesting

`Refusal.NestingTooDeep(80)` leaves input with more than **80 simultaneous opening `{` or `[`
delimiters in code** unchanged, with the reason `input nesting exceeds the maximum depth of 80`.
The pre-check runs before parsing, rendering and duplicate analysis on JVM,
Scala.js and Scala Native. It uses constant space, stops at the first excess opening delimiter,
and does not depend on catching a stack error. Objects and arrays share the budget, including an
explicit root object; siblings do not accumulate depth. Strings (ordinary, escaped and
triple-quoted), `#` / `//` comments and the `${...}` of a substitution are ignored. This is a
lexical resource limit, not a syntax validator or a bound on the object tree implied by dotted keys.

The limit is ten times the deepest explicit nesting measured on 2026-10-13 in the documented
1,257-file corpus of 24 public repositories: **8**, including Akka's
`akka-cluster-sharding/src/main/resources/reference.conf` at
`164b3c6615b0ac1957c4ef13b0cff6e2a43a79c0`. All 1,255 UTF-8 inputs were scanned; the other two
already refuse as `NotUtf8`. The scan counted peak brace/bracket depth outside strings and
comments, in source order. Eighty gives substantial headroom for real files while bounding the
recursive parser and the include-aware passes before deeply nested input can consume minutes
and gigabytes. The bare sconfig 10,000-object JVM stack failure is pinned separately as a
`library:` case in `SconfigDefectsSpec`.

Style files (`.hocon-fmt.conf`) use the same pre-checks and return a configuration error naming
the file; the CLI exits 2 before touching selected files. A style file's dotted key path longer
than 120 segments — or one whose flattened path, the enclosing objects included, passes the bound
in the next section — is refused by that guard, with `<file>: input dotted key paths exceed the
maximum of N segments` naming the limit that fired. The entire style parse, including option validation and
diagnostic rendering, also catches stack exhaustion and returns `<file>: input nesting exceeds
available stack capacity` on one line. The `FormatOptionsSpec` cases for dotted style paths of 999,
2,999 and 9,999 segments accept any single-line named error, so they pass whichever check fires
first. The stack threshold depends on the runtime. The dotted-path cases were run on the JVM only;
Scala.js and Scala Native were not run against them.

## Long dotted key paths

`Refusal.PathTooDeep` leaves input whose **longest dotted key path exceeds 120 segments**
unchanged, with the reason `input dotted key paths exceed the maximum of 120 segments`. The same
early scan that bounds nesting counts the segments of a key path — the `a.b.c` of `a.b.c = 1` is
three — and refuses before parsing, rendering and duplicate analysis, on JVM,
Scala.js and Scala Native. A path's segments are counted the way sconfig splits them: at unquoted
dots, with adjacent quoted and unquoted pieces joining into one element (`"a"."b"` is two, `"a""b"`
is one), so dots inside a quoted key, a quoted value or an include target are never segments, and
`a . b` is two while `a b` is one. Key paths in `${...}` substitutions are skipped with the rest of
the substitution; siblings do not accumulate.

**The whole file has a report-work budget of 300,000.** A definition at flattened length `L` costs
`L²` units. A dotted statement of `w` segments inside an enclosing path of length `E` defines
every intermediate object too, so it costs `(E+1)² + ... + (E+w)²`. The scan adds these in
constant time per statement and refuses once their total exceeds the budget, with kind
`path-too-deep` and reason `too many or too long key paths to check safely; use shorter key paths
or split the configuration into smaller files`. A single 96-segment path
costs 299,536 units and passes the scan; 97 segments exceed the budget. Splitting it into nested objects does not remove that cost.

Shorten the actual full key paths, or split the configuration into smaller files and format each
part separately, preserving merge and include order. There is a conservative refusal band:
6,000 lines of `app.service.module.feature.optN = N` (249,780 bytes) cost 330,000 units and
refuse in 3 ms, while `main` before this budget formats them in about 1.0–1.4 s on the JVM;
12,000 lines (505,780 bytes) refuse too, versus about 4.2–5.2 s previously. At five segments,
5,454 lines fit and 5,455 do not. The default remains 300,000, 11.2 times the corpus maximum:
raising it changes refusal policy and needs an owner decision, rather than an extrapolation
from one fast shape. Nesting the same full paths retains their definitions and cost.

The flattened length includes every enclosing field path and **one segment per array element
level** (`k[0].f` has three segments). `{` inherits the field's path whether written as `a.b {`,
`a.b = {`, `a.b : {` or `a.b += {`; `[` also adds the element level. Every piece of an object or
array concatenation inherits the field's path. Closing the value removes its contribution from
the enclosing length; siblings inherit the same enclosing length and each contributes its own
work. The obsolete 360-segment flattened cap is removed with its boundary tests: at this budget
an enclosing path has at most 96 key segments (`S(96) = 299,536`) and 80 array levels, and even
an unfinished 120-segment statement stays below 360 before the next work check. Historical
slow-chain and spelling regressions now pin the work budget.

The previous per-path caps accepted a 160 KB file with two 120-segment chains and 200 leaves of
120 segments: its report took 12–13 s on the JVM, and 1,600 leaves took 122 s. Across 30 measured
dense shapes, squared definition lengths fit the **old algorithm's** timings better than cubes
(R² 0.99258 versus 0.98200; about 6.6 ns per squared unit). That constant does not calibrate
the new algorithm. The independent review measured about 20–34 ns per unit on large deep shapes
with the budget lifted for calibration, about 31–55 ns for object chains and 54–66 ns for array
chains. At the shallow end, 300,000 distinct root definitions cost about 4.4 microseconds per
unit (1.31 s warm). The budget is a conservative shape proxy, not a conversion to seconds;
it also caps the definition count because every definition costs at least one unit.

The budget is 11.2 times the corpus maximum of 26,725 units (8.91% of it). The historical corpus
maxima are depth 8, written path 11 and flattened path 12; the budget also accepts their combined
depth-8/path-11 shape and 79 nested short keys. The 2026-10-16 parity run found zero differences
in exact verdicts, formatted bytes and ordered findings on 1,257 corpus paths (1,255 readable)
and 697 repository `.conf`/`.hocon` paths (690 readable); unreadable paths retain that result.

The budget alone would still allow slow repeated short keys: 30,000 definitions of `x = 1`
cost only 30,000 units but took 10.06 s in the old report. The report now walks definitions
backwards and indexes the nearest later replacement at each path, preserving the findings and
their source order without pairing every twin. Both its index and warning-suppression set use structural ordering,
so equal-hash key names cannot make those operations quadratic: 32,768 colliding keys take
about 0.17 s, or under 0.4 s when every key produces a warning on one line. A differential property compares it with the
original algorithm on 1,000 generated documents per platform.

On Linux/WSL2, Java 25 JVM, among the compact near-budget shapes in the independent 2026-10-16
search, the slowest accepted **report** took 1.87 s cold / 1.31 s warm for 300,000 distinct root fields
(3.49 MB); the earlier 133-shape search found the same warm maximum. The Native 0.5.12 CLI
on that shape took **13.1 s and 6.06 GiB peak RSS** under an 8 GiB/no-swap scope; the JVM CLI
took 4.13 s and about 1.9 GB RSS. These are measured cases, not a wall-clock or memory guarantee.
The revision remeasured 300,000 roots at 11.56 s / 6.06 GiB on Native and 100,000 roots at
3.48 s / 2.04 GiB. A lower cap would materially reduce Native memory, but also refuse more
files; no separate root-definition cap is introduced without an owner policy decision.

**The accepted-file worst case is bounded here for the report's key-path work only.** Reading,
lexing, document parsing and rendering also depend on input bytes. For example, the report on
3/9 MB scalar arrays took 1.8/4.5 s in the review; empty objects and scalars consume bytes
without adding key definitions, and the CLI has no file-size limit. Scala.js also retains its
library's 33-segment parse limitation (see below).

### Slow sconfig object merges and appends (scan-level refusal)

Bare sconfig 2.0.0 parses many dotted definitions sharing an object quadratically. In the
independent review, **21,428 lines of `a.b.kN = 1` (310,310 bytes)** pass this scan but take
**20.6 s through the JVM CLI and 105 s through the Native CLI**; the report itself takes only
124–169 ms. Bare `ConfigFactory.parseString` on 4,000 / 8,000 / 16,000 such keys took about
0.9 / 2.1 / 8.9 s; spelling 16,000 keys as `a { b { k0 = 1 ... } }` took 13 ms.
The revision reproduced 0.66 / 2.02 / 8.71 s with the formatter's parse options;
independent root objects with the same path lengths took only 17 ms at 16,000 keys. The same
parsing defect exists on `main` before the report budget.

Repeated `k += 1` is a second shape of this parser problem: 300,000 lines accepted by the
resource scan did not finish the JVM CLI within 20 minutes of CPU in the review. Two red-by-design `library:`
cases in `SconfigDefectsSpec` compare bare parsing of shared dotted keys and repeated appends with
nested fields and a literal array; they stay red, target the library directly and run outside
ordinary formatter tests.

**The scan refuses these shapes before the parser spends its quadratic time.** The same walk
that counts report work now counts merge work: per object scope, every statement that writes a
dotted path or appends with `+=` charges the first segment of its path in the object it stands
in, a plain repeat of a one-segment key defines nothing through the key and charges nothing,
the Nth charge of one name costs `2N-1` — N definitions through one key are N² — and the
running total is refused past `MaxMergeWork = 2,000,000` with kind `slow-merges` and reason
`too many definitions merge through the same key; write them nested in { } or as one [ ]
array, or split the file`. The charge uses the scan's own segmentation: dots inside a quoted
key or a value are never segments, and `${...}` substitutions charge nothing.

Calibration: the corpus maximum is 22,512 units over 1,255 readable of 1,257 paths, 89 times
under the budget, and this repository's own files stay under 20 units, so no accepted file
changes verdict. The measured boundary sits at 1,414 dotted definitions through one prefix
(1,999,396 units; 1,415 charge 2,002,225 and refuse). On an otherwise idle box the largest
accepted shape formats through the cold JVM CLI in 1.55 s, while shapes far past the budget —
2,828, 5,657 and 16,000 dotted definitions through one prefix — refuse in 1.04–1.17 s of whole
CLI run; on `main` those seconds grow quadratically (net of a 4.9 s overhead on a busier box:
3.4 s at 2,000, 18.1 s at 5,657, 63.8 s at 16,000, over 145 s at 21,428). Untouched shapes
stay accepted at their old cost: 16,000 nested keys format in 3.41 s and 30,000 plain
definitions in 3.47 s on the same quiet box. These are measured shapes, not a wall-clock
guarantee; the budget is a shape proxy, as for the report work above.

Repeated `k += 1` charges the same units as a dotted merge. Under the budget it formats and keeps
every append as written (the document engine of #151; before it, sconfig's renderer turned the
chain into text its own parser rejected, a `BrokenOutput` refusal). Above it, the merge budget
refuses at the scan — 16,000 repeats of one key refuse within 1.12 s of cold JVM CLI, measured
before #151 — instead of paying the quadratic parse. Nested spelling avoids the shared-prefix parse cost
but retains the same report units; replacing repeated appends with an equivalent literal array
avoids both costs.

## The preserved preamble

A file that opens with a block of comments and blank lines, a blank line under the block and the
first field after that, keeps the block **verbatim**: `HoconFormatter` takes it before anything is
parsed and puts it back unchanged in front of the formatted body. Marker, spacing, blank lines and
line endings are the file's own; the body is formatted as always. This is what the command line
does about the commonest refusal in real files — 394 corpus files were refused for a lost comment,
and most of them open with a licence, an SPDX line or a banner — and a file whose body already
formats under such a header is `Verdict.AlreadyFormatted`: it is left exactly as it is, header and
all.

The rule is narrow, so that a block the parse needs never moves:

- **The preamble is the run of blank and comment-only lines the file opens with, without the
  comment block directly above the first line of the body.** That block is what the parse attaches
  to the field below (`# section` in `# head\n\n# section\na = 1` stays with `a`); only the blocks
  a blank line detaches are taken. Blank lines the file opens with are part of the preamble, so
  they survive too, which they do not anywhere else.
- **What is left must hold a comment.** A file that opens with blank lines only has no preamble:
  the leading blank lines are dropped as before. A file of nothing but comments has no body to
  format, so it has no preamble and is still refused.
- **Nothing of the block is parsed or restored.** It cannot be mistaken for a placeholder, and an
  `include` in a comment is no directive. (A source-level `__INCLUDE_` guard once read the file's
  own text, preamble included, and refused a reserved spelling when the file held an include; the
  masking retirement of 2026-10-24 took it away, and the review's `// __INCLUDE_0\n\na : 1`
  formats to `// __INCLUDE_0\n\na = 1` with or without an include.)
- **The body keeps every normalisation** listed under [intentional
  normalisations](#intentional-normalisations): `//` becomes `#`, `:` becomes `=`, nested objects
  are flattened, plain strings are unquoted, the body's line endings become `\n`. The preamble is
  the part that keeps its spelling, and the body is the only part that changed before.

What the preamble path does **not** cover, and what therefore still refuses as it always did: a
comment that a blank line separates from the field below anywhere but at the top of the file; a
comment at the end of the file or of an object; a comment before a closing brace; a block before
the closing `]` or `)` of an array, where no field could stand. The lines such a refusal names are
the file's own lines: the parse reads the preamble's lines as blank lines, one for one.

A nonempty braced root's header is covered: the body is formatted from `{`, and the header stands
before the flattened fields. An empty root is a boundary: `# head\n\n{}\n` refuses with
`UnstableOutput`, because rendering leaves only the preamble and the next pass treats it as a
comments-only document. Comments-only inputs still refuse with `LostComment`; no refused file is
written.

A UTF-8 BOM before the header is outside the preserved path: `\ufeff# licence\n\na : 1\n`
refuses with `LostComment(licence)`, including when an include follows the header. This is an
inherited safe refusal. A BOM after the detached header and before the first field is accepted.

Measured on a 1,257-file corpus of 24 public repositories at pinned commits (2026-10-11, one
`--check` run per file): refusals fall from 483 to 310, and 181 files format that were refused
before — 8 of them already formatted, so nothing is written. Eight files that formatted before are
refused now, each a comment on an appended field that the render prints twice; 73 stay refused with
another kind (`broken-output` 38, `unstable-output` 35), their output not settling, which the second
pass judges before the comment multiset. None of the 181 has a different comment multiset, each is a
fixed point, and the 153 the Lightbend 1.4.5 oracle can resolve are unchanged (5 it cannot read, 23
hold an include and are checked by the second pass alone).

<!-- UPSTREAM-SCONFIG: delete this paragraph once the option is released. -->
**What the playground keeps that the command line refuses** (`coreSite`, on the sconfig fork of
[site](site.md#running-ahead-of-the-release)): a comment above a blank line anywhere but in the
file's leading block, and a block of comments before a closing brace or the end of the file,
including a file of comments only. Blank lines elsewhere are still not kept, there or here. **What
stays refused there too:** a block of comments before the closing `]` or `)` of an array, since no
field can stand in it (about 30 corpus files), and the files whose merges sconfig renders
unstably. The fork's base also renders some merges and `+=` appends that released sconfig breaks,
so the page formats a few inputs the command line refuses for those reasons. The page's
placeholders get the same protection as the include ones: user text that quoting could spell into
`__COMMENT_` — `"__COMM""ENT_0"`, or an escape — is kept, the prefix stepping aside for it, and a
rendering that could not be told from the placeholders is refused rather than restored on a guess.

Blank lines meet the same blind spot outside a preserved preamble: the parse tree holds values and
their comments and nothing else, so blank lines are not kept at all — formatting the reference.conf
corpus turns 1713 of its 11132 lines into 0. That is spacing rather than a comment, so the checks
pass and the output is accepted.

Dropped with its object, moved across a field, or shadowed (`Refusal.LostInclude`,
`Refusal.MovedInclude`, `Refusal.ShadowedByInclude`): none of these refusals is reachable on the
document engine. A resolving parse deletes an include and renders only the surviving definitions,
so the formatter once refused, before the engine, an include in an object a later definition of
the key replaces (`o { include "x.conf" }` then `o : 5`); an include that shared a line with
fields, which a resolving render ordered unpredictably so a later field could resolve to the
included value (`include "defaults.conf", zone = "us"`); and — most conservatively, because the
included file cannot be read at format time — a definition the merge dropped beside an include on
a path it may write (`include "f.conf"` defining `o.retained`, then `o = 3`, `o.c = 7`), in an
object, an empty object, or any piece of a concatenation, where the merged tree cannot say which
definition a path and line belong to. An independent review found 18 sources of the object-piece
shape, every one resolving differently once formatted by that pipeline; the guard refused them
wholesale rather than guess.

The document engine (2026-10-23; masking and shadow guard retired 2026-10-24) made the whole
family moot: it keeps every statement node — includes, duplicate definitions, `+=`,
substitutions — writes them where they stand, and never loads an include. The shapes above
format: every definition is kept, a same-line include stays in front of whatever shared its line
(pinned in `IncludeOrderSpec`), and the duplicate report names a scalar definition a later one
replaces. The two `catalogue/include-shadowed-by-*` examples, refused since the research task,
format now. The guards stay for an engine regression: `LostInclude` and `MovedInclude` compare
the statements the source gave with the ones the output carries (`IncludeStatements`, whitespace
collapsed), a 13,272-case corpus sweep on the engine refused none, and a test seam that injects
a losing render is what exercises them. `Refusal.ShadowedByInclude` and `Refusal.ReservedName`
stay on the wire for enum consumers; the published core produces neither (the site's comment
carrier still fires `ReservedName` for its own placeholders).

Re-parseable, but not a fixed point (`Refusal.UnstableOutput`):

- **A merge a definition leaves behind**: `a : 1` then `a : ${a}`, or `a : [1]` then `a += 2`,
  can render as an unresolved-merge banner that parses but changes again on the next pass
  (`Refusal.UnstableOutput`). A syntax check alone misses this. For the self-reference, a field
  before the merge (`b : 2` above `a : 1`), a field after it at the root (`name = app`), or a
  leading comment (`# c`) gives this verdict; the BrokenOutput list names the merge-alone shape, and
  the paragraph below gives the measured examples.
- **A cycle written over definitions**: the specification's self-referential example, `a : 1`,
  `b : 2`, `a : ${b}`, `b : ${a}`, renders the same banner. A cycle between bare names, with no
  other definition of either key, does not refuse at all: `a : ${b}` with `b : ${a}` formats to
  the fixed point `a = ${b}`, `b = ${a}`, the second pass changes nothing, and resolution fails
  with the same cycle complaint on the input and the output — nothing is hidden, and there is no
  cycle detection to promise.
- **Env override**: `host = localhost` then `host = ${?HOST}`. Alone, at the file root or inside an
  object, it is `Refusal.BrokenOutput`: the banner does not even parse. With a field before it
  (`z = 1` above), a leading comment (`# c` above), or a field after it at the root (`name = app`),
  it is `Refusal.UnstableOutput`: the banner parses and changes again on the next pass. The most common idiom in
  Lightbend-style config: 357 of 1,650 real files from GitHub are refused for it. The fixed-point
  fix is [ekrich/sconfig#598](https://github.com/ekrich/sconfig/pull/598), merged 2026-09-30, and
  sconfig v2.1.0 (2026-10-12) carries it. The render-option follow-up
  [#600](https://github.com/ekrich/sconfig/pull/600) remains open, awaiting review and merge.
  Verified with `gh` on 2026-10-14: #598 `MERGED`, #600 `OPEN`, latest release v2.1.0
  (2026-10-12). The playground fork includes #598; the published core still depends on sconfig
  2.0.0, so the refusal stands until the dependency is upgraded and the pins re-checked.

Both refuse, and no refused file is written; which refusal a substitution merge earns depends on
the shape of the file around it, not only on the defect. Four measured inputs, with sconfig 2.0.0:

- `a : 1` then `a : ${a}`, alone: `Refusal.BrokenOutput`
- `b : 2`, `a : 1`, `a : ${a}`: `Refusal.UnstableOutput`, a field before the merge flips it
- `a : 1`, `a : ${a}`, `name = app`: `Refusal.UnstableOutput`, a field after the merge at the root flips it
- an object `o` holding `a = 1` then `a = ${a}`, followed by `name = x` after the object:
  `Refusal.BrokenOutput`; a sibling after the enclosing object does not flip it

The object concatenation (`g { x = 1 }` then `e = ${g} { name = "east" }`) and the nested
self-reference (`foo : { a : 1 }` then `foo : ${foo.a}`, with `name = app` after them) stay
`Refusal.BrokenOutput` in the shapes measured. These
examples are not a rule for every shape: the kind is pinned input by input in `HoconSpecCoverageSpec`.

Which refusal a defect gets, or whether it is refused at all, can depend on the options: the same
tree renders differently with `simplify-nested-objects = false`. Pinned over the examples in
`ExamplesSpec`; a file that is refused under one style is not thereby shown to format wrongly under
another, but nothing here promises the options agree:

- `catalogue/sconfig-defect` and `catalogue/env-override-root-not-parseable` are
  `Refusal.BrokenOutput` with the default nesting and `Refusal.UnstableOutput` without it.
- `catalogue/object-substitution-then-field` (`x = ${t} { b = 2 }`) is `Refusal.BrokenOutput` with
  the default nesting and formats without it.

`Refusal.ReservedName`: retired from the published core's formatting with the masking pass
(2026-10-24). It used to refuse, when a file held an `include`, any other literal `__INCLUDE_`
spelling, or a key or value parsing to one (including adjacent quoted or unquoted token
concatenations, and case-insensitive `\u005f` underscore escapes even in comments and
triple-quoted strings) — restoring user text as a placeholder would have corrupted it silently.
The document engine masks nothing and keeps comments and statements verbatim, so the spelling is
harmless and those files format. The refusal stays on the wire, and the site's comment carrier
still fires it for its own `__COMMENT_` placeholders (see
[architecture](architecture.md#comment-carrier)).

Rejected at parse time although the specification allows them (`Refusal.NotHocon`):

- **An array at the file root**: `[ "a", "b" ]`
- **The `[]` env-variable list suffix**: `${MY_LIST[]}`

Scala.js only:

- **An object nested 32 or more deep** renders as text that fails to parse ("empty path") and is
  refused (`Refusal.BrokenOutput`). 31 levels are fine. A dotted path of 33 or more segments
  (`k0.k1. ... .k32 = 1`) does not parse at all on Scala.js (`BugOrBroken: empty path`), while it
  parses on the JVM and Native. Both failures come from Scala.js's `java.util.ArrayDeque`, which
  loses elements when it grows while wrapped (33 pushes):
  [scala-js/scala-js#5439](https://github.com/scala-js/scala-js/issues/5439), fixed by
  [#5440](https://github.com/scala-js/scala-js/pull/5440) (merged on 2026-10-11, milestone 1.23.0, not yet released).
  Reported as [ekrich/sconfig#648](https://github.com/ekrich/sconfig/issues/648); these limitations
  go away with a Scala.js release containing the fix.

- **Parsing text containing an `include`** throws `NotImplementedError`. See
  [architecture](architecture.md#platforms) for why the formatter is unaffected.

## What the duplicate report does not claim

The report says a key's earlier definition takes no effect; it says nothing where the earlier
value can still matter, and it reads one text at a time.

- **An include is not read.** `include "defaults.conf"` then `x = 2` may well make the included
  file's `x` dead, and `x = 1` before the include may be dead as well; the report opens no file,
  so both are silent. Only definitions written in the text itself are compared.
- **A later definition holding a substitution is not reported.** `x = 1` then `x = ${y}`, with `y`
  defined elsewhere, resolves to `y` and leaves the `1` dead — but `${?y}` is the same shape with
  `y` unset, where the `1` is exactly what the result is. The report does not resolve, so it
  reports neither. `+=` counts as a substitution: it is `${?key} [ ... ]` in another spelling. The
  guard has a blind spot worth knowing: an optional override only works as the *last* definition
  of its key, so `x = 1`, `x = ${?ENV}`, `x = 2` does resolve to `2`, and both earlier definitions
  are reported.
- **An object defined twice is not itself a finding**, since the two merge; a leaf inside it that
  the later definition replaces is one, however deeply it is written (`a { b = 1 }` then
  `a.b = 2`). A dotted path defines the objects on the way to its leaf: `logger = ERROR` then
  `logger.play = INFO` replaces the scalar and is reported.
- **An object written over a substitution is not a finding** either, though it takes the shape of
  one: `a = ${b}` then `a.c = 2` resolves to `b`'s fields with `c` beside them.
- **Array elements belong to their own array value.** Concatenating `[{b=1}] [{b=2}]`, or
  appending the second array through `${a}`, preserves both objects. Their fields are never
  paired across array values; repeated fields within an element are still reported.
- **The finding's lines are the text's.** The first is the line the earlier field starts on — a
  value spanning lines counts from its key — and the second is the line of the definition that
  first replaces it, not of the last one.

## Intentional normalisations

These changes come from sconfig's renderer and the formatter's text handling. Original spelling
is not kept. Most preserve meaning; numeric rendering has the known limitation below. Pinned in
`HoconSpecCoverageSpec` and `NumericRenderingSpec`:

- `//` comments become `#`
- `:` becomes `=` — the default separator, and `:` on request (`--separator :`, or a
  [`.hocon-fmt.conf`](usage.md#style-the-separator-and-friends))
- nested objects are flattened to path keys (`setSimplifyNestedObjects`)
- triple-quoted strings become escaped single-line strings
- `+=` appends become the specification's expansion, the same value (`a += 2` renders as
  `a = ${?a}[`, with the `2` indented on the next line and the `]` on its own — what
  `examples/catalogue/messy/expected/default.conf` pins, not `a = ${?a}[2]` on one
  line): sconfig renders the expanded form, keeping no trace of the shorthand (an append after an
  earlier definition of the key is instead the `+=` field-separator defect above, and is refused)
- **Numbers converted to strings can change.** The renderer rewrites numbers to their canonical
  spelling: `1e5` becomes `100000`, `1.0` becomes `1`, and `0.10` becomes `0.1` (`1.5e3` becomes
  `1500`). With `a = 1e5` and `b = ${a}ms`, `b` then resolves to `"100000ms"` instead of
  `"1e5ms"`. This also happens one substitution away (`b = ${a}`, `c = ${b}ms`) and beside an
  include. If only another file concatenates the number, no check reading one text can see that
  use; the formatter does not read included files. A consumer's `getString("a")` changes from
  `"1e5"` to `"100000"` even without a substitution. `1e400` becomes `Infinity`, which re-reads
  as a string rather than a number. These cases are **not refused for changed numeric spelling,
  string conversion, or the `Infinity` type change**; the usual comment, include, parseability
  and stability checks still apply. This is a known renderer limitation, reported as
  [ekrich/sconfig#654](https://github.com/ekrich/sconfig/issues/654); Lightbend config 1.4.9
  produces byte-identical rendering for these numeric cases. `NumericRenderingSpec` pins the
  formatter's accepted output so a renderer fix calls for updated expectations.
- unicode escapes are resolved (`\u0041` becomes `A`)
- line endings become `\n`, and a UTF-8 byte-order mark is dropped
- a comment at the end of a line moves to its own line above

## Refused although valid

- **A value starting with the word `include` followed by a quoted string**, `a : include "x"`:
  the concatenation is read as a directive, and the file is refused as `Refusal.NotHocon`.

## Other formats a file's name promises

Lightbend's loader reads `.conf`, `.json` and `.properties` by extension, and the last two are
formats of their own. A round trip through sconfig hands back HOCON: a `.json` file comes back with
its objects reordered and its quoting gone, and a `.properties` value such as
`spring.datasource.url=jdbc:mysql://localhost:3306/db` is not readable as HOCON at all. A file whose
name ends in `.json` or `.properties` is refused whatever its content — `Refusal.OtherFormat` says
`a JSON file, and hocon-fmt formats HOCON only` — because formatting it would write a different
format under the name it has. Plugin file filters default to `.conf` and `.hocon`; a configured
include that reaches one of these is reported and left alone.

## Platforms and edge cases

- **Windows and a terminal's stdin are untested for now.** Every suite runs on Linux, in CI too;
  macOS appears in the release workflow only to link the native binary, and the command line's
  stdin cases feed it a pipe or a file, never a TTY. Nothing says either cannot work — nothing
  shows it does.
- **A symlink is followed, and the name that decides is its target's.** So `alias.json` pointing at
  `target.conf` is formatted, and `alias.conf` pointing at `target.json` is refused as a JSON file.
  A directory walk instead decides what to visit by the entry's own name: an `alias.json` symlink
  is not visited there, while an `alias.conf` one is visited and the target's name then decides.
  If you need the link's own name to decide, open an issue and tell us — the behaviour can change.
- **When the staged replacement cannot prove it keeps the file's owner, group and mode bits — or
  the file or its directory is not writable — the formatted text is written in place.** That keeps
  the inode, but not the crash-atomicity of a rename: the write truncates first, so an interrupted
  one can leave a partial file (a killed run on an unwritable directory left a 7.4 MB file empty).
  All JVM plugins use the Java API's staged replacement with the same in-place fallback. If the
  window matters to you, open an issue: we would gladly hear of a better solution.
  [Usage](usage.md) has the full write contract.

## Sequential file examination on Scala Native

The Native CLI examines its selected files one at a time, in the order they were given; the JVM
and Node CLIs examine them concurrently. The trigger is established: with concurrent fibres on the
runtime's worker threads (`parTraverse`), the Scala Native 0.5.12 binary sometimes dies with an
unhandled signal 11. A standalone cats-effect program with none of hocon-fmt's code crashes too.
The root cause is not established, and nothing about it has been confirmed upstream.

Measured on `--check` only, on one WSL2 machine:

- Parallel build: 12 of 300 runs crashed in the bisect, and 1 of 100 in each of two reruns.
- Sequential build: 0 abnormal exits in about 800 runs.
- Median wall time 554 ms parallel versus 1154 ms sequential, about 2x.

Zero in about 800 runs shows that the rate dropped sharply, not that the fault is gone. Other
machines, operating systems, write mode and `--report` were not measured. The binary also keeps
the compiled 512m initial heap, an earlier mitigation that stays in place.

The measurements and the minimal reproduction are not part of this repository. The maintainer
keeps them in the analysis workspace outside it (`work/codex-jobs/i5/report.md`,
`work/codex-jobs/j1/report.md`).

## Speed

The pinned sconfig 2.0.0 dropped the exception-based loop control of 1.12.4, which cost about
2.3 us per `break` on Scala Native. Native `format` takes about 1 ms for a typical file (1.12.4:
3.6 ms) and 0.3-0.4 s for a generated 330 KB file (1.12.4: 2.0 s; the JVM takes 0.1 s). Its
renderer is still the slow part on Scala.js. Current numbers: `scripts/bench.py report`.

## What depending on sconfig means for your files

The published formatter uses sconfig to read values and print them again. That value tree loses
some source details: detached comments outside the preserved leading preamble, comments at the
end of objects/files, and definitions hidden by later definitions. The formatter checks for lost comments, broken output
and unstable output and leaves those files unchanged. This protects your file; a refusal is not a
claim that your valid HOCON is wrong. The playground temporarily keeps more comments than the CLI.

Default-plus-environment overrides and some substitution/object/array combinations also hit renderer
limitations. Several fixes are merged upstream but not released; the ledger above names them. We will
re-check them when upgrading rather than silently enable formatting because a newer version compiles.

The one known safety gap the research left behind — an overridden definition beside an include
disappearing while its include remains, so the formatter wrote a file whose meaning silently
changed — is closed by the document engine rather than by a refusal (2026-10-24): the definition
no longer disappears, every statement is kept, and the duplicate warning names the override. The
two inputs that reproduced the gap are in the catalogue and format now; the
[research probes](research/formatters/REPORT.md#probe-results) keep the inputs with their recorded
refusals, dated as history. Tell us if a file is refused that you think is safe, including the
smallest input and the formatter version. No upstream issue is posted by the research task.
