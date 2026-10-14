# Known limitations

Refused files are always left unchanged. File runs report refusals without failing by default,
so a green `--check` means no failing condition under the chosen policy, rather than a promise
that every selected file was formatted. `--fail-on-refusal` (or `fail-on-refusal = true` in
`.hocon-fmt.conf`) makes any selected refusal fail both format and check; `--no-fail-on-refusal`
clears it. The policy is independent of `fail-on-duplicates`: disabling one leaves the other
active. I/O, usage and config errors take precedence as exit 2; otherwise a check change or an
enabled refusal/duplicate failure gives 1. Stdin refusals already exit 1 under either policy.
The CLI summary counts skipped (refused) files. See [usage](usage.md#fail-on-refusal).

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

Rendered as text that cannot be read back safely (`Refusal.BrokenOutput`):

- **`+=` field separator**: `a : [1]` then `a += 2`
- **Self-referential substitution**: `a : 1` then `a : ${a}`
- **Array concatenation with a substitution**: `path = ${path} [ /usr/bin ]`
- **String concatenation with a substitution**: `path : ${path}":d"`
- **Nested self-reference**: `foo : ${foo.a}`
- **Object concatenation with a substitution**: `e = ${g} { name = "east" }`, the ordinary
  config-inheritance idiom, which sconfig renders as `e = ${g}name = east`. The one worth reporting
  upstream first: short, obviously wrong, and common.
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
The pre-check runs before include masking, parsing, rendering and duplicate analysis on JVM,
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

`Refusal.PathTooDeep(120)` leaves input whose **longest dotted key path exceeds 120 segments**
unchanged, with the reason `input dotted key paths exceed the maximum of 120 segments`. The same
early scan that bounds nesting counts the segments of a key path — the `a.b.c` of `a.b.c = 1` is
three — and refuses before include masking, parsing, rendering and duplicate analysis, on JVM,
Scala.js and Scala Native. A path's segments are counted the way sconfig splits them: at unquoted
dots, with adjacent quoted and unquoted pieces joining into one element (`"a"."b"` is two, `"a""b"`
is one), so dots inside a quoted key, a quoted value or an include target are never segments, and
`a . b` is two while `a b` is one. Key paths in `${...}` substitutions are skipped with the rest of
the substitution; siblings do not accumulate.

The bound exists because the duplicate report's work on a definition grows with the cube of its
path's segments (every prefix of every path is paired against the definitions), and the command
line runs that report on every file it examines. Bare sconfig is fast on the same input — a
2,000-segment `a.a.a. ... = 1` parses, renders and reparses in about 60 ms — but the duplicate
report spent 0.4 s at 500 segments, 2.8 s at 1,000 and 24 s at 2,000 on the JVM (measured
2026-10-14, warm in-process), so a 10 KB file could hang a run for tens of seconds. At the limit
itself the whole formatter, duplicate report included, needs milliseconds: a 120-segment path
formats in about 2 ms warm, under 1 s through the command line, most of it JVM start-up.

**The flattened path is bounded too.** The report's cost follows the *flattened* path, that is the
segments of every enclosing object plus the written ones, and the written-line limit alone saw one
statement: nesting re-created a long flattened path from short lines, and a file of 20 nested
levels, each a 120-segment path (2,400 flattened segments, 16.8 KB), took 34 s in the duplicate
report and 36 s through the command line — refused, but only after the report had run, since the
command line examines every file; 40 levels (4,800 segments) took 224 s (measured 2026-10-14 by an
independent review). The same scan now counts the flattened path: a `{` makes the key path just
written the enclosing one for every definition inside, a `}` takes it back out, and a definition
whose flattened path passes **360 segments** — three written maxima — is refused with the same
`PathTooDeep` reason naming 360. The 20-level file above is now refused by the scan in
milliseconds, before anything is parsed. A file at the limit itself costs the duplicate report
about 0.1 s per call on the JVM and about 1.2 s per call on the Native binary (measured
2026-10-14 in-process, three rounds: 88–185 ms JVM, 1,207–1,268 ms Native, for 7 × 45, 2 × 120 and
4 × 72 segment shapes); a shape at 720 flattened segments is refused in under a millisecond on
both. The bound was 720 until 2026-10-14: at that limit the Native report took about 9 s per
call, which is why the limit was halved. The corpus is unaffected: its deepest flattened path
measured 12 segments, and the 1,257-file corpus's verdicts are unchanged by the bound.

What the flattened bound does not change: the report's total work still grows with the number of
definitions, as every pass over a file does. Each definition inherits the enclosing segments, so
a file of many definitions under one long enclosing path costs more than a file of few, even when
every definition sits within the bound; within the bound, shortening the paths is what shortens
the run.

The written limit is ten times the longest dotted key path measured across the 34,644 files of the
corpus scanned on 2026-10-14, sources included — 11 segments, in a test source of the
apache-pekko-connectors repository — and the repository's own `.conf` files reach 3; the flattened
limit of 360 is thirty times the deepest flattened path the corpus reaches (12) and four times the
generous joint bound of its two recorded maxima (depth 8 times path 11). Longer paths can still be
formatted by splitting the definition (`a.b.c = 1` for `a { b.c = 1 }`), which the flattened bound
carries up to the same 360 segments — past about 2,400 the split form was refused as
`not valid HOCON: input nesting exceeds available stack capacity` even before this bound existed. A
path of 33 or more segments does not even parse on Scala.js today (see the
[library defects](#sconfig-defects-the-formatter-refuses) below), so there the guard only reaches
shapes the library refuses anyway, until a Scala.js release carries the fix.

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
- **The block is taken before any masking.** When the file contains an include, the source-level
  `__INCLUDE_` guard reads the file's own text, preamble included, and refuses a reserved spelling
  with `ReservedName`. Without an include, the preamble may contain that spelling: the review's
  `// __INCLUDE_0\n\na : 1` becomes `// __INCLUDE_0\n\na = 1`. Adding
  `include "defaults.conf"` before the field makes it refuse. Nothing of the preamble is parsed or
  restored, so it cannot be mistaken for a placeholder; an `include` in a comment is no directive.
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

Dropped with its object (`Refusal.LostInclude`):

- **An include in an object that a later definition of the key replaces**: `o { include "x.conf" }`
  then `o : 5`. sconfig merges repeated keys, so the include no longer mattered, but its text would
  vanish.

Moved across a field (`Refusal.MovedInclude`):

- **An include that shares a line with a field**: `include "defaults.conf", zone = "us"`.
  `keepOriginOrder` sorts by the line a field starts on, an origin has no column, and fields on
  one line come out in no defined order (`z = 1, y = 2, x = 3` renders `x`, `y`, `z`). The include
  is masked as a field, so it moves like one; here it lands after `zone`, and since a later
  definition wins, `zone` then resolves to the included file's value instead of `us`. The page
  fork includes the parse-sequence ordering fix (#645), so this same-line input formats there;
  the published core stays on 2.0.0 and refuses it.
- **A key defined again after the include**: `a.b = 1`, the include, `a.c = 2`. Repeated keys are
  rendered once, where the first appeared, so `a.c` would move before the include.

  `format` compares, for every include, the full paths of the keys defined before it in its
  object, before and after. Includes on their own lines, in any order, are unaffected, and so is
  an include after the fields on its line. Fields moving among themselves matter only against an
  include: a key defined twice is merged when parsing. For the same reason sorting fields would
  move includes across them, and is never offered.

Dropped in the include's object (`Refusal.ShadowedByInclude`):

- **A definition a later one replaces, with the include before it**: `include "f.conf"` (which
  defines `o.retained`), `o = 3`, `o.c = 7`. The scalar erased the included `o`, and `o.c = 7`
  replaced the scalar, so the file resolves `o` to `{c = 7}`; `o = 3` leaves no trace in the
  rendered tree and is dropped, after which the included `o.retained` merges into `o.c = 7`. This
  is the shape the duplicate report catches, reading a parse that keeps every definition.
- **An empty object, with the include before it**: `x.a = 5`, `include "scalar.conf"` (which
  defines `x = 3`), `x {}`. The empty object replaced the included scalar, so the file resolves
  `x` to `{}`; nothing of it survives the merge, it is dropped, and the include's scalar is what
  `x` resolves to. The report does not catch this one: a repeated object merges instead of
  replacing (see "What the duplicate report does not claim").
- **A definition in a later piece of an array concatenation**: `rows = [0] [{ include "f.conf"`,
  `q = false`, `q.child = 8 }]`, with `q = { retained = 91 }` in the included file. The file
  resolves `rows[1].q` to `{ child = 8 }` — `q = false` erased the included object and
  `q.child = 8` replaced the scalar — and formatted, the dropped `q = false` lets the included
  object merge back in, resolving `{ child = 8, retained = 91 }`. Each piece of a concatenation
  counts its elements from zero while the merged list counts them across the pieces, so the merged
  tree cannot say what stands at the definition's own element; the check refuses the definition
  rather than read the value of the element its path names there.
- **A definition in a piece of an object concatenation**: `app = {} { q = 0, q.a = 1, include
  "f.conf", q {} }`, with `q = 9` in the included file. The file resolves `app.q` to `{}` — `q {}`
  cleared the included scalar — and formatted, the dropped `q {}` lets the scalar through,
  resolving `q = 9`. An object piece merges with the pieces beside it, and the merged value at a
  path and line a piece writes may be the survivor of a definition the merge dropped, with nothing
  in the tree to tell that it is not the piece's own. An independent review found 18 sources of
  this shape, every one resolving differently once formatted; the doubt covers the piece whole, so
  the check reads nothing inside a concatenation off the merged tree.

- **A definition a kept twin's line vouches for**: `q = 0, q.a = 1, include "f.conf", q {}`, with
  `q = 9` in the included file. The file resolves `q` to `{}` — `q {}` cleared the included scalar —
  and the check formatted it until this rule, dropping `q {}`: the merged tree carried a value at
  `q` on that line (the kept `q.a = 1`), and the pair of path and line matched all three
  definitions, so the empty object passed for kept. The merge follows the source, so a definition a
  later twin on the same line erases cannot be the value the pair carries, and the pair no longer
  vouches for it — `q = 0` is erased by both `q.a = 1` and `q {}`, and `q {}` is refused. The doubt
  is one-sided: a twin in front cannot have replaced anything, so `include "f.conf", p {}, p = 3`,
  where `p = 3` is the value the merge keeps, still formats.

  The included file cannot be read at format time — a web page has no filesystem, and the target
  may be a URL — so the formatter cannot check whether the dropped definition mattered. It refuses,
  naming the include's line and the definition's line, and leaves the file for the user to rework by
  hand. Deleting the dropped line, moving the include below the definitions, or writing the value
  the file resolves to as one definition all format; which one is right depends on what the file was
  meant to say. `o = null` is not a way out: the null is dropped like any other replaced definition,
  and the include then writes the key again.

  This refuses a little too much. `x.a = 5`, the include, `x {}` is refused even when the included
  file says nothing about `x` (as one research probe's `f.conf` does): the check sees the text, not
  the included file, and a file whose included contents are unknown is not one to resolve on a guess.
  The doubt covering a whole piece refuses ordinary configs too: `app={servers=["one"]} { include
  "f.conf"` `pool {}` `pool.size=8 }` cannot change what the file resolves to when formatted — an
  oracle resolved it the same against every one of 29 included bodies tried — but nothing inside the
  concatenation vouches for `pool {}`, so it is refused like the hazards above. The doubt was
  measured once more with the one-sided same-line rule in place: reading object pieces off the
  merged tree again formats 331 sources the doubt refuses and lets five of them (21 comparisons
  across the reviewer's bodies) resolve differently, the empty object after the include merging away
  with no erasing twin on its line, so the doubt stays and those configs stay refused. Of the files
  in `examples/`, the golden files and the research probes, exactly the three that reproduce the
  first two cases are refused that were not before.

Re-parseable, but not a fixed point (`Refusal.UnstableOutput`):

- **Substitution cycle**: `a : ${b}` with `b : ${a}` renders as an unresolved-merge banner that
  parses but changes again on the next pass. A syntax check alone misses this.
- **Env override**: `host = localhost` then `host = ${?HOST}` stays an unresolved merge, rendered as
  a banner that grows by one on every pass; at the file root the banner does not even parse
  (`Refusal.BrokenOutput`). The most common idiom in Lightbend-style config: 357 of 1,650 real
  files from GitHub are refused for it. The fixed-point fix is
  [ekrich/sconfig#598](https://github.com/ekrich/sconfig/pull/598), merged 2026-09-30, and sconfig
  v2.1.0 (2026-10-12) carries it. The render-option follow-up
  [#600](https://github.com/ekrich/sconfig/pull/600) remains open, awaiting review and merge.
  Verified with `gh` on 2026-10-14: #598 `MERGED`, #600 `OPEN`, latest release v2.1.0
  (2026-10-12). The playground fork includes #598; the published core still depends on sconfig
  2.0.0, so the refusal stands until the dependency is upgraded and the pins re-checked.

Which refusal a defect gets, or whether it is refused at all, can depend on the options: the same
tree renders differently with `simplify-nested-objects = false`. Pinned over the examples in
`ExamplesSpec`; a file that is refused under one style is not thereby shown to format wrongly under
another, but nothing here promises the options agree:

- `catalogue/sconfig-defect` and `catalogue/env-override-root-not-parseable` are
  `Refusal.BrokenOutput` with the default nesting and `Refusal.UnstableOutput` without it.
- `catalogue/object-substitution-then-field` (`x = ${t} { b = 2 }`) is `Refusal.BrokenOutput` with
  the default nesting and formats without it.

`Refusal.ReservedName`: a file with an `include` is refused when the rest of its text spells
`__INCLUDE_` literally, or parses to a key or value containing it (including adjacent quoted or
unquoted token concatenations). This is the prefix of the include placeholders (see
[architecture](architecture.md#include-masking)); restoring user text as a placeholder corrupted
it silently. The source check also broadly refuses any case-insensitive `\u005f` underscore
escape, even in a comment or triple-quoted string where it does not resolve. Include targets are
masked before this check and remain verbatim. A file without an include is not affected.

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

## Speed

The pinned sconfig 2.0.0 dropped the exception-based loop control of 1.12.4, which cost about
2.3 us per `break` on Scala Native. Native `format` takes about 0.8 ms for a typical file (1.12.4:
3.6 ms) and about 0.55 s for a generated 330 KB file (1.12.4: 2.0 s; the JVM takes 0.1 s). Its
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
changed — is refused now (`Refusal.ShadowedByInclude`, above), and the two inputs that reproduced
it are in the catalogue. The duplicate warning remains useful evidence of the override; it is no
longer the only thing standing between the user and those bytes. The
[research probes](research/formatters/REPORT.md#probe-results) keep the inputs and their recorded
refusals. Tell us if a file is refused that you think is safe, including the smallest input and
the formatter version: the check sees the text, not the file the include names, so it refuses a
little too much by design. No upstream issue is posted by the research task.
