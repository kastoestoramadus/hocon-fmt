# Usage

Every channel runs the same formatter and follows the same rules:

- **format** rewrites only files whose formatted text differs, as UTF-8.
- **check** writes nothing, names every unformatted file, and then fails.
- A file the formatter **refuses** (not HOCON, not UTF-8, or hit by a
  [known sconfig defect](limitations.md)) is reported with the reason and left byte-for-byte
  untouched. A refusal never fails the run: a `.conf` file that is not HOCON at all, such as an
  nginx config, is common enough that failing on it would make the tool unusable.

All JVM channels need Java 17 or newer, as Scala 3.8 does.

## Command line

```
hocon-fmt [--check] <file>...
hocon-fmt --stdin [--stdin-filename <name>]
hocon-fmt --version
```

| exit code | meaning |
|---|---|
| 0 | done; with `--check`, every file is formatted or refused |
| 1 | `--check` found an unformatted file, or stdin was refused |
| 2 | the arguments could not be parsed, or stdin could not be read |

`--stdin` reads UTF-8 until EOF and writes only the formatted text to stdout, without a
summary. Already formatted input is returned unchanged. A refusal writes nothing to stdout,
prints the reason to stderr, and exits 1 so an editor can keep its original buffer.
`--stdin-filename` supplies a name for diagnostics only; it does not access that file.
Do not combine stdin mode with file arguments or `--check`.

```sh
hocon-fmt --stdin --stdin-filename application.conf < input.conf > output.conf
hocon-fmt --version
```

`--version` prints the build version shared by all CLI runtimes and needs no input.

Three builds of the same program:

| build | get it | start-up per run* |
|---|---|---|
| native binary | `pipx install hocon-fmt`, or `hocon-fmt-<os>-<arch>` from a GitHub release | 31 ms |
| Node | `npx hocon-fmt` | 140 ms |
| JVM | `sbt "cliJVM/run <args>"` from a checkout | 720 ms |

\* `--check` on one small file, averaged over 10 runs on one Linux machine.

## cats-effect library

`hocon-fmt-cats` supplies file operations on the JVM, Scala.js under Node and Scala Native.
Use `"eu.ww86" %% "hocon-fmt-cats" % "0.1.0"` on the JVM, or `%%%` in a cross-project.
Node applications also supply one `java.time` implementation, for example
`"org.ekrich" %%% "sjavatime" % "1.5.0"`; cats-effect already supplies one on Native.

```scala
import cats.effect.IO
import fs2.Stream
import fs2.io.file.Path
import ww86.hocon_fmt.interop.cats.FileFormatter

val formatter = FileFormatter[IO]
val file = Path("application.conf")
val verdict = formatter.verdict(file)          // IO[Verdict], strictly decoded from bytes
val outcome = formatter.format(file)          // IO[FormatOutcome]
val checked = Stream.emits(List(file)).covary[IO].through(formatter.check)
val required = formatter.formatOrRaise(file)   // refusal raised as FormatRefusedException
```

The API works with any `F[_]: Async` and `Files[F]`. `format` returns `Formatted`,
`AlreadyFormatted` or `Refused(refusal)`. Checks emit `(Path, Verdict)` pairs without writing.
IO errors use the effect's error channel; refusals stay values unless `formatOrRaise` is used,
whose exception carries the original `Refusal` in `.refusal`.

Formatting stages the complete output in a managed temporary directory beside the original,
then atomically replaces the original; a failed or cancelled staged write leaves the original
intact and cleans up staging. The staged file is only renamed over the original when it can be
given the original's owner, group and every mode bit, setgid included, and when both the file and
its directory can be written; otherwise the formatted text is written in place, which keeps the
file but loses the crash-atomicity of the rename. Symlinks are followed. A replacement is a new
file, so other hard links keep the old content; an in-place write keeps them too. Calls on the same
file must be serialised; `distinctPaths` canonicalises and deduplicates a list before parallel work.

## Try and Future

The core needs neither cats-effect nor fs2: `Verdict.of(bytes)` is a pure decision —
`NeedsFormatting(text)`, `AlreadyFormatted` or `Refused(refusal)` — and only `hocon-fmt-core` has
to be on the classpath. A caller whose channel carries values, not effects, lifts a refusal into
the one `FormatRefusedException`, which carries the typed `Refusal` in `.refusal`:

```scala
import scala.util.{Failure, Success, Try}
import ww86.hocon_fmt.{FormatRefusedException, Verdict}

val bytes: Array[Byte] = ??? // the file's content
val formatted: Try[String] = Try(Verdict.of(bytes)).flatMap {
  case Verdict.NeedsFormatting(text) => Success(text)
  case Verdict.AlreadyFormatted      => Success(String(bytes, UTF_8))
  case Verdict.Refused(refusal)      => Failure(FormatRefusedException(refusal))
}
```

```scala
import scala.concurrent.Future
import ww86.hocon_fmt.{FormatRefusedException, Verdict}

val formatted: Future[String] = Future(Verdict.of(bytes)).flatMap {
  case Verdict.NeedsFormatting(text) => Future.successful(text)
  case Verdict.AlreadyFormatted      => Future.successful(String(bytes, UTF_8))
  case Verdict.Refused(refusal)      => Future.failed(FormatRefusedException(refusal))
}
```

Both recipes are compiled in `TryAndFutureSpec`. Nothing refuses on its own: `Verdict` is a value,
and `FormatRefusedException` exists only where a caller or an adapter raises it — the same type the
build-tool facade and the cats adapter raise.

## Java and Kotlin

`eu.ww86:hocon-fmt-java-api` puts the same core behind types the JVM speaks natively: static
methods on `ww86.hocon_fmt.java.HoconFmt` return a `Verdict` that is a sealed interface of records —
`AlreadyFormatted`, `NeedsFormatting(formatted)` and `Refused(kind, reason)` — with a `RefusalKind`
constant per core `Refusal` case. The mirror exists because Scala 3 writes sealed-ness to TASTy and
not to the class file, so no Java compiler can switch over the core's enum exhaustively; the mirror
can, on Java 21. Nothing accepts or returns null: the package is JSpecify `@NullMarked`, which
Kotlin enforces as compile errors.

```java
import ww86.hocon_fmt.java.HoconFmt;
import ww86.hocon_fmt.java.RefusalKind;
import ww86.hocon_fmt.java.Verdict;

Verdict verdict = HoconFmt.checkFile(path);
if (verdict instanceof Verdict.NeedsFormatting needed) {
    System.out.println("Run me to rewrite " + path);
} else if (verdict instanceof Verdict.Refused refused
        && refused.kind() == RefusalKind.NotHocon) {
    logger.warn("Leaving {} alone: {}", path, refused.reason());
}
```

```kotlin
val verdict = HoconFmt.checkFile(path)
val shape = when (verdict) {
    is Verdict.AlreadyFormatted -> "$path is formatted"
    is Verdict.NeedsFormatting -> "would become: ${verdict.formatted()}"
    is Verdict.Refused -> "leaving alone: ${verdict.reason()}"
}
println(shape)
```

`check` judges text or bytes, `checkFile` a path, `formatFile` rewrites a file only when the
formatted text differs — the same whole-file `Files.writeString` the Gradle and Maven plugins
make, so a refused file is never touched — and `formatOrThrow` raises the core's
`FormatRefusedException` for callers that prefer an exception. Both compilers hold the caller to
the full set: the Kotlin `when` above is value-used with no `else`, and a Java 21 `switch` needs
no `default`; a missing branch is a compile error, not a run-time surprise. Over the core's own
enum Kotlin is worse than unchecked — a `when` missing a branch compiles and then throws
`NoWhenBranchMatchedException` at run time — which is the trap the mirror removes. On Java 17
every outcome is an `instanceof` away.

The module builds in `java-api/` like the Gradle plugin does, resolving the core from Maven Local
until it reaches Maven Central; a consumer declares
`implementation("eu.ww86:hocon-fmt-java-api:0.1.0")`.

## pre-commit

```yaml
repos:
  - repo: https://github.com/kastoestoramadus/hocon-fmt
    rev: v0.1.0
    hooks:
      - id: hocon-fmt            # rewrites files; the commit stops so you can stage them
      # - id: hocon-fmt-check    # or only report
```

The hooks run the native binary, installed from the `hocon-fmt` wheel as ruff's hooks
install ruff, so they need nothing but the Python pre-commit already runs on. Where there is no
native build, such as Windows, use `hocon-fmt-node` and `hocon-fmt-check-node`, which
run the Node build. On 20 files a hook run takes about 155 ms native against 290 ms on Node, and
its environment is 29 MB against 233 MB, mostly the Node that pre-commit downloads.

## sbt

```scala
// project/plugins.sbt
addSbtPlugin("eu.ww86" % "sbt-hocon-fmt" % "0.1.0")
```

| key | |
|---|---|
| `hoconFormat` | rewrite the files that are not formatted |
| `hoconFormatCheck` | fail if any file is not formatted |
| `hoconFormatSources` | the files; default `*.conf` and `*.hocon` in the Compile and Test resource directories |

Run in a project, a task covers that project and the projects it aggregates, so running it at the
root covers the build, and a resource directory two projects share is examined once. Neither task
is wired into `test` or `compile`, as with sbt-scalafmt; add `hoconFormatCheck` to CI explicitly.

```scala
hoconFormatSources := (baseDirectory.value / "conf" ** "*.conf").get
```

## Gradle

```kotlin
plugins {
    id("eu.ww86.hocon-fmt") version "0.1.0"
}
repositories { mavenCentral() } // the plugin resolves the formatter through the project

hoconFormatter {
    source.setFrom(fileTree("config") { include("**/*.conf") }) // default: *.conf and *.hocon under src
}
```

`hoconFormat` rewrites; `hoconFormatCheck` fails on an unformatted file and runs as part of
`check`. Both are configuration-cache compatible, and the check is up to date while neither the
files nor the formatter change. To pin another formatter version:
`dependencies { hoconFormatter("eu.ww86:hocon-fmt-core_3:<version>") }`.

## Mill

```scala
//| mvnDeps:
//| - eu.ww86::mill-hocon-fmt::0.1.0
package build

import mill.*, javalib.*
import ww86.hocon_fmt.mill.HoconFormatterModule

object app extends JavaModule, HoconFormatterModule {
  object test extends JavaTests, TestModule.Junit5, HoconFormatterModule
}
```

`./mill __.hoconFormat` rewrites; `./mill __.hoconFormatCheck` fails on an unformatted file. Mill
1.1.4 or newer. The trait covers the module it is mixed into, so a test module needs it too.
`hoconFormatSources`, the module's `resources` by default, lists directories to search for
`*.conf` and `*.hocon`, or single files:

```scala
override def hoconFormatSources = Task.Sources("conf")
```

## Maven

```xml
<plugin>
  <groupId>eu.ww86</groupId>
  <artifactId>hocon-fmt-maven-plugin</artifactId>
  <version>0.1.0</version>
  <executions>
    <execution>
      <goals><goal>check</goal></goals> <!-- binds to verify -->
    </execution>
  </executions>
</plugin>
```

`mvn hocon-fmt:format` rewrites. Parameters, relative to the project directory:

| parameter | default |
|---|---|
| `includes` | `src/**/*.conf`, `src/**/*.hocon` |
| `excludes` | none |
| `skip` (`-Dhocon-fmt.skip`) | `false` |
