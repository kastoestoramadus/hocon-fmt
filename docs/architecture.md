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
| `cli` | `CmdApi`, an `IOApp`: arguments, `.hocon-fmt.conf` filesystem access, parallelism, report | JVM, Scala.js (Node), Scala Native | cats, cats-effect, fs2-io, decline |
| `java-api` | `HoconFmt` and the mirrored `Verdict` records and `RefusalKind` for Java and Kotlin callers; published as `eu.ww86:hocon-fmt-java-api` from sbt, tested by the standalone Gradle build in `java-api/` | JVM, Java 17 | core, jspecify |
| `web` | the formatter as a script for web pages: one global, `HoconFormatter` | Scala.js | core |
| `coreSite` | the core's sources against a sconfig fork that keeps detached comments, with the real `CommentCarrier`; not published | Scala.js | the fork |
| `site` | the project page — presentation, [playground](playground.md), contributions — on Laminar, calling `coreSite` directly; see [site](site.md) | Scala.js | coreSite |
| `sbt-plugin` | `hoconFormat`, `hoconFormatCheck` for sbt 1.x and sbt 2, one cross-built source tree | JVM, Scala 2.12 and 3.8.4 | java-api, at run time |
| `gradle-plugin` | the same two tasks for Gradle; standalone Gradle build | JVM, Java 17 | java-api, at run time |
| `maven-plugin` | `hocon-fmt:format`, `hocon-fmt:check`; standalone Maven build | JVM, Java 17 | java-api |
| `mill-plugin` | `hoconFormat`, `hoconFormatCheck` for Mill 1.1.4 and later; standalone Mill build | JVM, Scala 3.8.2 | java-api, at run time |
| `npm/` | template of the npm package that wraps the Node build of the CLI | Node | cli |
| `python/` | builds the wheel that carries the native binary, for the pre-commit hooks | | cli |
| `bench` | times each formatter phase; see [testing](testing.md#benchmarks) | JVM, Scala.js, Scala Native | core |

`core` stays pure and depends on nothing but sconfig because the build-tool plugins load it into
their hosts. File effects live in `cats`, on cats-effect and fs2; the CLI delegates to it. The independent
`zio` library adapter offers text and file formatting on ZIO.

## Naming

Artifact ids, packages, plugin ids, hook ids and command names — each with the registry rule it
follows and the mismatches left open: [naming](naming.md).

## The pipeline

A lexical [depth check](limitations.md#excessive-nesting) runs before this pipeline and before
duplicate analysis: more than 80 brace/bracket levels in code refuses as `NestingTooDeep`, and
the same scan refuses a dotted key path longer than 120 segments or total report work above
300,000 units, as
[`PathTooDeep`](limitations.md#long-dotted-key-paths), with the same limits on every platform.
Each definition costs its squared flattened path length, including array element levels.
Strings and comments do not consume these budgets. This bounds report key-path work; sconfig
parsing and input-byte costs remain separate limitations.

1. A [preserved preamble](#the-preserved-preamble) is taken first: the leading block of comments
   and blank lines is kept verbatim and stands in for the parse as blank lines, one per line, so
   every line number stays the file's own and the block is put back in front of the formatted body.
2. `IncludeMasking.mask` swaps every `include` statement for placeholder fields.
3. The source and parsed tree are checked for placeholder collisions (see
   [include masking](#include-masking) and [comment carrier](#comment-carrier)).
   sconfig renders the masked tree with the `FormatOptions` asked for: the default
   style (`=`), or what the caller — the CLI's flags over a `.hocon-fmt.conf` — requested.
4. `IncludeMasking.unmask` puts the original statements back.
5. The restored output must be encodable as UTF-8: an unpaired UTF-16 surrogate is replaced
   when encoded. The result is also refused unless a second pass reproduces it. Output that cannot
   survive encoding or parse again is `Refusal.BrokenOutput`; output that changes again is
   `Refusal.UnstableOutput`. Input
   sconfig cannot read is `Refusal.NotHocon`. The second pass judges sconfig's mis-renderings
   before anything our own placeholders could have done to the text, which is why it comes first:
   an unresolved merge renders as a banner of comments nobody wrote, and that banner growing on
   every pass is the sharper reason to refuse the file than the comments it invented.
6. The output's comment multiset must be the source's, exactly: a comment dropped or printed twice
   is `Refusal.LostComment`. A difference alone would miss the second kind — sconfig's `+=`
   desugaring copies the comment of the appended field onto both halves of the expansion — and a
   comment nobody wrote is as much a change to the file as a lost one.
7. The order check refuses output in which an include has changed places with a field
   (`Refusal.MovedInclude`). The document engine keeps every statement where it stands, so the
   check is the order itself: the statements, whitespace collapsed, must appear in the output as
   many times and in the order the source gave them. (The placeholder walk this replaced — the
   retired `IncludeOrder` — located an include by the field the resolving pipeline substituted
   for it; see `docs/improvement-log/2026-10-08-34-include-order-refusal.md` for its history.)
8. `IncludeShadow` refuses output in which a definition the merge drops stood in the include's
   object on a path the include may write (`Refusal.ShadowedByInclude`). The placeholder makes the
   parse resolve the text as if the included file said nothing, so a definition a later one replaces
   — or an object that merges into an object beside it and adds nothing — leaves no trace in the
   rendered tree, although it may have been what kept the included file's values out of the path.
   `DuplicateReport` gives the definitions and the includes of the masked text (the same document
   walk the report reads), and the parsed tree's origins say which lines survived the merge: a
   value, an object or an array carries the line of the field that put it there. A dropped
   definition is refused over when another definition of its object names the same path or one above
   or below it and the dropping could let values through — it writes a value and a later definition
   makes the path an object again, or it is an object with nothing inside it and no later definition
   of the path follows. A definition a later definition of the same path on its line erases is not
   vouched for by the pair of path and line either: the merge follows the source, so the value at
   the pair is the later one's, and `q = 0, q.a = 1, include "f.conf", q {}` slipped through that
   hole until the doubt was made one-sided. A definition inside a piece of a concatenation is never
   vouched for by the merged tree, array piece or object piece: the piece counts its elements from
   zero where the merged list counts them across the pieces, and the merged value at a path and line
   a piece writes may be the survivor of a definition the merge dropped, with nothing in the tree to
   tell that it is not the piece's own. It counts as possibly dropped and its path is assumed to
   hold an object, refusing it rather than reading a value that may belong to another element or
   another definition.
   The check is deliberately conservative: the included file cannot be read at format time, so it
   refuses a form a later, better-informed pass could allow.

The include checks share indexes across includes in the same scope. The order check compares the
statements themselves, whitespace collapsed, so it holds no index of its own. `IncludeShadow` builds its
survival, same-line twin and related-path indexes once per object path and array scope. For an
empty object it remembers the latest surviving blocker, then finds the first include between that
blocker and the definition; suffix facts answer the questions about later definitions. Refusals
retain their original include order, then definition line and source order. Frozen test-only
implementations compare both direct checks and complete formatter results on generated and corpus
inputs.

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
duplicate report could not run, rather than an empty successful report. The Java API mirrors options, findings and report failures in JDK records. Its `inspectFile`
reads original bytes once, reports duplicates from those bytes and writes only a `NeedsFormatting`
verdict. sbt and Mill read these records reflectively; Gradle and Maven call them directly. All four apply the file's effective duplicate and
refusal policies. `fail-on-refusal` is false by default; when enabled it makes a refused selected
file fail either task, with no write. Java
`Inspection.failsOnRefusal` exposes this per-file decision across the isolated sbt loader too.

The CLI carries the file's effective policies in `CmdApi.Outcome`. Its `fails` combines check
changes, enabled refusal failures and enabled duplicate findings with OR; `Run.exitCode` gives
I/O errors precedence as 2, otherwise a failing outcome gives 1, otherwise 0. Usage and config
errors also return 2. Stdin refusals always return 1. The file summary counts skipped refusals,
including under the compatibility default, and clearing one policy does not clear the other.

`ConfigLookup` contains the pure lookup decision: a config wins even in a directory holding `.git`,
otherwise `.git` stops the walk, otherwise the parent is next. Filesystem existence probes, parent
paths, strict UTF-8 reads and error effects stay in CLI and Java API. `StyleOverrides` and
`FormatOptions.parse` are pure core values shared by these integrations; core still depends only
on sconfig. Gradle includes ancestor configs in task inputs and defers discovery of their paths
through a provider to preserve configuration-cache reuse.

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

## The preserved preamble

The commonest refusal in real files is a licence header or a banner: a block of comments that a
blank line separates from the field below, which sconfig drops (see
[limitations](limitations.md#the-preserved-preamble)). `Preamble` finds the block a file opens
with, and `HoconFormatter` takes it verbatim before anything is parsed, replacing it in the text
the parse reads with blank lines, one per line. Comments and blank lines are both non-tokens, so
the parse, the rendering and every origin line of the body are the ones the file itself would give:
a refusal that names a line names the file's own, and `IncludeShadow`'s two lines stay the lines a
user can find. What is returned is the preamble put back in front of the formatted body, so the
block keeps its marker, spacing and blank lines while the body takes every normalisation.

`Preamble` reads only the lines the file opens with, and reads them from their own first
characters: no string can be open before the first line of content, so a `#` or `//` that opens a
line there really opens a comment, and the scan stops at the first line that is neither blank nor a
comment. A large file pays for its header and no more.

The rule, and what it deliberately leaves out: the preamble is the run of blank and comment-only
lines the file opens with, less the comment block directly above the first line of the body — that
block is what the parse attaches to the field below it, and taking it would move it. What is left
must hold a comment: a file that opens with blank lines only has no preamble, and a file of
comments only has no body to format. The source-level collision guard (`__INCLUDE_` written out)
reads the file's own text, preamble included, when the file contains an include; only then does a
reserved spelling in the block refuse with `ReservedName`. Without an include, the review's
`// __INCLUDE_0\n\na : 1` formats to `// __INCLUDE_0\n\na = 1`; adding
`include "defaults.conf"` before the field makes it refuse. Nothing of the preamble is parsed or
restored, so nothing of it can be mistaken for a placeholder, and an `include` in a comment is no
directive to mask.

A BOM before the header stops this scan: `\ufeff# licence\n\na : 1\n` still refuses with
`LostComment(licence)`, also with an include after the header. A BOM after the detached header and
before the first field is accepted. A header before an empty root (`# head\n\n{}\n`) refuses
with `UnstableOutput`: rendering leaves only the preamble, which becomes a comments-only input
on the next pass. Comments-only input still refuses with `LostComment`. These boundaries of the
preserved path leave refused files unwritten.

`CommentCarrier.keepsDetachedComments` is what turns the path off: where the parse already keeps a
detached block (the page's fork), the block survives the ordinary path and the carrier renders it,
and the preamble path stays out of its way — one mechanism owns a block, never both. The seam goes
with the fork, the preamble path does not: it keeps what the released library drops.

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
refused, never altered. The order check gets the text with the comments already restored, so it
never sees a placeholder. The regex rules of include masking apply.

This is temporary: [site](site.md#returning-to-upstream-sconfig) lists what goes when sconfig
releases the option.

## Platforms

The shipped Scala Native CLI keeps the compiled 512m initial heap, an earlier mitigation that stays in
place for intermittent crashes: the trigger is established (concurrent fibres on the runtime's worker
threads; a standalone cats-effect program crashes too), the root cause is not, and the CLI now examines
its files sequentially — [limitations](limitations.md#sequential-file-examination-on-scala-native) has
the state of it. [The improvement log](improvement-log/2026-10-13-native-initial-heap.md) records what
was measured then and the checks to repeat on upgrades.

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
- **sbt 2** loads plugins with Scala 3.8.4, which cannot read the core's Scala 3.9 TASTy, so the
  same boundary is used from the same source tree: the `sbt-plugin` project cross-builds both
  flavours (`sbt-hocon-fmt_2.12_1.0` and `sbt-hocon-fmt_sbt2_3`), and only the task plumbing
  differs per host, in `src/main/scala-2.12` and `scala-3` — sbt 1's
  `CoursierDependencyResolution.retrieve` against sbt 2's `moduleDescriptor` and `update`, and
  `Def.uncached`, which sbt 2 requires of a task whose result holds a file. Scripted suites run
  each flavour against its own host (`sbtPluginTest`, `sbt2PluginTest`).
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

**Mill** compiles its `HoconFormatterModule` trait for the host's Scala 3.8.2 and Mill 1.1.4.
At task time it resolves `eu.ww86:hocon-fmt-java-api` and its transitive dependencies separately
from the module's classpath and Scala version forcing. A platform-parent `URLClassLoader` owns the formatter's Scala 3.9
runtime; only JDK values cross through reflective `inspectFile` calls. The loader closes after
each command. Mill's compiler sees neither core nor Java API classes. Unit tests and real Mill
1.1.4 / 1.1.10 builds enforce the boundary. The plugin remains in the second publish wave.

The cats and ZIO writers encode once and verify that decoding the bytes gives the original text
before creating a staged file or truncating the target. Both write paths use those verified bytes,
so a direct write cannot silently substitute an unencodable character. The Java API's
`Files.writeString` also reports encoding failures rather than substituting characters.

The Java API owns file writes for Gradle, Maven, sbt and Mill. `formatFile` resolves the real path before
reading and stages complete UTF-8 output in the target directory. A pure JDK helper reads the unix
owner, group and twelve mode bits, adopts them on staging (mode last because chown can clear
setuid/setgid), then reads them back. Only a verified identity and writable target and parent
permit atomic replacement. Otherwise it writes in place, retaining the inode and losing
crash-atomicity; staging or atomic-move errors leave the original intact and clean up staging.
Refusals and already formatted files cause no write, including no modification-time change.
Symlinks survive; other hard links retain old content after replacement and share new content
with the in-place fallback. The cats and ZIO adapters keep
their effect-specific, cross-platform implementations of the same replacement rule.
