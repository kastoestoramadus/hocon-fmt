# Usage

Every channel runs the same formatter and follows the same rules:

- **format** rewrites only files whose formatted text differs, as UTF-8.
- **check** writes nothing, names every unformatted file, and then fails.
- A file the formatter **refuses** (not HOCON, not UTF-8, or hit by a
  [known sconfig defect](limitations.md)) is reported with the reason and left byte-for-byte
  untouched. A refusal never fails the run: a `.conf` file that is not HOCON at all, such as an
  nginx config, is common enough that failing on it would make the tool unusable.
- Where a key is defined more than once, the later definition wins and the earlier one never takes
  effect; the CLI says so as a warning. It changes nothing that is written and no exit code, unless
  `--fail-on-duplicates` asks for it — [the duplicate report](#the-duplicate-report).

Library adapters expose refusals to the caller: the ZIO adapter uses a typed error for a
single operation and a per-file outcome for a streamed check, so the application controls its
run policy.

All JVM channels need Java 17 or newer, as Scala 3.8 does.

## Command line

```
hocon-fmt [--check] [--separator =|:] [--config <file>] [--fail-on-duplicates] <file>...
hocon-fmt --stdin [--stdin-filename <name>] [--fail-on-duplicates]
hocon-fmt --version
```

| exit code | meaning |
|---|---|
| 0 | done; with `--check`, every file is formatted or refused, and no finding failed the run |
| 1 | `--check` found an unformatted file, stdin was refused, or a finding met `--fail-on-duplicates` |
| 2 | the arguments could not be parsed, a config file could not be read or trusted, or stdin could not be read |

`--stdin` reads UTF-8 until EOF and writes only the formatted text to stdout, without a
summary. Already formatted input is returned unchanged. A refusal writes nothing to stdout,
prints the reason to stderr, and exits 1 so an editor can keep its original buffer.
`--stdin-filename` supplies a name for diagnostics only; it does not access that file.
Do not combine stdin mode with file arguments or `--check`.

```sh
hocon-fmt --stdin --stdin-filename application.conf < input.conf > output.conf
hocon-fmt --version
```

## Style: the separator and friends

The default style writes `key = value`. Three choices are yours to make, from the command line
or from a style file kept in the repository, so nobody has to pass flags on every run:

| flag | meaning |
|---|---|
| `--separator =\|:` | the token between a key and its value; `=` is the default |
| `--double-indent`, `--no-double-indent` | indent the contents of nested objects four spaces |
| `--simplify-nested-objects`, `--no-simplify-nested-objects` | flatten nested objects to path keys (the default), or keep the braces |
| `--config <file>` | read the style from this file instead of the one found for each file |

The style file is `.hocon-fmt.conf`, written in HOCON, and looked up in the formatted file's
directory and its parents, stopping at the first one found or at a directory containing `.git`,
so a style from outside a checkout does not reach in. The file above pins a repository that
wants the `:` spelling:

```hocon
# .hocon-fmt.conf
separator = ":"
double-indent = false
simplify-nested-objects = true
```

Precedence is **flag > config file > default**, field by field: a flag left off leaves the file
in charge of that one choice. An unknown key or a mistyped value is an error naming the file and
the key, and the run stops with exit 2 before any file is touched. `--stdin` applies the flags
but does no lookup, keeping the promise that `--stdin-filename` reads nothing.

```sh
hocon-fmt --separator : application.conf   # one file, the : spelling
git ls-files '*.conf' | xargs hocon-fmt --check   # the repository, each file styled by its .hocon-fmt.conf
```

The build-tool plugins below format with the default style; plugin settings and config-file
lookup there come after their migration to the java API.

`--version` prints the build version shared by all CLI runtimes and needs no input.

### The duplicate report

A key defined more than once resolves to its later definition: the earlier one, its text dropped
from the formatted file, takes no effect. The CLI points at both lines where it sees them:

```
config/application.conf:12: akka.logging-filter defined again at line 21; the earlier value never takes effect
```

Findings are warnings. They are printed in `format`, `--check` and `--stdin` runs (on stderr in
stdin mode, which keeps stdout the formatted text alone) and change nothing that is written. By
default they change no exit code either: a refusal still fails nothing, and a file that needs
formatting still fails `--check`. `--fail-on-duplicates` makes any finding fail the run with exit
1, and `--no-fail-on-duplicates` clears it again; a repository can ask for the failure in its
`.hocon-fmt.conf`:

```hocon
fail-on-duplicates = true
```

If the document traversal cannot read the file, the CLI prints
`WARNING: duplicate report could not run for <file>: <reason>`. This warning changes no exit
code; the formatting verdict still decides whether the file can be written.

Not every repeated path is a finding. The `x = "default"` then `x = ${?ENV}` idiom is not: the
earlier value is what an unset variable leaves standing. Neither is an object defined twice, which
merges, nor a later definition that holds a substitution, whose unresolved merge may still reach
the earlier value — see [what the report does not
claim](limitations.md#what-the-duplicate-report-does-not-claim).

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
Java API's `formatOrThrow` and the cats adapter raise.

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
formatted text differs — Gradle, Maven and sbt use this same file operation — and
`formatOrThrow` raises the core's
`FormatRefusedException` for callers that prefer an exception. Both compilers hold the caller to
the full set: the Kotlin `when` above is value-used with no `else`, and a Java 21 `switch` needs
no `default`; a missing branch is a compile error, not a run-time surprise. Over the core's own
enum Kotlin is worse than unchecked — a `when` missing a branch compiles and then throws
`NoWhenBranchMatchedException` at run time — which is the trap the mirror removes. On Java 17
every outcome is an `instanceof` away.

`formatFile` resolves symlinks first and retains the link. It stages complete UTF-8 output beside
the target and atomically replaces it only when the file and directory are writable and the
staged owner, group and all mode bits (setuid, setgid and sticky included) match after readback.
When identity cannot be proved, or the directory is unwritable, it writes in place; that fallback
retains the inode but cannot offer crash-atomicity. A staging failure leaves the original intact
and removes the temporary file; an unsupported atomic move is an I/O error. Refused and already
formatted files retain both their bytes and modification time. Atomic replacement leaves other
hard links on the old content; an in-place write updates them too. Serialise calls and avoid
concurrent edits to the same file. Mill continues to write in place through its core integration.

sbt builds and publishes the artifact (`eu.ww86:hocon-fmt-java-api`, no `_3` suffix — it is plain
Java) over the same sources the standalone Gradle build in `java-api/` tests: `sbt javaApi/publishM2`
puts it in Maven Local for development, and releases carry it to Maven Central with a POM that
pulls in the core and jspecify. A consumer declares only
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

The plugin resolves `eu.ww86:hocon-fmt-java-api` and its transitive core in an isolated
class loader, apart from sbt's Scala 2.12 runtime. It sends the original bytes to the API;
invalid UTF-8 and other refused files are reported and left untouched.

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
`dependencies { hoconFormatter("eu.ww86:hocon-fmt-java-api:<version>") }`; the plugin calls the Java
API, and the core comes with it, so naming the core alone would leave the worker without its entry point.

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

The plugin depends on `eu.ww86:hocon-fmt-java-api`, and the core comes with it; the two are
released in lockstep with the plugin, so a build has nothing else to declare.

## ZIO library

Add `"eu.ww86" %% "hocon-fmt-zio" % "0.1.0"` on the JVM, or use `%%%` in a
Scala.js / Scala Native build. From a checkout, run
`sbt coreJVM/publishLocal zioJVM/publishLocal` and use `0.1.0-SNAPSHOT`, adding the
`coreJS`/`zioJS` or `coreNative`/`zioNative` pair for those platforms: the adapter
depends on `hocon-fmt-core`, so publishing it alone resolves nothing.
The adapter uses ZIO 2.1.26 and has no cats or cats-effect dependency.

```scala
import java.nio.file.Paths
import zio.{IO, UIO}
import ww86.hocon_fmt.{Refusal, Verdict}
import ww86.hocon_fmt.interop.zio.{FileError, ZioFiles, ZioFormatter}

val formatted: IO[Refusal, String] = ZioFormatter.format("app.port=8080")
val decision: UIO[Verdict] = ZioFormatter.verdict("app.port=8080")
val path = Paths.get("application.conf")
val rewrite: IO[FileError, Verdict] = ZioFiles.format(path)
val report = ZioFiles.check(List(path, Paths.get("local.conf"))).runCollect
```

`ZioFiles.verdict(path)` checks without writing. It and `format(path)` put content
refusals in `FileError.Refused(reason)` and filesystem errors in `FileError.Io(cause)`,
wrapping a JDK call that fails unchecked (a path on a closed ZIP filesystem, say) in an
`IOException` that keeps the original as its cause. `format` returns the original decision:
`NeedsFormatting` means the write completed; `AlreadyFormatted` leaves the file untouched.
`check` is a lazy `ZStream` of `FileOutcome(path, Either[FileError, Verdict])`: it retains
failures as per-file outcomes and continues to the next path. The application decides how
to report them.

Files are read strictly as UTF-8 through blocking `java.nio` operations. Only a
`NeedsFormatting` verdict writes: the adapter stages complete output beside the original, then
atomically replaces the original only when the staged copy can be given the original's owner,
group and every mode bit, setgid included, and when both the file and its directory can be
written; otherwise the formatted text is written in place, which keeps the file but loses the
crash-atomicity of the rename. A failed or cancelled staged write leaves the original intact
and removes the temporary file; filesystems without atomic replacement produce an I/O error.
Symbolic links are followed and retained. A replacement is a new file, so other hard links keep
the old content; an in-place write keeps them too. Avoid concurrent edits to the same file
while formatting.

JVM and Scala Native provide both APIs. Scala.js provides `ZioFormatter` for text only;
`java.nio` file operations belong to the JVM and Native builds.
