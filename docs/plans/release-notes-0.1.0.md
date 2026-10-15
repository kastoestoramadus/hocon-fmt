# hocon-fmt 0.1.0

The first release: a formatter for HOCON that keeps what it cannot format, on the JVM. It runs
from sbt, Gradle or Maven, from the command line, or from a library, and every channel makes the
same decision about a file.

## What is in it

| artifact | for | how |
|---|---|---|
| `eu.ww86:sbt-hocon-fmt` | sbt builds | `addSbtPlugin("eu.ww86" % "sbt-hocon-fmt" % "0.1.0")` |
| `eu.ww86.hocon-fmt` (Gradle Plugin Portal) | Gradle builds | `plugins { id("eu.ww86.hocon-fmt") version "0.1.0" }`; `hoconFormat` rewrites, `hoconFormatCheck` fails on an unformatted file and runs as part of `check` |
| `eu.ww86:hocon-fmt-maven-plugin` | Maven builds | the `<plugin>` with groupId `eu.ww86` and version `0.1.0`; `mvn hocon-fmt:format` rewrites, `check` binds to `verify` |
| `eu.ww86:hocon-fmt-cli_3` | the command line on the JVM | `cs launch eu.ww86:hocon-fmt-cli_3:0.1.0 -- --check <paths>`; `--stdin`, exit codes; Java 17 or newer |
| `eu.ww86:hocon-fmt-core_3` | Scala 3 libraries | `Verdict.of(bytes)`: a pure decision, no effects, no file access |
| `eu.ww86:hocon-fmt-java-api` | Java and Kotlin | `HoconFmt.checkFile(path)`, JSpecify `@NullMarked` |
| `eu.ww86:hocon-fmt-cats_3` | cats-effect applications | the file operations the CLI uses |

## What it formats

A `.conf` or `.hocon` file in a directory that is walked, or a file named on the command line —
which is formatted whatever its extension, unless it is `.json` or `.properties`. The default style
is `key = value`, nested objects flattened to path keys (`a { b = 1 }` becomes `a.b = 1`), and no
extra indentation for nested objects. `.hocon-fmt.conf` in the file's directory or above it, up to
the directory holding `.git`, can set `separator`, `double-indent`, `simplify-nested-objects`,
`fail-on-duplicates` and `fail-on-refusal`; a flag on the command line beats the file, and an
unknown key is an error, never ignored.

Some normalisations are the design, not a bug: `//` becomes `#`, a `:` separator becomes `=`
unless asked otherwise, number literals are canonicalised, a byte-order mark is dropped, and a
trailing comment moves to its own line. The output means what the input meant; it does not spell
it the same way.

## Refusing rather than corrupting

A file the formatter will not format is **refused**: reported with the reason, left byte for byte
untouched, and it does not fail the run. A `.conf` file that is not HOCON at all, a file that is
not UTF-8, a `.json` file, a file whose formatting would lose a comment or an include, and the
sconfig defects listed in [limitations](../limitations.md) all end that way. The name is the one
the file is known by: a symlink is followed first, so a `.json` alias of a `.conf` target is
formatted, and a `.conf` alias of a `.json` target is refused — tell us if you need a link's own
name to decide instead. A file that cannot be *read or written* is a different thing:
`cannot read <path>: <reason>` on stderr and exit code 2, so a typo in a CI path cannot pass
silently.

`--check` writes nothing and exits 1 when a file needs formatting; `format` rewrites only the files
whose text differs, as UTF-8. The library and the plugins act on the same `Verdict`: a refusal is
a value, never a write, and never a failed build unless a fail-on policy asks for it, as
`fail-on-refusal` and `fail-on-duplicates` do.

## Known limits, in full in [limitations](../limitations.md)

- **A comment above a blank line is refused**, so most real files are: 20 of 23 `reference.conf`
  files surveyed from Akka, Pekko, Play, Kamon, Gatling and ssl-config open with a licence banner
  and a blank line, and the comment would be dropped. It is the limit most real files hit.
- **A substitution sconfig cannot render is refused**: the `x = "default"` then `x = ${?ENV}`
  idiom (357 of 1,650 real files surveyed), an object, array or string concatenation with a
  substitution (`child = ${base} { b = 2 }`), a substitution that refers to itself, and `+=` after
  the key is defined. `${?ENV}` on the first definition of its key formats; only an override after
  one is refused. An upstream fix for it has shipped (sconfig 2.1.0); the core still depends on
  sconfig 2.0.0, so the refusal stands until the dependency is upgraded.
- **Includes**: an include that shares a line with a field, or a key defined again after one, is
  refused rather than moved across it.
- **A root-level array, the `${MY_LIST[]}` suffix, and `a : include "x"` are refused** although the
  specification allows them.
- **A file with an include that spells `__INCLUDE_` is refused**, since that word writes the
  placeholders the formatter stands for the includes with.
- Blank lines are not kept, and duplicate definitions collapse to the later one, which the CLI and
  the plugins report as a warning (failing the run only with `fail-on-duplicates`).
- **Deep input is bounded before it is parsed**: input with more than 80 simultaneous opening
  `{` or `[` delimiters in code, a dotted key path over 120 segments, or a flattened path over
  360 (the enclosing objects counted) is refused. The surveyed corpus reaches depth 8 and a
  flattened path of 12 segments.
- Scala.js is not in this release; a `.json` and `.properties` file is refused under any platform,
  because formatting it would write HOCON under the name it has. The refusal is by the name the
  file is known by, so a symlink's target name decides (see above).

## The sbt plugin

```scala
// project/plugins.sbt
addSbtPlugin("eu.ww86" % "sbt-hocon-fmt" % "0.1.0")
```

| key | |
|---|---|
| `hoconFormat` | rewrite the files that are not formatted |
| `hoconFormatCheck` | fail if any file is not formatted |
| `hoconFormatSources` | the files; default `*.conf` and `*.hocon` in the Compile and Test resource directories |

```scala
hoconFormatSources := (baseDirectory.value / "conf" ** "*.conf").get
```

Neither task is wired into `test` or `compile`; add `hoconFormatCheck` to CI explicitly. The
plugin resolves `eu.ww86:hocon-fmt-java-api` and its transitive core in an isolated class loader,
apart from sbt's own Scala 2.12 library. This release was tried as a user would: in an sbt build
resolving only from a private repository, `hoconFormatCheck` failed naming the file,
`hoconFormat` rewrote it, and the second check passed.

## Not in this release

The sbt 2 flavour of the plugin (built and tested, published after this release); the Mill plugin
(wave 2: tested on Mill 1.1.4 and 1.1.10, excluded from the first publish selection); the Scala.js
and Scala Native artifacts of the core and its adapters and the zio adapter (wave 2); the native
binaries, the PyPI wheels and the npm package (wave 2). The pre-commit hooks are not a published
thing here: they are used from this git repository, running the native binary. They ship in wave
2, since they pin PyPI and npm versions published only then. Until then the command line on the
JVM is the only CLI channel.
