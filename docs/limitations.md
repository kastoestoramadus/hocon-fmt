# Known limitations

## sconfig defects the formatter refuses

Each is reproduced with a bare sconfig parse-render round trip, with no code of ours involved.
`format` verifies its own output and returns a `Refusal`, so no integration writes the file.

Each has a **failing** test in `SconfigDefectsSpec` asserting what sconfig ought to produce. The
expected text is not guessed: each case is paired with a plainly written config that means the same
thing and renders correctly, and the test first asserts both `resolve()` to the same value. A
failure therefore prints a diff ready to paste into an upstream issue. Run them with
`sbt libraryDefects`, on all three platforms: 19 failures on the JVM and Native, 20 on Scala.js.
When a sconfig release fixes one, its test turns green: that is the signal to drop the refusal and
the entry below. sconfig 2.0.0 was tried on 2026-09-25: the regular suites pass on it, and the
defects then known remain.

Rendered as text that will not parse again (`Refusal.BrokenOutput`):

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

Rendered without a comment (`Refusal.LostComment`); a comment has no meaning to compare, so only
this check notices. sconfig attaches a comment to the field after it; one that ends up attached
to no field is dropped:

- **A comment no field follows**: after the last field of the file or of an object, and every
  comment of a file that holds nothing else.
- **A comment a blank line follows**: the blank line ends the attachment, so the comment is
  dropped although a field does come after it, and a blank line inside a comment block takes the
  part above it with it. This is what real files hit, since nearly all open with a licence header
  or a banner followed by a blank line: 20 of 23 reference.conf files from Akka, Pekko, Play,
  Kamon, Gatling and ssl-config are refused over one, 343 of their 5692 comments lost; so are
  263 of the 265 `.conf` files in the scala/community-build, which each open with `// <repo url>`
  and a blank line — strip those comment blocks and 261 of the 265 format. Nothing on sconfig
  main, among its open pull requests, or on the sHOCON `bugs-comments` branch fixes it (checked
  2026-10-08), and the gap is the same on sconfig 2.0.0.
  The command line and the plugins still refuse these. The project page's playground runs on a
  sconfig fork that keeps them and masks the rest, see below; the investigation is in
  [investigations/blank-line-comments.md](investigations/blank-line-comments.md).

<!-- UPSTREAM-SCONFIG: delete this paragraph once the option is released. -->
**What the playground keeps that the command line refuses** (`coreSite`, on the sconfig fork of
[site](site.md#running-ahead-of-the-release)): a comment above a blank line, and a block of comments
before a closing brace or the end of the file, including a file of comments only. Blank lines
themselves are still not kept. **What stays refused there too:** a block of comments before the
closing `]` or `)` of an array, since no field can stand in it (about 30 corpus files), a braced
root's header comment, and the files whose merges sconfig renders unstably. The fork's base also
renders some merges and `+=` appends that released sconfig breaks, so the page formats a few inputs
the command line refuses for those reasons. The page's placeholders get the same protection as the
include ones: user text that quoting could spell into `__COMMENT_` — `"__COMM""ENT_0"`, or an
escape — is kept, the prefix stepping aside for it, and a rendering that could not be told from
the placeholders is refused rather than restored on a guess.

Blank lines meet the same blind spot: the parse tree holds values and their comments and nothing
else, so blank lines are not kept at all — formatting the reference.conf corpus turns 1713 of its
11132 lines into 0. That is spacing rather than a comment, so the checks pass and the output is
accepted.

Dropped with its object (`Refusal.LostInclude`):

- **An include in an object that a later definition of the key replaces**: `o { include "x.conf" }`
  then `o : 5`. sconfig merges repeated keys, so the include no longer mattered, but its text would
  vanish.

Moved across a field (`Refusal.MovedInclude`):

- **An include that shares a line with a field**: `include "defaults.conf", zone = "us"`.
  `keepOriginOrder` sorts by the line a field starts on, an origin has no column, and fields on
  one line come out in no defined order (`z = 1, y = 2, x = 3` renders `x`, `y`, `z`). The include
  is masked as a field, so it moves like one; here it lands after `zone`, and since a later
  definition wins, `zone` then resolves to the included file's value instead of `us`. No open
  sconfig pull request orders same-line fields: the origin-line ones cannot, with no column to
  go by, so the fix would be to order by parse sequence.
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
  [ekrich/sconfig#598](https://github.com/ekrich/sconfig/pull/598), merged 2026-09-30,
  not yet released. The render-option follow-up
  [#600](https://github.com/ekrich/sconfig/pull/600) remains open, awaiting review and merge.
  Verified with `gh` on 2026-10-09: #598 `MERGED`, #600 `OPEN`, latest release v2.0.0
  (2026-08-24). The playground fork includes #598; the published core does not.

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
  refused (`Refusal.BrokenOutput`). 31 levels, and a dotted path of any length, are fine.

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

These are sconfig's renderer doing what the `ConfigFormatOptions` in `HoconFormatter` ask of it,
not defects. Meaning is preserved, original spelling is not. Pinned in `HoconSpecCoverageSpec`:

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
- number literals are canonicalised (`1.5e3` becomes `1500`)
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
  The Mill plugin always writes in place — a deliberate simplification, since the other plugins
  get a staged replacement from the Java API, and Mill needs none of that boundary. If the window
  matters to you, open an issue: we would gladly hear of a better solution.
  [Usage](usage.md) has the full write contract.

## Speed

sconfig's parser is about 27 times slower on Scala Native than on the JVM, and its renderer is the
slow part on Scala.js. A service's `application.conf` formats in about 1 ms on Native, well under
the process start-up; a generated 330 KB file takes about 2 s there against 0.1 s on the JVM.
Current numbers: `scripts/bench.py report`.

## What depending on sconfig means for your files

The published formatter uses sconfig to read values and print them again. That value tree loses
some source details: licence banners above blank lines, comments at the end of objects/files,
and definitions hidden by later definitions. The formatter checks for lost comments, broken output
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
