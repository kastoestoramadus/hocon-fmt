# Tests

Suites are split by concern, so a change answers to one place. A suite's directory follows from
what it touches: `shared` runs on every platform, `jvm-native` reads files, `jvm` is JVM-only.

| suite | where | answers for |
|---|---|---|
| `GoldenFileSpec` | `core/jvm-native` | rendering: each `<name>.conf` in `core/jvm-native/src/test/resources` against `<name>.expected.conf` |
| `HoconFormatterInvariantsSpec` | `core/jvm-native` | idempotence, output re-parses, meaning preserved, inputs shaped like the internal placeholders |
| `FormatterPropertiesSpec` | `core/shared` | the same guarantees on generated documents: no comment, include or meaning lost, no placeholder leaked, nothing refused without reason |
| `IncludeDetectionSpec` | `core/shared` | which occurrences of `include` are a directive; the contract of the detection regex |
| `IncludeOrderSpec` | `core/shared` | an include keeps the fields defined before it: what is refused when formatting would move one across it, what still formats |
| `HoconSpecCoverageSpec` | `core/shared` | the HOCON specification: what is refused (and why), normalised, supported |
| `DuplicateReportSpec` | `core/shared` | the duplicate report: replaced definitions and ancestor barriers, independent array scopes, and resolution showing which values survive |
| `ExamplesSpec` | `core/shared` | every directory example: today’s verdict and exact expected output, on JVM, Scala.js and Native; prints the roadmap |
| `VerdictSpec` | `core/shared` | the per-file decision every integration acts on, including strict UTF-8, the origin a parse failure names and the formats a file's name rules out |
| `OptionsSpec` | `core/shared` | the parse and render options the formatter pins explicitly: the final newline, empty text, and how env-variable values render |
| `SconfigDefectsSpec` | `core/shared` | sconfig's own bugs, with none of our code involved; red by design |
| `HoconFormatterJsSpec` | `web` | the JavaScript API a page calls, through its global, on the Closure-compiled script |
| site suites | `site` | the page's pure logic on Scala.js/Node: the status model, the snapshot/live merge, the grouping, the fetch path against a fake; see [site](site.md#testing) |
| `FileFormatterSpec` | `cats` | file verdicts, no-write checks and refusals, replacement that keeps the file's owner, group and mode bits, the write in place when it cannot, symlinks and canonical paths on JVM, Node and Native |
| `FileFormatterFailureSpec` | `cats/.jvm` | a failure or cancellation after a partial staged write preserves the original bytes and cleans up staging |
| `ZioFormatterSpec`, `ZioFilesSpec`, adapter `FormatterPropertiesSpec` | `zio/shared`, `zio/jvm-native` | typed text refusals on all runtimes; file decisions, untouched refusals, atomic replacement cleanup, replacement that keeps the file's owner, group and mode bits, the write in place when it cannot, symlinks and permissions, plus 1000 arbitrary byte sequences on JVM / Native |
| `ZioFilesJvmSpec` | `zio/jvm` | a path on a closed ZIP filesystem, whose unchecked `ClosedFileSystemException` must stay that file's outcome; Scala Native serves no jar provider, so the mechanism is JVM-only |
| `CmdApiSpec` | `cli` | the CLI on real temp files, on JVM, Node and Native: exit codes, every file examined once, unformattable and non-UTF-8 files never written, arguments |
| scripted | `sbt-plugin/src/sbt-test` | the sbt plugin in a real sbt build, including the java-api worker classpath, reflective verdict mapping and untouched byte-level refusals |
| functional | `gradle-plugin/src/functionalTest` | the Gradle plugin through TestKit, including configuration cache and up-to-date checks |
| invoker | `maven-plugin/src/it` | the Maven plugin in real Maven builds |
| unit, integration | `mill-plugin/test`, `mill-plugin/integration` | the Mill plugin in process through `UnitTester`, and in a real Mill: 1.1.4, the oldest supported, and 1.1.10 |
| `HoconFmtTest`, `KotlinInteropTest` | `java-api/src/test` | the Java API for Java and Kotlin callers: the mirrored verdicts and refusal kinds and the parity tests that pin the mirror to the core, file checks and the write only on `NeedsFormatting`, `formatOrThrow`, and what Kotlin sees — a value-used `when` with no `else` and JSpecify's non-null returns |
| `IsolatedLoaderContractTest` | `java-api/src/test` | the sbt-published API in an isolated loader: byte entry points, verdict record accessors, enum names, named refusals and other-format refusal |
| e2e | `scripts/pre-commit-e2e.sh` | both families of pre-commit hooks, native and Node, installed from this repository as a user would |
| `CliAcceptanceSuite` | `acceptance` | the CLI as a process on JVM, Node and Native: `--stdin` bytes under `LC_ALL=C` and UTF-8, redirected files and pipes, input beyond one read, refusals, `--version`, argument errors; `sbt acceptance/test` |
| scoverage report | `coverageJvm`: core, cats, cli, zio on the JVM | which statements and branches the JVM tests reach, per module; CI's `coverage` job publishes the HTML and a per-module summary, and nothing fails on it |

## Property tests

`HoconGen` generates documents as a tree that knows its own comments and includes, then writes
them the ways people write HOCON: `:`, `=` or nothing before `{`, `#` and `//` comments holding
quotes and the word include, quoted and dotted keys. Knowing what went in lets a property check
what came out without parsing it with the code under test. Each property runs 1000 documents on
every platform; `sbt -Dhocon.properties=20000 "coreJVM/testOnly ww86.hocon_fmt.FormatterPropertiesSpec"`
searches deeper. A failure prints the document and the seed that reproduces it.

They found what no example covered: a quote inside a comment hid the next include, which on the
JVM then vanished; a comment with no field after it was dropped; an include in an object that a
later definition replaces vanished with it, after 11356 documents; and, in sconfig, a one-field
object inside an array loses its braces when it does not fit on one line, which one CI run turned
up after 859 documents.

## Benchmarks

`bench` times each phase (mask, parse, render, unmask, the whole format) on five generated
scenarios on the JVM, Scala.js and Scala Native. `scripts/bench.py run` adds the CLI's start-up per
build and attaches the results to HEAD in `refs/notes/benchmarks`, keeping three runs per commit
and thirty commits; `scripts/bench.py report` prints the medians per commit and flags a slowdown
over 15% and 0.05 ms between runs on the same machine. CI records main's history and reports a pull
request against it, without failing: shared runners are too noisy to gate on.

Notes are shared with `git push origin refs/notes/benchmarks`, and survive rebase and amend with
`git config notes.rewriteRef refs/notes/benchmarks`.

## Conventions

- **Golden files.** Adding a fixture is a two-file drop. Regenerate with
  `UPDATE_GOLDEN=1 sbt "coreJVM/testOnly ww86.hocon_fmt.GoldenFileSpec"` and read the diff before
  committing: a golden file is worth what the human who approved it looked at.
- **Meaning preservation** is asserted on include-free inputs only: an include of a missing file
  cannot be parsed on its own. Files with includes are covered by idempotence.
- **`library:` and `formatter:`** prefixes in `SconfigDefectsSpec` and `HoconSpecCoverageSpec`
  record who is responsible for a failure, so nobody fixes an upstream bug here.
- **`SconfigDefectsSpec` is excluded from `sbt test`**, because a permanently red CI teaches people
  to ignore it. `sbt libraryDefects` runs it on all three platforms, since sconfig's Scala.js and
  Native builds have defects of their own.
<!-- UPSTREAM-SCONFIG: delete this bullet with the fork and `coreSite`; docs/site.md has the steps. -->
- **`coreSite` runs core's shared suite** with `Variant.ledger`: the tests that pin a refusal the
  fork does not produce (comments it keeps, merges its base renders) are listed in
  `core/site-shared/src/test` with a reason each, and `CommentCarrierSpec` pins the outcome there
  instead. `KeepDetachedCommentsGuardSpec` (JVM, `sbt libraryDefects`) is red until released
  sconfig has the option: the signal to return to upstream.
- **Order.** `sbt test` runs core, cats, cli and the ZIO adapter on the JVM, Scala.js and Scala Native, one project at a
  time under a `==========` banner. Aggregated projects would run concurrently and print unlabelled,
  interleaved summaries. The cost: the run stops at the first failing project.
- **Coverage.** `sbt coverageJvm` measures what the JVM tests reach, statement and branch, per
  module; the HTML lands under `*/target/scala-*/scoverage-report` and CI's `coverage` job
  publishes it as an artifact with a per-module table in the job summary. It runs separately
  from `sbt test`, which stays uninstrumented, and ends with `coverageOff`; publish, publishLocal,
  publishM2 and publishSigned refuse to run while coverage is on, so a jar carrying the coverage
  runtime's calls cannot ship even when the run fails midway. Scala.js and Scala Native cannot be
  measured: dotty's coverage runtime needs `java.util.UUID` over `java.security.SecureRandom`,
  which neither javalib carries, so the instrumented code stops at link time. No number fails
  anything — coverage says what ran, not what the assertions check; mutation testing is the
  follow-up in [ideas](ideas.md).

## Shared examples

`examples/showcase/NN-slug/` holds the playground examples in directory order.
`examples/catalogue/slug/` holds examples for tests only. Both contain `input.conf`,
`example.conf`, and, for successful examples, `expected/default.conf`. Refused showcase
examples need no expected file: their verdict is pinned. Refused catalogue examples carry
`expected/refused.txt` containing the `refused:<kind>` verdict. Input and expected
output are compared exactly, including the final newline. Only the `default` option set
exists today.

The sbt source generator reads `example.conf` with sconfig and embeds both directories
in core test and site Scala data; tests and the site use it without runtime file I/O. Metadata carries
`title`, `story` (two sentences using domain terms), `shows` (the button tooltip),
`target` (the human-authored ideal verdict), `now` (today’s verdict), and `options: [default]`.
Verdicts are `formatted`, `already-formatted` (only for now or pending), or
`refused:<kind>`, with kinds `not-utf8`, `not-hocon`, `other-format`, `broken-output`,
`lost-comment`, `lost-include`, `moved-include`, and `unstable-output`. The generator embeds the
kinds it
accepts as `ExampleData.kinds`, and `ExamplesSpec` maps every `Refusal` case through an
exhaustive `kindOf` and requires the result to be exactly that set, so the next `Refusal`
case cannot drift. When `now != target`, `reason-if-different` is
required. `ExamplesSpec` asserts `now` and output and prints gaps as
`now / pending / target`; absent pending is shown as “—”. Optional `pending` records
a verdict from a local sconfig build with the author’s open PRs merged; this build
does not compute it and the suite does not assert it. `findings: [...]` carries the report's kinds for
the example's `input.conf`, which `ExamplesSpec` asserts — an example that declares none has none.

`source` records `kind: synthetic | distilled | verbatim` and `pattern`; verbatim
examples also require `url` and `licence`. A distilled entry may carry
`source.seen-in`, the number of corpus files containing the pattern; the generator
ignores it. Add fixtures only after reviewing their
inputs, metadata, and expected output; the generator never derives the target.

The two showcase fork differences declare `now-site: formatted` and carry
`expected/site-default.conf`. The published suites still assert `now`; the fork's
`Variant.ledger` routes these inputs to `CommentCarrierSpec`, which asserts `now-site`
and the exact fork output. `target` is always authored independently. Verbatim
provenance may also carry `source.sha`, embedded alongside the URL and licence.
