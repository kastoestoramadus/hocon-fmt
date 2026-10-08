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
| `core` | `HoconFormatter.format: String => Either[Refusal, String]`, `Verdict`, include masking | JVM, Scala.js, Scala Native | sconfig only |
| `cats` | `FileFormatter[F]`: file verdicts, identity-preserving formatting, streaming checks, opt-in refusal errors | JVM, Scala.js (Node), Scala Native | core, cats-effect, fs2-io |
| `zio` | `ZioFormatter`, blocking `ZioFiles`, identity-preserving formatting, per-file streamed outcomes | JVM, Scala Native; text on Scala.js | core, ZIO, zio-streams |
| `cli` | `CmdApi`, an `IOApp`: arguments, parallelism, report | JVM, Scala.js (Node), Scala Native | cats, cats-effect, fs2-io, decline |
| `java-api` | `HoconFmt` and the mirrored `Verdict` records and `RefusalKind` for Java and Kotlin callers; published as `eu.ww86:hocon-fmt-java-api` from sbt, tested by the standalone Gradle build in `java-api/` | JVM, Java 17 | core, jspecify |
| `web` | the formatter as a script for web pages: one global, `HoconFormatter` | Scala.js | core |
| `site` | the project page — presentation, [playground](playground.md), contributions — on Laminar, calling the core directly; see [site](site.md) | Scala.js | core |
| `sbt-plugin` | `hoconFormat`, `hoconFormatCheck` for sbt 1.x | JVM, Scala 2.12 | core, at run time |
| `gradle-plugin` | the same two tasks for Gradle; standalone Gradle build | JVM, Java 17 | core, at run time |
| `maven-plugin` | `hocon-fmt:format`, `hocon-fmt:check`; standalone Maven build | JVM, Java 17 | core |
| `mill-plugin` | `hoconFormat`, `hoconFormatCheck` for Mill 1.1.4 and later; standalone Mill build | JVM, Scala 3 | core |
| `npm/` | template of the npm package that wraps the Node build of the CLI | Node | cli |
| `python/` | builds the wheel that carries the native binary, for the pre-commit hooks | | cli |
| `bench` | times each formatter phase; see [testing](testing.md#benchmarks) | JVM, Scala.js, Scala Native | core |

`core` stays pure and depends on nothing but sconfig because the build-tool plugins load it into
their hosts. File effects live in `cats`, on cats-effect and fs2; the CLI delegates to it. The independent
`zio` library adapter offers text and file formatting on ZIO.

## The pipeline

1. `IncludeMasking.mask` swaps every `include` statement for placeholder fields.
2. sconfig parses and renders the masked text with the options in `HoconFormatter`.
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

`Verdict.of(bytes)` wraps this for one file: it decodes strictly as UTF-8 (`Refusal.NotUtf8`
rather than replacing bytes it cannot decode) and says whether the file is already formatted,
needs formatting, or must be left alone. Every integration acts on a `Verdict`; they differ only
in how they find files and report — and in the name they pass (`Verdict.of(bytes, name)`,
`HoconFormatter.format(text, origin)`), which a refusal reports as where a parse tripped and which
rules out a file named `.json` or `.properties`. Formatting those writes HOCON where the name
promises another format, so nothing here touches them (`Refusal.OtherFormat`).

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
2. `unmask` restores the statements and drops the guards. A placeholder counts only when its key
   and value carry the same index, compared exactly, so `__INCLUDE_0 : "__INCLUDE_01"` stays the
   user's own field. Own-line guards are removed before inline ones: the inline pattern does not
   consume the preceding newline, so the other order leaves blank lines behind. sconfig may render
   a guard on the very first line, so a newline is lent for that pass.

**Why a guard field:** `setSimplifyNestedObjects` collapses a single-field object into a dotted
path, so `o { __INCLUDE_0: v }` would become `o.__INCLUDE_0: v`, moving the placeholder out of its
object. A second field keeps the object from collapsing.

**Why the whole statement:** the previous scheme replaced only the keyword and commented out the
rest of the line, which swallowed closing braces and entries following the include.

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
  the core by coordinates generated by sbt-buildinfo and calls `JvmFacade` reflectively in a class
  loader whose parent is the platform loader, the way sbt-scalafmt runs scalafmt. Resolution keeps
  the build's resolvers but not its Scala version, which would otherwise pin a 2.12 scala-library
  onto a formatter that needs Scala 3's. Only `FormatRefusedException` counts as a refusal; any
  other exception fails the task.
- **Gradle** compiles against the core but does not ship it: the core is resolved through a
  `hoconFormatter` configuration in the consumer's build and runs in a Worker API class loader, so
  a Scala 3 library never lands on a buildscript classpath shared with other plugins.
- **Maven** gives every plugin its own class loader, so the plugin depends on the core directly.

`JvmFacade.reformat(byte[]): Optional<String>`, throwing a checked `FormatRefusedException` whose
message is the reason, is the JDK-typed boundary all three share. `java-api` publishes that
boundary as the artifact `eu.ww86:hocon-fmt-java-api`: Java-only, so no `_3` suffix and no Scala
library inside, with the verdicts as records and a `RefusalKind` per refusal case — the mirror
exists because Scala 3 writes sealed-ness to TASTy, not the class file, so no Java compiler can
switch over the core's enums exhaustively. sbt builds it over the same sources the standalone
Gradle build in `java-api/` tests, and a contract suite in that build loads the sbt-published jar
the way the sbt plugin will: a `URLClassLoader` over the platform loader, reached reflectively,
since the two loaders hold distinct classes under equal names and only values cross.

**Mill** needs none of this: from 1.1.4 it runs on Scala 3.8.2, as the core does, so its plugin is
a Scala 3 trait that depends on the core and matches on `Verdict` directly. A Mill plugin works on
the Mill it was compiled against and on newer ones, so it is compiled against 1.1.4 and tested on
1.1.4 and the newest Mill.
