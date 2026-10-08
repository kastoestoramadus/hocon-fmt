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
  config-inheritance idiom, which sconfig renders as `e: ${g}name: east`. The one worth reporting
  upstream first: short, obviously wrong, and common.
- **A one-field object inside an array that does not fit on one line** loses its braces: holding
  a substitution, `a : [ { b : ${?X} } ]` renders as `a: [ b: ${?X} ]`; so does one whose field is
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
  The investigation, with a probe that masks these comments, is in
  [investigations/blank-line-comments.md](investigations/blank-line-comments.md).

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

  **Not detected:** sconfig drops a definition that a later one of the same key overrides, and
  after an include that definition may have been what overrode the included file. Neither the parse
  of the source nor that of the output shows it, so the comparison above cannot:
  `include "f.conf"` then `o = 3` then `o.c = 7` renders without `o = 3`, and `x.a = 5`, the
  include, `x {}` renders without `x {}`. In both the included file's values for `o` and `x` now
  survive. Such a file is formatted today.

Re-parseable, but not a fixed point (`Refusal.UnstableOutput`):

- **Substitution cycle**: `a : ${b}` with `b : ${a}` renders as an unresolved-merge banner that
  parses but changes again on the next pass. A syntax check alone misses this.
- **Env override**: `host = localhost` then `host = ${?HOST}` stays an unresolved merge, rendered as
  a banner that grows by one on every pass; at the file root the banner does not even parse
  (`Refusal.BrokenOutput`). The most common idiom in Lightbend-style config: 357 of 1,650 real
  files from GitHub are refused for it. Fixed by ekrich/sconfig#600, not yet released.

Rejected at parse time although the specification allows them (`Refusal.NotHocon`):

- **An array at the file root**: `[ "a", "b" ]`
- **The `[]` env-variable list suffix**: `${MY_LIST[]}`

Scala.js only:

- **An object nested 32 or more deep** renders as text that fails to parse ("empty path") and is
  refused (`Refusal.BrokenOutput`). 31 levels, and a dotted path of any length, are fine.

- **Parsing text containing an `include`** throws `NotImplementedError`. See
  [architecture](architecture.md#platforms) for why the formatter is unaffected.

## Intentional normalisations

These are sconfig's renderer doing what the `ConfigFormatOptions` in `HoconFormatter` ask of it,
not defects. Meaning is preserved, original spelling is not. Pinned in `HoconSpecCoverageSpec`:

- `//` comments become `#`
- `=` becomes `:`
- nested objects are flattened to path keys (`setSimplifyNestedObjects`)
- triple-quoted strings become escaped single-line strings
- `+=` appends become the specification's expansion, the same value (`a += 2` renders as
  `a: ${?a}[`, with the `2` indented on the next line and the `]` on its own — what
  `examples/catalogue/plus-append-alone/expected/default.conf` pins, not `a: ${?a}[2]` on one
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

## Speed

sconfig's parser is about 27 times slower on Scala Native than on the JVM, and its renderer is the
slow part on Scala.js. A service's `application.conf` formats in about 1 ms on Native, well under
the process start-up; a generated 330 KB file takes about 2 s there against 0.1 s on the JVM.
Current numbers: `scripts/bench.py report`.
