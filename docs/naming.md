# Naming

Every context the project's name appears in, the rule that governs it, what the repository says
today, and whether the two agree. A name is judged against its registry's or language's own rule,
not against taste, so each row carries its source. The `here` column is what `git grep` finds in the
sources, `build.sbt`, the manifests, `.pre-commit-hooks.yaml`, the workflows and `docs/`.

One mismatch becomes expensive at the first release: the JVM package. It is the first
[finding](#findings); the rest either match, or differ in ways that cost nothing to leave alone.

## Contexts

| context | convention and source | here | verdict |
|---|---|---|---|
| Maven group id (Central namespace) | "Each groupId should follow Java's package name rules. This means it starts with a reversed domain name you control" ([Maven naming guide](https://maven.apache.org/guides/mini/guide-naming-conventions.html)); Central verifies the domain by DNS TXT record and reverses it "exactly, even if the domain name contains hyphens or other characters that would result in an invalid Java package name" ([namespace](https://central.sonatype.org/register/namespace/), [requirements](https://central.sonatype.org/publish/requirements/)) | `eu.ww86`, proved with a TXT record on `ww86.eu` ([releasing](releasing.md)) | matches |
| Scala library artifact ids | sbt appends the binary version: "The underlying mechanism used to indicate which version of Scala a library was compiled against is to append `_<scala-binary-version>` to the library's name" ([Cross-Build](https://www.scala-sbt.org/1.x/docs/Cross-Build.html)); Scala.js publishes `foo_sjs1_2.13-0.1-SNAPSHOT.jar` ([cross-build](https://www.scala-js.org/doc/project/cross-build.html)); Scala Native's prefix is `"native" + binaryVersion` ([ScalaNativeCrossVersion](https://github.com/scala-native/scala-native/blob/main/sbt-scala-native/src/main/scala/scala/scalanative/sbtplugin/ScalaNativeCrossVersion.scala)) | `hocon-fmt-core_3`, `hocon-fmt-cats_3`, `hocon-fmt-zio_3`, `hocon-fmt-cli_3` on the JVM, `_sjs1_3` and `_native0.5_3` on the other platforms ([releasing](releasing.md)) | matches |
| Java-only library artifact id | "The artifactId is the name of the artifact. The identifiers should only consist of lowercase letters, digits, and hyphens" ([naming guide](https://maven.apache.org/guides/mini/guide-naming-conventions.html)) | `hocon-fmt-java-api`, `crossPaths := false`, no Scala library inside ([build.sbt](../build.sbt)) | matches |
| Maven plugin artifact id and goal prefix | "The conventional artifact ID formats to use are: `maven-${prefix}-plugin` […] for official plugins maintained by the Apache Maven team itself […] ; `${prefix}-maven-plugin` - for plugins from other sources"; the prefix is what `mvn <prefix>:goal` uses ([prefix mapping](https://maven.apache.org/guides/introduction/introduction-to-plugin-prefix-mapping.html)) | `hocon-fmt-maven-plugin`, goals `hocon-fmt:format` and `hocon-fmt:check` | matches |
| sbt plugin artifact id | "Use the `sbt-$projectname` scheme to name your library and artifact" ([Plugins Best Practices](https://www.scala-sbt.org/1.x/docs/Plugins-Best-Practices.html)); an sbt 1 plugin "append[s] the sbt-cross version `_2.12_1.0` to the module artifactId" ([IvySbt](https://www.scala-sbt.org/1.9.8/api/sbt/internal/librarymanagement/IvySbt.html)), an sbt 2 plugin carries `_sbt2_3` ([sbt 2 plugins](https://www.scala-sbt.org/2.x/docs/en/reference/plugin.html)) | `sbt-hocon-fmt` on Scala 2.12.21; [the sbt 2 plan](plans/sbt2-plugin.md) publishes `sbt-hocon-fmt_sbt2_3` separately | matches |
| Mill plugin artifact id | the published id is the module name plus `platformSuffix` plus the Scala binary version; Mill's own example sets `platformSuffix = s"_mill1"` and publishes `myplugin_mill1_3` ([writing plugins](https://mill-build.org/mill/extending/writing-plugins.html)) | `artifactName = "mill-hocon-fmt"` with `platformSuffix = "_mill1"`, resolved as `eu.ww86::mill-hocon-fmt` ([mill-plugin/build.mill](../mill-plugin/build.mill)) | matches |
| Version string | semantic versioning: "It should start with the major version, followed by the minor version and the patch version" ([naming guide](https://maven.apache.org/guides/mini/guide-naming-conventions.html)); a released version "cannot end in `-SNAPSHOT`" ([Central requirements](https://central.sonatype.org/publish/requirements/)) | `0.1.0-SNAPSHOT` while unreleased; the first release sets `0.1.0` in six places ([releasing](releasing.md)) | matches |
| JVM package | "You form a unique package name by first having (or belonging to an organization that has) an Internet domain name, such as oracle.com. You then reverse this name, component by component, to obtain, in this example, com.oracle, and use this as a prefix for your package names" ([JLS §6.1](https://docs.oracle.com/javase/specs/jls/se21/html/jls-6.html#jls-6.1); §7.7 in the older JLS, now [Module Declarations](https://docs.oracle.com/javase/specs/jls/se25/html/jls-7.html#jls-7.7)); "Scala packages should follow the Java package naming conventions" ([Scala style](https://docs.scala-lang.org/style/naming-conventions.html)) | `ww86.hocon_fmt` in all 120 Scala sources and the Java sources, with subpackages `.java`, `.sbt`, `.gradle`, `.maven`, `.mill`, `.interop.cats`, `.interop.zio`, `.web`, `.bench`, `.site` | **differs** — see [Findings](#findings) |
| Kotlin-facing package | "Names of packages are always lowercase and do not use underscores (`org.example.project`)" ([Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html#naming-rules)) | Kotlin callers import `ww86.hocon_fmt.java.HoconFmt`; the underscore is part of the API they type | differs, with the package row |
| Class, object and file names | "Classes should be named in upper camel case"; objects like classes ([Scala style](https://docs.scala-lang.org/style/naming-conventions.html)); "Class names should be nouns, in mixed case with the first letter of each internal word capitalized" ([Oracle](https://www.oracle.com/java/technologies/javase/codeconventions-namingconventions.html)) | `HoconFormatter`, `HoconFmt`, `HoconFormatterPlugin`, `HoconFormatterModule`, `HoconFormat` / `HoconFormatCheck`, `HoconText`, `CmdApi` | matches |
| Java API names | the same class rule; names are read by Java and Kotlin callers | `HoconFmt`, `Verdict`, `Inspection`, `RefusalKind`, `FormatOptions`, `DuplicateReport`, `AtomicFile`, under `@NullMarked` | matches |
| Gradle plugin id | "Plugin IDs are meant to be globally unique, similar to Java package names (i.e., a reverse domain name)"; "May contain any alphanumeric character, '.', and '-'", "Must contain at least one '.'", "Conventionally use only lowercase characters", no leading or trailing '.' and no '..' ([plugin ids](https://docs.gradle.org/current/userguide/implementing_gradle_plugins_binary.html#sec:creating_a_plugin_id)); the Portal wants the id to trace back to its author, `io.github.<user>.<name>` when the domain cannot be proved ([publish](https://plugins.gradle.org/docs/publish-plugin)) | `eu.ww86.hocon-fmt`, with `io.github.kastoestoramadus.hocon-fmt` as the documented fallback ([releasing](releasing.md)) | matches |
| Gradle task, extension and property names | Gradle's task pages state no naming rule (`custom_tasks`, `implementing_custom_tasks` and `more_about_tasks` mention none); Gradle's own tasks are camelCase (`compileJava`, `check`), and an option generated from a property keeps its words ([Custom Tasks](https://docs.gradle.org/current/userguide/custom_tasks.html)) | tasks `hoconFormat` / `hoconFormatCheck`, extension and configuration `hoconFormatter`, `hoconFormatterClasspath`, properties `separator`, `doubleIndent`, `simplifyNestedObjects`, `failOnDuplicates` | unclear — no rule to match |
| Maven plugin parameters and properties | the parameter name is the POM element; `@Parameter(property = "sayhi.greeting")` is "configuration of the mojo parameter from the command line by referencing a system property" ([plugin development](https://maven.apache.org/guides/plugin/guide-java-plugin-development.html)) | `includes`, `excludes`, `skip`, `separator`, `doubleIndent`, `simplifyNestedObjects`, `failOnDuplicates`, with properties `hocon-fmt.separator`, `hocon-fmt.double-indent`, `hocon-fmt.simplify-nested-objects`, `hocon-fmt.fail-on-duplicates`, `hocon-fmt.skip` | matches |
| sbt setting and task keys | "The convention for keys, like `console` and `fullClasspath`, is that the Scala identifier is camel case, while the String representation is lowercase and separated by dashes"; a new key takes "a plugin-specific prefix" ([Inspecting Settings](https://www.scala-sbt.org/1.x/docs/Inspecting-Settings.html), [Best Practices](https://www.scala-sbt.org/1.x/docs/Plugins-Best-Practices.html)) | `hoconFormat`, `hoconFormatCheck`, `hoconFormatSources`, `hoconSeparator`, `hoconDoubleIndent`, `hoconSimplifyNestedObjects`, `hoconFailOnDuplicates` | matches |
| Mill commands and arguments | a Mill command is its Scala member; its arguments are named after the parameters, `--<name>` on the command line ([Tasks](https://mill-build.org/mill/fundamentals/tasks.html)) | `hoconFormat` / `hoconFormatCheck`, arguments `separator`, `double-indent`, `simplify-nested-objects`, `fail-on-duplicates` | matches |
| npm package | "The name must be less than or equal to 214 characters", "New packages must not have uppercase letters in the name", "the name can't contain any non-URL-safe characters", no leading `.` or `_`, no spaces ([package.json](https://docs.npmjs.com/cli/v10/configuring-npm/package-json#name), [validate-npm-package-name](https://github.com/npm/validate-npm-package-name)) | `hocon-fmt`, with `bin: {"hocon-fmt": "hocon-fmt.js"}`; the repository's own `hocon-fmt-pre-commit-hooks` is private and never published | matches |
| PyPI project and import module | names are normalised: "lowercased with all runs of the characters `.`, `-`, or `_` replaced with a single `-`" ([PEP 503](https://peps.python.org/pep-0503/#normalized-names)); an installed command should be usable as a shell command ([entry points](https://packaging.python.org/en/latest/specifications/entry-points/)); import packages are lowercase, underscores discouraged ([PEP 8](https://peps.python.org/pep-0008/#package-and-module-names)) | project `hocon-fmt`; no import module at all, the wheel carries the binary in `.data/scripts` and installs the command `hocon-fmt`; `hocon-fmt-pre-commit-hooks` is the local hook package, never uploaded | matches |
| Wheel file name | `{distribution}-{version}(-{build tag})?-{python tag}-{abi tag}-{platform tag}.whl`, each component escaped by replacing runs of non-alphanumerics with `_`, scripts under `{distribution}-{version}.data/scripts/` ([PEP 427](https://peps.python.org/pep-0427/#file-name-convention)) | `hocon_fmt-0.1.0.dev0-py3-none-manylinux_2_34_x86_64.whl` ([python/build_wheel.py](../python/build_wheel.py)) | matches; the underscore is required, not chosen |
| pre-commit hook ids | `id` is "the id of the hook - used in pre-commit-config.yaml"; no character rule is documented ([Creating new hooks](https://pre-commit.com/#creating-new-hooks)) | `hocon-fmt`, `hocon-fmt-check`, `hocon-fmt-node`, `hocon-fmt-check-node` | unclear — no rule to match; the four repeat the command name |
| CLI command | "Utility names should be between two and nine characters, inclusive" and "should include lowercase letters […] and digits only" ([POSIX Guideline 1 and 2](https://pubs.opengroup.org/onlinepubs/9699919799/basedefs/V1_chap12.html)); multi-word tools conventionally join the words with a hyphen | `hocon-fmt`, the same word as the npm `bin`, the wheel's script, the native binary and the JS bundle | differs from Guideline 2 on purpose; see [Findings](#findings) |
| CLI flags | "Long options consist of `--` followed by a name made of alphanumeric characters and dashes"; "Option names are typically one to three words long, with hyphens to separate words" ([GNU libc](https://sourceware.org/glibc/manual/latest/html_node/Argument-Syntax.html)); short options are a single alphanumeric ([POSIX Guideline 3](https://pubs.opengroup.org/onlinepubs/9699919799/basedefs/V1_chap12.html)) | `--check` with short `-c`, `--stdin`, `--stdin-filename`, `--version`, `--config`, `--separator`, and the pairs `--double-indent` / `--no-double-indent`, `--simplify-nested-objects` / `--no-…`, `--fail-on-duplicates` / `--no-…` | matches |
| Config file and keys | no external rule; the file is named after the tool and its keys repeat the flags | `.hocon-fmt.conf` with `separator`, `double-indent`, `simplify-nested-objects`, `fail-on-duplicates`; the same four words are the CLI flags, the Maven properties' suffixes, the Mill argument names and, in camel case, the sbt and Gradle keys | matches |
| Environment variables | a variable or secret "Can only contain alphanumeric characters (`[a-z]`, `[A-Z]`, `[0-9]`) or underscores (`_`)", "Must not start with a number", "Must not start with the `GITHUB_` prefix" ([Actions variables](https://docs.github.com/en/actions/reference/workflows-and-actions/variables)) | workflows: `RELEASE_WAVE`, `SONATYPE_USERNAME`, `SONATYPE_PASSWORD`, `PGP_SECRET`, `PGP_PASSPHRASE`; code: `HOCON_FMT_DEPLOY_SHA`, `HOCON_FMT_DEPLOY_TIME`, `HOCON_FMT_FORK_CACHE`; tests: `UPDATE_GOLDEN`, `MAVEN_REPO_LOCAL`, `PLUGIN_UNDER_TEST`, `MILL_EXECUTABLE_PATH`, `MILL_TEST_RESOURCE_DIR`, `COURSIER_REPOSITORIES` | matches |
| git tags | "We recommend creating releases using semantically versioned tags – for example, `v1.1.3`" ([releasing actions](https://docs.github.com/en/actions/how-tos/create-and-publish-actions/release-and-maintain-actions)) | `v<version>`, `v0.1.0`; the release workflow triggers on `tags: ['v*']`; no tag exists yet | matches |
| Repository variables | the same Actions rule as any variable | `RELEASE_WAVE`, which picks the wave ([releasing](releasing.md)) | matches |
| Branch names | git's ref rules are the only hard ones: no spaces, `..`, `~`, `^`, `:`, `?`, `*`, `[`, `\`, no trailing `.lock` ([git-check-ref-format](https://git-scm.com/docs/git-check-ref-format)); the everyday shape is `<type>/<kebab-case-slug>` | `task/`, `docs/`, `feat/`, `fix/`, `ci/`, `chore/`, `build/`, `packaging/`, `defect/`, `examples/`, and `claude/<slug>` for agent work | matches common practice; no standard to differ from |
| Improvement-log file names | the repository's own rule: `<date>-<pr number>-<slug>.md`, a commit sha where an entry predates pull requests ([README](improvement-log/README.md)) | 82 files in that shape, e.g. `2026-10-09-85-naming-conventions.md`, three with a sha | matches |
| GitHub repository, workflows and Action | a repository name "can only contain ASCII letters, digits, and the characters `.`, `-`, and `_`", at most 100 characters ([creating a repository](https://docs.github.com/en/repositories/creating-and-managing-repositories/creating-a-new-repository)); a workflow is a `.yml` under `.github/workflows` with a `name` ([workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#name)) | `kastoestoramadus/hocon-fmt`; `ci.yml`, `pages.yml`, `release.yml`, named `CI`, `Pages`, `Release`; the planned Action is `kastoestoramadus/hocon-fmt-action@v1` ([ideas](ideas.md)) | matches |
| Docker image (wave 2, planned) | repository names are lowercase, each component `[a-z0-9]+(?:(?:[._]\|__\|[-]+)[a-z0-9]+)*` ([distribution/reference](https://github.com/distribution/reference)) | `ghcr.io/kastoestoramadus/hocon-fmt` ([ideas](ideas.md)) | matches |
| Homebrew (wave 2, planned) | a tap repository is `homebrew-<repository>` for the one-argument `brew tap` ([Taps](https://docs.brew.sh/Taps)); "Name the formula like the project markets the product", "Filenames should be all lowercase" ([Formula Cookbook](https://docs.brew.sh/Formula-Cookbook)) | `brew install kastoestoramadus/tap/hocon-fmt`, `Formula/hocon-fmt.rb` in `kastoestoramadus/homebrew-tap` ([ideas](ideas.md)) | matches |
| coursier app (wave 2, planned) | the installed name is the descriptor's file name unless its `name` field overrides it ([app descriptors](https://get-coursier.io/docs/cli-appdescriptors)) | `cs install hocon-fmt` ([ideas](ideas.md)) | matches |
| Scala.js export and bundle | an export emitted as a script must be "a valid JavaScript identifier" ([export to JavaScript](https://www.scala-js.org/doc/interoperability/export-to-javascript.html)) | `@JSExportTopLevel("HoconFormatter")`; the bundle is written as `hocon-fmt.js` ([build.sbt](../build.sbt)) | matches |
| Scala Native binary | no naming rule is documented; `nativeLink` produces "native binary" ([Scala Native sbt](https://scala-native.org/en/stable/user/sbt.html)) | `nativeConfig.withBaseName("hocon-fmt")` ([build.sbt](../build.sbt)) | unclear — no rule to match |

## Findings

Ranked by what a change costs now against what it costs once the artifacts are published.

1. **The package root does not reverse the domain the project owns, and the segment is not the one
   the owner chose.** Everything under `ww86.hocon_fmt` — 120 Scala sources, the Java API, the
   plugins' implementation classes, the CLI's `Main-Class`, the sbt plugin's `buildInfoPackage` —
   should be `eu.ww86.hoconfmt`. The JLS convention is a name formed from a domain the
   organization holds: `ww86.eu` reverses to `eu.ww86`, which is also the group id verified at
   Central, while `ww86` alone reverses nothing and is not a top-level domain. And the owner chose
   `hoconfmt` as the project segment, which is the form Kotlin's conventions want; `hocon_fmt` is
   legal Java but an underscore in the Kotlin-facing API a Kotlin caller types
   ([Gradle's Portal guide](https://plugins.gradle.org/docs/publish-plugin) is the one source that
   argues for the underscore — "while the plugin ID and group ID should use dashes, the package name
   should contain underscores instead" — but it argues about plugin ids, not about a group id that is
   already dot-separated). **Before the first release this is a mechanical rename the compiler
   checks** — the imports, the plugin descriptors, `buildInfoPackage`, the CLI's `Main-Class`, the
   site's snippets and the docs, with no consumer to break. **After it, it breaks every Java, Kotlin
   and Scala caller's imports and every plugin class name** — a major version, and a
   [limitations](limitations.md)-style note rather than a fix. This is the one row worth acting on
   before the tag.

2. **The command name against POSIX Guideline 2 differs, and should stay as it is.** `hocon-fmt`
   uses a hyphen, which Guideline 2's "lowercase letters and digits only" excludes, and it is at the
   top of Guideline 1's 2-to-9 characters. Every channel already spells it that way — the npm `bin`,
   the wheel's installed script, the native binary, the JS bundle, the sbt, Gradle, Maven and Mill
   tasks, the pre-commit ids and the repository — and multi-word command names with hyphens are
   ordinary. Renaming it would break more than the guideline is worth.

3. **`UPDATE_GOLDEN` has no project prefix.** The environment variables the code owns are
   `HOCON_FMT_*`; the golden-file switch is named for what it does and collides with nothing here,
   but it is the one variable a reader could take for someone else's. It is test-only and read in
   one file ([GoldenFileSpec](../core/jvm-native/src/test/scala/ww86/hocon_fmt/GoldenFileSpec.scala)).

4. **Three contexts have no rule to be measured against, and a fourth has only a loose one.** Gradle
   states no convention for task names, pre-commit none for hook ids, Scala Native none for the
   binary's name; git fixes only the characters a branch name may not contain. Each is used the way
   the surrounding ecosystem uses it, which is all that can be checked; a convention appearing later
   would have to be re-checked here.

5. **The wave-2 names are unbuilt.** The Docker image, the Homebrew tap, the coursier app, the
   GitHub Action and the sbt 2 artifact are documented names, not published ones; each matches its
   registry's rule on paper, and each should be re-checked here when it is built. The Gradle Plugin
   Portal id in particular has a fallback (`io.github.kastoestoramadus.hocon-fmt`) that changes the
   implementation-derived artifact coordinates if `ww86.eu` cannot be verified there.

## Checked by running

Claims a page alone cannot settle, all on 2026-10-09; a hyphen cannot be a package segment, the
underscore can, PyPI folds the two spellings into one project, npm accepts the name, and the wheel
name is what PEP 427 says it must be.

```
$ javac -d out pkg/Hyphen.java        # package eu.ww86.hocon-fmt;
pkg/Hyphen.java:1: error: ';' expected
package eu.ww86.hocon-fmt;
                     ^
1 error

$ javac -d out pkg/Under.java         # package ww86.hocon_fmt;
$ echo $?
0
```

Scala 3 takes a backticked package instead and warns what that costs, and the class file it writes
is one no Java source can name (`javap` output trimmed to its first lines):

```
$ scalac -d out EuPkg.scala           # package `eu.ww86.hocon-fmt`
-- Warning: EuPkg.scala:1:8 ----------------------------------------------------
1 |package `eu.ww86.hocon-fmt`
  |        ^^^^^^^^^^^^^^^^^^^
  |The package name `eu.ww86.hocon-fmt` will be encoded on the classpath, and can lead to undefined behaviour.
1 warning found

$ find out -name '*.class'
out/eu$u002Eww86$u002Ehocon$minusfmt/EuPkg.class
$ javap -p 'out/eu$u002Eww86$u002Ehocon$minusfmt/EuPkg.class'
Compiled from "EuPkg.scala"
public class eu$u002Eww86$u002Ehocon$minusfmt.EuPkg {
  public eu$u002Eww86$u002Ehocon$minusfmt.EuPkg();
}
```

```
$ python3 -c "import re; print(re.sub(r'[-_.]+','-','hocon_fmt').lower())"
hocon-fmt
$ python3 -c "import re; print(re.sub(r'[-_.]+','-','Hocon.FMT').lower())"
hocon-fmt
$ curl -s -o /dev/null -w '%{http_code} => %{url_effective}\n' -L https://pypi.org/simple/scikit_learn/
200 => https://pypi.org/simple/scikit-learn/
```

`hocon-fmt` and `hocon_fmt` are one PyPI project; whoever publishes one spelling owns both. The
name is unclaimed: both spellings answer 404 today, as do `hocon-fmt` on the npm registry and both
package names of this repository.

```
$ for u in https://pypi.org/simple/hocon_fmt/ https://registry.npmjs.org/hocon-fmt; do
    curl -s -o /dev/null -w '%{http_code}  %{url_effective}\n' -L "$u"; done
404  https://pypi.org/simple/hocon_fmt/
404  https://registry.npmjs.org/hocon-fmt

$ npm install --no-save validate-npm-package-name@8.0.0 && node -e "const v=require('validate-npm-package-name'); for (const n of ['hocon-fmt','Hocon-FMT','.hocon-fmt']) console.log(n+': '+JSON.stringify(v(n)))"
hocon-fmt: {"validForNewPackages":true,"validForOldPackages":true}
Hocon-FMT: {"validForNewPackages":false,"validForOldPackages":true,"warnings":["name can no longer contain capital letters"]}
.hocon-fmt: {"validForNewPackages":false,"validForOldPackages":false,"errors":["name cannot start with a period"]}
```

```
$ python3 python/build_wheel.py --binary fake-bin --platform-tag manylinux_2_34_x86_64 --out /tmp/dist
/tmp/dist/hocon_fmt-0.1.0.dev0-py3-none-manylinux_2_34_x86_64.whl
$ python3 -c "import glob,zipfile; print('\n'.join(zipfile.ZipFile(glob.glob('/tmp/dist/*.whl')[0]).namelist()))"
hocon_fmt-0.1.0.dev0.data/scripts/hocon-fmt
hocon_fmt-0.1.0.dev0.dist-info/METADATA
hocon_fmt-0.1.0.dev0.dist-info/WHEEL
hocon_fmt-0.1.0.dev0.dist-info/RECORD
```

The version in the name is the wheel's own rule, not the project's: `0.1.0-SNAPSHOT` becomes
`0.1.0.dev0`, which is what `build_wheel.py` does so that a snapshot sorts below its release.
