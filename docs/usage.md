# Usage

Every channel runs the same formatter and follows the same rules:

- **format** rewrites only files whose formatted text differs, as UTF-8.
- **check** writes nothing, names every unformatted file, and then fails.
- A file the formatter **refuses** (not HOCON, not UTF-8, or hit by a
  [known sconfig defect](limitations.md)) is reported with the reason and left byte-for-byte
  untouched. A refusal never fails the run: a `.conf` file that is not HOCON at all, such as an
  nginx config, is common enough that failing on it would make the tool unusable.

Library adapters expose refusals to the caller: the ZIO adapter uses a typed error for a
single operation and a per-file outcome for a streamed check, so the application controls its
run policy.

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

## ZIO library

Add `"eu.ww86" %% "hocon-fmt-zio" % "0.1.0"` on the JVM, or use `%%%` in a
Scala.js / Scala Native build. From a checkout, run
`sbt zioJVM/publishLocal zioJS/publishLocal zioNative/publishLocal` and use `0.1.0-SNAPSHOT`.
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
`NeedsFormatting` verdict writes: the adapter stages complete output beside the original,
retains POSIX permissions where supported, and atomically replaces it. Failure to replace
leaves the original untouched and removes the temporary file; filesystems without atomic
replacement produce an I/O error. Symbolic links are followed and retained. Replacement
creates a new file identity; other metadata and hard links are not preserved. Avoid concurrent
edits to the same file while formatting.

JVM and Scala Native provide both APIs. Scala.js provides `ZioFormatter` for text only;
`java.nio` file operations belong to the JVM and Native builds.
