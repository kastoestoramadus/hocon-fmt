# Architecture

## Division of responsibility

**`include` handling is ours; parsing and rendering are sconfig's.** This project exists to carry
`include` directives across a round trip sconfig cannot, and to refuse any file it cannot hand
back intact. When output is wrong for any other reason the bug is upstream: reproduce it against
bare sconfig in `SconfigDefectsSpec`, report it, and make `format` refuse the file. Do not work
around it in `HoconFormatter`.

## Modules

| module | what it is | platforms | depends on |
|---|---|---|---|
| `core` | `HoconFormatter.format: (String, FormatOptions) => Either[Refusal, String]`, `Verdict`, include masking | JVM, Scala.js, Scala Native | sconfig only |
| `cats` | `FileFormatter[F]`: file verdicts, identity-preserving formatting, streaming checks, opt-in refusal errors | JVM, Scala.js (Node), Scala Native | core, cats-effect, fs2-io |
| `zio` | `ZioFormatter`, blocking `ZioFiles`, identity-preserving formatting, per-file streamed outcomes | JVM, Scala Native; text on Scala.js | core, ZIO, zio-streams |
| `cli` | `CmdApi`, an `IOApp`: arguments, the `.hocon-fmt.conf` lookup, parallelism, report | JVM, Scala.js (Node), Scala Native | cats, cats-effect, fs2-io, decline |
| `java-api` | `HoconFmt` and the mirrored `Verdict` records and `RefusalKind` for Java and Kotlin callers; published as `eu.ww86:hocon-fmt-java-api` from sbt, tested by the standalone Gradle build in `java-api/` | JVM, Java 17 | core, jspecify |
| `web` | the formatter as a script for web pages: one global, `HoconFormatter` | Scala.js | core |
| `coreSite` | the core's sources against a sconfig fork that keeps detached comments, with the real `CommentCarrier`; not published | Scala.js | the fork |
| `site` | the project page — presentation, [playground](playground.md), contributions — on Laminar, calling `coreSite` directly; see [site](site.md) | Scala.js | coreSite |
| `sbt-plugin` | `hoconFormat`, `hoconFormatCheck` for sbt 1.x | JVM, Scala 2.12 | java-api, at run time |
| `gradle-plugin` | the same two tasks for Gradle; standalone Gradle build | JVM, Java 17 | java-api, at run time |
| `maven-plugin` | `hocon-fmt:format`, `hocon-fmt:check`; standalone Maven build | JVM, Java 17 | java-api |
| `mill-plugin` | `hoconFormat`, `hoconFormatCheck` for Mill 1.1.4 and later; standalone Mill build | JVM, Scala 3 | core |
| `npm/` | template of the npm package that wraps the Node build of the CLI | Node | cli |
| `python/` | builds the wheel that carries the native binary, for the pre-commit hooks | | cli |
| `bench` | times each formatter phase; see [testing](testing.md#benchmarks) | JVM, Scala.js, Scala Native | core |

`core` stays pure and depends on nothing but sconfig because the build-tool plugins load it into
their hosts. File effects live in `cats`, on cats-effect and fs2; the CLI delegates to it. The independent
`zio` library adapter offers text and file formatting on ZIO.

## The pipeline

1. `IncludeMasking.mask` swaps every `include` statement for placeholder fields.
2. The source and parsed tree are checked for placeholder collisions (see
   [include masking](#include-masking) and [comment carrier](#comment-carrier)).
   sconfig renders the masked tree with the `FormatOptions` asked for: the default
   style (`=`), or what the caller — the CLI's flags over a `.hocon-fmt.conf` — requested.
3. `IncludeMasking.unmask` puts the original statements back.
4. The result is refused if an include statement did not come back (`Refusal.LostInclude`) or a
   comment of the source is missing from it (`Refusal.LostComment`), and unless a second pass
   reproduces it: output that will not parse
   again is `Refusal.BrokenOutput`, output that changes again is `Refusal.UnstableOutput`. Input
   sconfig cannot read is `Refusal.NotHocon`.
5. Last, `IncludeOrder` refuses output in which an include has changed places with a field
   (`Refusal.MovedInclude`). The placeholder is a field to sconfig, which orders fields by the line
   they start on and fields sharing a line arbitrarily, so the include can end up on the other side
   of a field that a later definition would override. For each include it compares the full paths
   of the keys defined before it in its object, in the masked rendering and in the source; the source
   is read with `mask(_, onOwnLines = true)`, which puts each placeholder on a line of its own, since
   its real line does not say where it stood.

`HoconText` finds the strings and comments of a text in one pass. Masking asks it whether an
`include` is code, and the comment check asks it for each comment's text.

`Verdict.of(bytes, options)` wraps this for one file: it decodes strictly as UTF-8
(`Refusal.NotUtf8` rather than replacing bytes it cannot decode) and says whether the file is
already formatted, needs formatting, or must be left alone — under the style options given,
`FormatOptions.default` when none are. Every integration acts on a `Verdict`; they differ only
in how they find files and report — and in the name they pass (`Verdict.of(bytes, name)`,
`HoconFormatter.format(text, origin)`), which a refusal reports as where a parse tripped and which
rules out a file named `.json` or `.properties`. Formatting those writes HOCON where the name
promises another format, so nothing here touches them (`Refusal.OtherFormat`).

## The duplicate report

HOCON merges a key defined more than once, and the parse a format makes keeps only the winner:
`ConfigFactory.parseString("x = 1\nx = 2")` renders `x = 2` alone, the earlier definition gone
with its line. A report of definitions a later one replaces therefore cannot read that parse —
the merge is where the evidence is lost. `DuplicateReport` reads the syntax-preserving document
parse of the same text (`ConfigDocumentFactory.parseString`), whose tree keeps every field, and
counts lines by the renders of the nodes it passes in source order. That is one more parse of
the text, and the price of seeing what the merge discards; `format` and `Verdict` are untouched,
and the report never reads what an `include` names — the include is a node of the document tree,
so no masking is involved and the lines are the text's own.

Getting to the tree takes one implementation detail: sconfig publishes no traversal API
([lightbend/config#300](https://github.com/lightbend/config/issues/300)), so the document is
matched against `SimpleConfigDocument` and its `configNodeTree` read. The rest of what the report
touches — `ConfigNodeField.path` and `.value`, `ConfigNodeComplexValue.children`, the `Tokens`
helpers — is public but in packages sconfig documents as internal, so an sconfig upgrade is worth
a look at `DuplicateReport` even when it compiles.

A later definition replaces everything before it, except where it cannot: an object — written as a
block or as a dotted path — merges with an earlier object, and with an earlier substitution, whose
resolved object merges into it. Two fields of one object are two definitions of one path, so
`a { b = 1 }` and `a.b = 2` are the leaf reported; a dotted path also defines the objects on the
way to its leaf, which is how `logger = ERROR` then `logger.play = INFO` is reported. A later
definition holding a substitution replaces nothing: the merge stays unresolved, and the earlier
value is what the substitution falls back to when it is optional and unset. `+=` counts as one —
it is `${?key} [ ... ]` in another spelling.

Every array value has its own element scope, including pieces of a concatenation and arrays in
later definitions. Their index-zero objects cannot replace each other's fields. A replacement
of an ancestor ends its descendants' lifetime before a later object introduces fresh fields;
when an ancestor warning covers the same source and replacement lines, it covers those leaves
too. Leaves on separate lines retain their own warning.

The CLI prints a finding as a warning beside the file it examined, and only `--fail-on-duplicates`
(or its key in `.hocon-fmt.conf`) makes one fail a run; `cats`' `FileFormatter.inspect` hands the
verdict, findings and any report failure of one read to the CLI, and its `write` replaces the file
as `format` does. A failed document parse or an unsupported document tree produces a warning that the
duplicate report could not run, rather than an empty successful report. The plugins do not report
yet: they call the core through `JvmFacade`, which has no report.

## Include masking

Read this before touching `IncludeMasking`: it is the core algorithm, not incidental cruft. The
deeper fixes it works around were too invasive to land in sconfig.

Parsing resolves and discards `include` directives, so they cannot survive parse-then-render.
Each **whole statement** is swapped for a placeholder field before parsing and swapped back after:

1. `mask` finds `include` at a word boundary in code (outside strings and comments, as
   `HoconText` reports them), and the extent of the statement:
   a quoted target, or `required(...)` / `file(...)` / `url(...)` / `classpath(...)` with balanced
   parentheses. Anything else (`include_path`, the word in prose) is left alone. The statement
   becomes `__INCLUDE_<n>` plus a guard field; the original is kept in a side table.
2. The reserved name is checked, not trusted. With includes, the source check refuses extra
   literal `__INCLUDE_` occurrences and any case-insensitive `\u005f` underscore escape outside
   the masked include targets, including in comments and triple-quoted strings.
3. After parsing, keys at every object level, strings and array elements are checked for the
   reserved prefix. Only exact generated key/value pairs for issued indices are allowed;
   unresolved values are checked through their rendering, including substitution paths.
   Since a concatenated user key can replace even an identical generated pair during parsing,
   a second parse uses generated names absent from the first rendering and source. That tree
   permits no reserved user names or values. This extra parse is needed only for files with includes.
4. Before restoration, each generated index must appear exactly once as a complete placeholder
   field and once as a guard, with no other reserved text in the rendering. Missing placeholders
   remain `Refusal.LostInclude`; collisions are `Refusal.ReservedName`.
   Indices too long for an `Int` are the user's, never an exception.
5. `unmask` restores the statements and drops the guards. A placeholder counts only when its key
   and value carry the same index, compared exactly, so `__INCLUDE_0 : "__INCLUDE_01"` stays the
   user's own field. Own-line guards are removed before inline ones: the inline pattern does not
   consume the preceding newline, so the other order leaves blank lines behind. sconfig may render
   a guard on the very first line, so a newline is lent for that pass.

**Why a guard field:** `setSimplifyNestedObjects` collapses a single-field object into a dotted
path, so `o { __INCLUDE_0: v }` would become `o.__INCLUDE_0: v`, moving the placeholder out of its
object. A second field keeps the object from collapsing.

**Why the whole statement:** the previous scheme replaced only the keyword and commented out the
rest of the line, which swallowed closing braces and entries following the include.

## Comment carrier

<!-- UPSTREAM-SCONFIG: delete this section with the seam once the option is released. -->

sconfig attaches a comment to the field below it and drops one that attaches to nothing. The
fork the project page runs on has `setKeepDetachedComments` (ekrich/sconfig#646, draft #647), which
attaches a block a blank line detaches. A block that no field follows is still dropped: the last
lines of an object, the end of the file, a file of comments only.

`HoconFormatter` reaches this through `CommentCarrier` (`parseOptions`, and `mask` returning a
`Carried` with its `restore` and its tree judgement). Two sources exist and a project compiles
exactly one:

- `core/default-shared`, in `coreJVM`, `coreJS` and `coreNative`: does nothing, so the published
  core is unchanged and still depends on sconfig alone. There is no run-time switch.
- `core/site-shared`, in `coreSite` only: sets the option and masks those blocks like
  [includes](#include-masking), a placeholder field plus a guard, restored after the render. It
  runs after include masking. A block inside an array or parentheses is left alone, because no
  field can stand there, and the file is refused as a lost comment.

The placeholder prefix is chosen to occur nowhere the parse could put it: not in the masked text,
and not in its reading with quotes dropped and `\uXXXX` escapes resolved, where `"__COMM""ENT_0"`
spells `__COMMENT_0`. So the prefix steps aside for user text that could be rendered into it, and
the restore matches only what this pass generated. That reading is a source-level
over-approximation; the tree the parse made is judged as well, the way [include
masking](#include-masking) judges its own (`PlaceholderTree`), so a spelling the reading missed is
refused (`Refusal.ReservedName`) rather than restored. Both `:` and `=` are matched, the renderer
writing the asked-for separator. A rendering whose prefix occurrences are not exactly the three
each placeholder writes is left unrestored: the block is then a lost comment and the file is
refused, never altered. `IncludeOrder` gets the text with the comments already restored, so it
never sees a placeholder. The regex rules of include masking apply.

This is temporary: [site](site.md#returning-to-upstream-sconfig) lists what goes when sconfig
releases the option.

## Platforms

- **Regular expressions** must work on three engines. Scala Native runs `java.util.regex` on RE2:
  no lookaround, backreferences, possessive quantifiers or `\G` `\R` `\Z`. Scala.js translates to
  JavaScript's engine at ES2015, which has no multiline `^`. Hence `\binclude` rather than
  `(?<!\w)include`, indices compared in code rather than with `\1`, and a lent newline rather than
  `(?m)^`. The suites running on every platform are what enforce it.
- **Scala.js**: sconfig cannot parse text containing an `include` there (`NotImplementedError`),
  which is why the output check parses the masked form. Do not "simplify" that back.
- **java.time**: neither the Scala.js nor the Scala Native javalib has it, and sconfig needs it.
  `core` declares sjavatime `Provided`, as sconfig does, so an application supplies exactly one
  implementation; the CLI gets scala-java-time through cats-effect. Two implementations of the same
  package fail to link on Native.
- `CmdApi` reads arguments through decline's `PlatformApp.ambientArgs`, because Scala.js hands
  `main` no arguments; under Node they are in `process.argv`.

## Build-tool plugins and the Scala runtime

A formatter written in Scala 3 has to reach hosts that are not:

- **sbt 1.x** runs plugins on Scala 2.12, which cannot link against Scala 3. The plugin resolves
  the Java API by coordinates generated by sbt-buildinfo and calls `HoconFmt.check(byte[], name)`
  for checks and `HoconFmt.formatFile(Path)` for formatting
  reflectively in a class loader whose parent is the platform loader, the way sbt-scalafmt runs
  scalafmt. Resolution keeps
  the build's resolvers but not its Scala version, which would otherwise pin a 2.12 scala-library
  onto a formatter that needs Scala 3's. The shim dispatches on the verdict's simple name,
  reads its record accessors reflectively and reads the refusal kind through JDK `Enum.name()`.
  Original bytes cross the loader so invalid
  UTF-8 stays a refusal. An unknown verdict or an exception fails the task.
- **Gradle** compiles against the Java API but does not ship it: `eu.ww86:hocon-fmt-java-api` (the
  core comes transitively) is resolved through a `hoconFormatter` configuration in the consumer's
  build and runs in a Worker API class loader, so a Scala 3 library never lands on a buildscript
  classpath shared with other plugins. The worker reads the API's sealed `Verdict` records.
- **Maven** gives every plugin its own class loader, so the plugin depends on the Java API directly
  and the core comes transitively. `Outcome` reads the API's sealed `Verdict` records with an
  `instanceof` chain, as the Gradle worker does.

`java-api` publishes the JDK-typed boundary as the artifact `eu.ww86:hocon-fmt-java-api`: Java-only,
so no `_3` suffix and no Scala library inside, with the verdicts as records and a `RefusalKind` per refusal case — the mirror
exists because Scala 3 writes sealed-ness to TASTy, not the class file, so no Java compiler can
switch over the core's enums exhaustively. sbt builds it over the same sources the standalone
Gradle build in `java-api/` tests, and a contract suite in that build loads the sbt-published jar
the way the sbt plugin does: a `URLClassLoader` over the platform loader, reached reflectively,
since the two loaders hold distinct classes under equal names and only values cross.

**Mill** needs none of this: from 1.1.4 it runs on Scala 3.8.2, as the core does, so its plugin is
a Scala 3 trait that depends on the core and matches on `Verdict` directly. A Mill plugin works on
the Mill it was compiled against and on newer ones, so it is compiled against 1.1.4 and tested on
1.1.4 and the newest Mill.

The Java API owns file writes for Gradle, Maven and sbt. `formatFile` resolves the real path before
reading and stages complete UTF-8 output in the target directory. A pure JDK helper reads the unix
owner, group and twelve mode bits, adopts them on staging (mode last because chown can clear
setuid/setgid), then reads them back. Only a verified identity and writable target and parent
permit atomic replacement. Otherwise it writes in place, retaining the inode and losing
crash-atomicity; staging or atomic-move errors leave the original intact and clean up staging.
Refusals and already formatted files cause no write, including no modification-time change.
Symlinks survive; other hard links retain old content after replacement and share new content
with the in-place fallback. Mill stays on core and writes in place. The cats and ZIO adapters keep
their effect-specific, cross-platform implementations of the same replacement rule.
