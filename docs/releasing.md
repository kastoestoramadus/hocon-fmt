# Releasing

The owner's 2026-10-13 decision is that **v0.1.0 covers the JVM core and the sbt 1.x, Gradle
and Maven plugins**. Native binaries, npm, PyPI, Mill and sbt 2 plugins follow later. Channels
share one core and version; they do not all ship in the first release.

v0.1.0 ships with the current refusal list: files the formatter cannot prove safe are refused.
The list has not been reviewed by hand yet; the second release tightens it. Refusal never
writes or breaks a file. The dated JVM measurement and corpus caveat are in
[limitations](limitations.md#refusals-in-v010).

The workflow implements this scope: wave 1 builds and signs the sbt artifacts and both
standalone plugins, stages the sbt and Maven deployments in Central Portal, and publishes the
Gradle plugin when its Portal credentials are configured. A missing Plugin Portal key or
secret in wave 1 skips that upload with a warning annotation and a job-summary entry before
Central uploads; it does not skip building or signing the plugin. On a real wave-2 tag, either
missing credential fails the job before any registry upload. Dry runs need neither credential.
Nothing has been released yet. Central setup is recorded below; Plugin Portal account and
namespace setup remain owner tasks. The `Release` workflow has not been exercised with real
secrets.

The `Release` workflow reads one switch, the repository variable `RELEASE_WAVE` (Settings →
Secrets and variables → Actions → Variables): unset or `1` ships wave 1, `2` enables the later jobs.
The wave-2 jobs stay in the workflow, skipped; a wave-2 release sets the variable, makes its
release, and can leave it set afterwards.

**Currently automated in wave 1**: `hocon-fmt-core_3`, `hocon-fmt-cats_3` (the CLI's POM names it),
`hocon-fmt-cli_3`, `hocon-fmt-java-api`, `sbt-hocon-fmt`, `hocon-fmt-maven-plugin` and the Gradle
plugin `eu.ww86.hocon-fmt` (implementation plus plugin marker). `sbt publishRelease` uploads the
five sbt artifacts; Maven's release profile uploads its own signed Central bundle, and Gradle's
`publishPlugins` uploads to Plugin Portal. `sbt test` fails if the sbt set stops being closed
under the POM dependencies and the Java API required at run time by all three build-tool plugins.
The Portal upload remains conditional on the owner supplying its credentials.

| artifact | wave | built by | published to | users get it through |
|---|---|---|---|---|
| `hocon-fmt-core_3`, `hocon-fmt-core_sjs1_3`, `hocon-fmt-core_native0.5_3` | 1 (JVM), 2 (JS, Native) | sbt | Maven Central | the pure formatter on every platform |
| `hocon-fmt-cats_3`, `hocon-fmt-cats_sjs1_3`, `hocon-fmt-cats_native0.5_3` | 1 (JVM), 2 (JS, Native) | sbt | Maven Central | cats-effect file operations on every platform |
| `hocon-fmt-zio_3`, `hocon-fmt-zio_sjs1_3`, `hocon-fmt-zio_native0.5_3` | 2 | sbt | Maven Central | the ZIO library adapter |
| `hocon-fmt-cli_3` | 1 | sbt | Maven Central | the JVM command line |
| `hocon-fmt-java-api` | 1 | sbt | Maven Central | a library dependency for Java and Kotlin callers |
| `sbt-hocon-fmt` | 1 (sbt 1.x); sbt 2 later | sbt | Maven Central | `addSbtPlugin` |
| `hocon-fmt-maven-plugin` | 1 | `maven-plugin/` | Maven Central | `<plugin>` |
| `mill-hocon-fmt_mill1_3` | 2 (on hold) | `mill-plugin/` | Maven Central | `//| mvnDeps` |
| `eu.ww86.hocon-fmt` | 1 | `gradle-plugin/` | Gradle Plugin Portal | `plugins { id(...) }` |
| native binaries, `hocon-fmt.js` | 2 | `Release` workflow | GitHub release | a download; the playground |
| wheels carrying the native binary | 2 | `Release` workflow | PyPI | the pre-commit hooks, `pipx install` |
| npm package carrying the Node build | 2 | `Release` workflow | npm | the `-node` pre-commit hooks, `npx` |

Mill plugin on hold: Mill 1.1.x runs on Scala 3.8, which cannot read this build's Scala 3.9 output (TASTy 28.9 against 28.8); it returns when Mill moves to 3.9. Its upload step in `release.yml` is disabled.

The names are free on every registry (checked 2026-09-26).

## One-time setup

Accounts and secrets only the owner can create. Wave-1 publishing code is in place; later
registries still need the code listed below.

### Maven Central

Done; recorded here so it can be redone. Artifacts are published under the group `eu.ww86`.

1. The namespace `eu.ww86` is verified at [central.sonatype.com](https://central.sonatype.com)
   through a TXT record on `ww86.eu` (Publishing → Namespaces). The record has served its purpose
   and may be removed. A group id cannot change after the first release.
2. The portal's user token (account menu → Generate User Token), not the account's own password,
   is what publishing uses.
3. The signing key is RSA 4096, key id `C273E77F6DDCBD56`, valid until 2028-10-06 and published on
   `keyserver.ubuntu.com`. Its public half must stay there, since Central checks signatures against
   it. To extend it, run `gpg --edit-key C273E77F6DDCBD56 expire`, send the key to the keyserver
   again and replace `PGP_SECRET`:
   ```bash
   gpg --keyserver keyserver.ubuntu.com --send-keys C273E77F6DDCBD56
   gpg --armor --export-secret-keys C273E77F6DDCBD56 | base64 -w0   # the value of PGP_SECRET
   ```
4. The repository secrets are `SONATYPE_USERNAME`, `SONATYPE_PASSWORD`, `PGP_SECRET` and
   `PGP_PASSPHRASE`.

The `central` job in `release.yml` imports the key and signs all wave-1 artifacts before any
upload. sbt's Central Portal support (`publishRelease`, which signs and calls `sonaUpload`) and
the Maven `release` profile (`central-publishing-maven-plugin` 0.11.0, `autoPublish=false`,
`waitUntil=validated`) stage two deployments. The Maven extension assembles the signed artifacts,
POM, sources, javadocs and checksums into a ZIP with Maven repository layout; `deploy` uploads
that bundle using the `central` server token, rather than deploying to a Maven repository URL.
See [Sonatype's Maven publishing guide](https://central.sonatype.org/publish/publish-portal-maven/).
Both deployments await a Publish click under Publish → Deployments. The core and Java API are
also published to a private local Maven repository so plugin builds work before Central has
the first version. Mill's upload stays disabled even in wave 2 until its Scala version catches up.

### Gradle Plugin Portal

1. Create a [Plugin Portal account](https://plugins.gradle.org) linked to the owner's GitHub
   account. Keep the plugin id `eu.ww86.hocon-fmt` and group `eu.ww86`: for this domain namespace,
   the Portal requires proof of ownership of `ww86.eu` using a DNS TXT record. Follow the record
   instructions supplied by Portal staff; Central's namespace verification is separate. The
   initial plugin submission also receives manual review. If the Portal rejects the id, stop
   and ask the owner to decide; changing the id or group is not automatic.
2. Generate a Portal API key and secret in the account profile and add the GitHub Actions
   secrets `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET`, preferably in the `release`
   environment. These are the environment variable names supported by Gradle's publishing plugin.
3. Run the workflow's default dry run with the existing signing secrets before tagging. Portal
   credentials are optional for that run; when either is missing, the upload step prints why
   it skipped. The dry run builds and signs the implementation JAR, sources, javadocs and POM,
   Gradle metadata and plugin marker POM into private Maven Local, without contacting the Portal.

The build applies `com.gradle.plugin-publish` 2.2.1, declares website, VCS URL, tags and tested
configuration-cache support, and uses the imported GnuPG key when `-PreleaseSigning` is present.
A non-dry version tag invokes `publishPlugins` only when both Portal secrets are present. Portal
upload is a publication submission, with initial review; it has no Central-style Publish click.
See [Gradle's publishing and namespace requirements](https://plugins.gradle.org/docs/publish-plugin).

### PyPI: the pre-commit hooks' binaries

1. Create an account with two-factor authentication.
2. Add a *pending* trusted publisher at
   [pypi.org/manage/account/publishing](https://pypi.org/manage/account/publishing/): project
   `hocon-fmt`, owner `kastoestoramadus`, repository `hocon-fmt`, workflow
   `release.yml`, environment `pypi`. It reserves the name and needs no token; the first upload
   turns it into the project's publisher.
3. Create the environment `pypi` (Settings → Environments). Requiring yourself as a reviewer
   there makes every upload wait for a click.

Code still needed: a job in `release.yml` with `environment: pypi`, `permissions: id-token: write`
and `pypa/gh-action-pypi-publish`, uploading the wheels the `native` jobs build.

### npm: the Node hooks

1. Create an account with two-factor authentication, and `npm login`.
2. Publish the first version by hand, from the tarball the release attaches:
   `npm publish --access public hocon-fmt-<version>.tgz`. npm lets you configure a trusted
   publisher only for a package that already exists.
3. On npmjs.com, in the package's settings, add a trusted publisher: repository
   `kastoestoramadus/hocon-fmt`, workflow `release.yml`.

Code still needed: a job publishing later versions from `release.yml`, with
`permissions: id-token: write` and npm 11.5.1 or later.

### GitHub

Nothing to create. `release.yml` runs by hand only once it is on `main`: GitHub offers "Run
workflow" for workflows on the default branch.

### Safeguards

A Central publish cannot be undone, so three steps stand between a push and a published release,
and none of them is complete on its own. A repository ruleset, `Protect release tags`, blocks
creating, moving or deleting `v*` tags for everyone but admins, so a contributor with write access
cannot push the tag. The `central` job runs in the repository environment `release`, which requires
the owner as reviewer, so a tag push or a manual run of that job waits for an explicit approval
(Settings → Environments → release). Last, the upload only stages a deployment: nothing is public
until the Publish click in the portal (sbt's `sonaUpload` is `USER_MANAGED`).
A manual run defaults to `dry_run: true`, signs
and uploads nothing, but it reads the signing secrets, so it needs the approval too. Selecting
a branch never uploads; selecting a version tag only uploads if `dry_run` is explicitly disabled.
The Gradle upload shares this environment gate but submits directly to Plugin Portal;
it does not wait for a Central Publish click.

What this does not cover yet: the four secrets (`PGP_SECRET`, `PGP_PASSPHRASE`,
`SONATYPE_USERNAME`, `SONATYPE_PASSWORD`) are still repository secrets, and the environment gates
only a job that declares it. Anyone with write access to the repository can read repository
secrets from another workflow on a branch of their own, with no approval. The next step, which
the owner does in Settings once this change is on `main`, in this order: move the four secrets into
the `release` environment, then limit its deployment policy to `main` and `v*` tags.
Put the two new Gradle Portal secrets in that environment too. The existing `central` job
already declares it, so the same approval covers both registries.

If a run fails after the upload: a portal deployment is only staged until Publish, so nothing is
public. A re-run stages a new deployment, so drop the stale one in Publish → Deployments first.
Never re-run the upload after a Publish click: Central does not accept the same version twice, so a
defect needs a new patch version. Expect the environment approval to be asked again on a re-run
(expected, not verified on GitHub). The `central` job times out after 30 minutes, so a hung upload
does not hold the secrets for the default six hours.

## Workflow runbook

1. Set the version in the six places that carry it: `build.sbt` (`ThisBuild / version`),
   `gradle-plugin/build.gradle.kts` (`version`), `java-api/build.gradle.kts` (`version`),
   `maven-plugin/pom.xml` (the plugin's own version and the `hocon-fmt-java-api` dependency),
   `mill-plugin/build.mill` (`formatterVersion`), and the `additional_dependencies` of all four
   hooks in `.pre-commit-hooks.yaml`. The npm and wheel versions follow `build.sbt`. All core, cats
   and zio platform artifacts and the Java API also inherit that version. `signRelease` and
   `publishRelease` name the wave-1 projects directly (`releaseProjectIds` in `build.sbt`), and
   `sbt test` fails when that set stops being closed under its dependencies.
   The wave-1 version check covers four files: `build.sbt`, `java-api/build.gradle.kts`,
   `gradle-plugin/build.gradle.kts` and both versions in `maven-plugin/pom.xml`. Mill and hook
   pins may trail until wave 2; `RELEASE_WAVE=2` checks all six locations.
2. Run the `Release` workflow by hand first (Actions → Release → Run workflow), leave
   `dry_run` checked (the default), and keep `RELEASE_WAVE` unset or `1` for v0.1.0. Approve the
   `release` environment. It builds and signs the five sbt artifacts, runs Maven `-Prelease verify`,
   and runs Gradle `-PreleaseSigning check publishToMavenLocal` against a private Maven repository.
   No Central or Plugin Portal upload occurs, even when a tag is selected and all secrets exist.
   The real signing passphrase is checked here; registry credentials are not validated by this run.
3. Push a tag `v<version>` on main's history. The secret-free `version` job fetches main and
   rejects a tag outside its history or inconsistent versions for the selected wave before the
   `central` job requests environment approval. The approved job builds and signs all
   wave-1 artifacts, then uploads two Central deployments (sbt and Maven) and submits the Gradle
   plugin if both Portal credentials exist. A skipped Portal upload means the first-release
   scope is still incomplete: finish Portal setup before announcing v0.1.0.
   A wave-2 tag (`RELEASE_WAVE=2`) also links and smoke-tests the native binaries, builds their
   wheels, packs npm, builds the web script, and attaches them to a GitHub release. Mill stays disabled.
4. Publish and verify:
   - In Central Publish → Deployments, inspect both deployments and publish the sbt one first:
     it carries core, cats, CLI, Java API and sbt 1.x plugin. Then publish the Maven plugin.
     A release cannot be undone. The sbt, Maven and Gradle plugins resolve the Java API and core
     from Central, so wait for those artifacts to be available before testing or announcing plugins.
   - Check Plugin Portal's initial-review status for `eu.ww86.hocon-fmt` and finish any requested
     namespace verification. The workflow submission may precede Central publication; consumer
     tests must wait for Central dependencies and Portal approval.
   - Publish PyPI and npm before announcing a wave-2 tag: the hooks pin those exact versions.
5. Try every channel the wave shipped as a user would (below).
6. After publishing, open a follow-up PR setting the development version to
   `0.1.1-SNAPSHOT` after `0.1.0` (or the next planned version after later releases).
   Update the build versions and checkout instructions together; keep the hook pins and
   released usage examples on the published version until the next release.

Until the artifacts are on Maven Central, the Gradle and Maven builds resolve the Java API, and the
core through it, from Maven Local: run `sbt coreJVM/publishM2 javaApi/publishM2` before either
build. The Mill build resolves the core from the local
Ivy repository: run `sbt coreJVM/publishLocal` before building it. The java-api tests load the jar sbt publishes,
so `sbt javaApi/publishM2` joins `sbt coreJVM/publishM2` before `./gradlew check` in `java-api/`.

## pre-commit

A user's configuration names this repository and a tag:

```yaml
repos:
  - repo: https://github.com/kastoestoramadus/hocon-fmt
    rev: v0.1.0
    hooks:
      - id: hocon-fmt
```

pre-commit clones the tag, reads `.pre-commit-hooks.yaml` and installs the hook's
`additional_dependencies` from PyPI or npm. So a tag works only once the versions it pins are
published, and nothing else about the hooks needs releasing. Users move to a new release with
`pre-commit autoupdate`. The hooks install everything at install time, so they also run on
pre-commit.ci.

At a wave-1 tag the hooks do not install: their pins name PyPI and npm versions that wave never
publishes, so the install of the hook environment fails — pip with `No matching distribution
found for hocon-fmt==<version>`, npm with a 404 — and the repository is left untouched. v0.1.0
users format through the JVM CLI and the sbt 1.x, Gradle and Maven plugins; the hooks are worth
announcing only from the first wave-2 tag, whose pins are published.

The wheels cover Linux x86_64 and aarch64 with glibc 2.34 or newer and macOS on Apple silicon.
Anywhere else (Windows, Intel Macs, older Linux) pip finds no wheel, and the `-node` hooks are the
way in.

To check a release, in any repository with a `.conf` file:

```bash
pre-commit try-repo https://github.com/kastoestoramadus/hocon-fmt hocon-fmt --ref v<version> --all-files
```

## Earlier wave-1 dry run

The JVM and Scala.js package is `eu.ww86.hoconfmt`; Java consumers use
`import eu.ww86.hoconfmt.java.HoconFmt;`. Distribution and artifact names remain `hocon-fmt`.

The wave-1 set was published into a private Maven repository with the release version set for the
one session (`build.sbt` then said `0.1.0-SNAPSHOT`, since bumped to `0.1.0`; nothing reached `~/.m2`):

```bash
sbt -Dmaven.repo.local=<dir>/m2 \
  'set ThisBuild / version := "0.1.0"' \
  "coreJVM/publishM2" "catsJVM/publishM2" "cliJVM/publishM2" "javaApi/publishM2" "sbtPlugin/publishM2"
```

Three scratch consumer builds resolved from it alone, with no Maven Local or Ivy Local configured:

- a Scala 3 sbt build depending on `hocon-fmt-core_3` formats bytes from a `Verdict` and reports a
  refusal without writing;
- an sbt build with `addSbtPlugin` fails `hoconFormatCheck` naming the file, `hoconFormat` rewrites
  it and the next check passes; the plugin resolves the Java API and its transitive core at task
  time, in a Scala 2.13 build;
- `hocon-fmt-cli_3` resolves and runs. No launcher script ships, so the jar is what runs; coursier
  does it directly (`cs launch eu.ww86:hocon-fmt-cli_3:0.1.0 -- --version` against the private
  repository, `-r file://<dir>/m2`, prints `hocon-fmt 0.1.0`), and the manifest's
  `Main-Class: eu.ww86.hoconfmt.CmdApi` serves a `java -cp` run. `--check` exits 1 then 0 after
  formatting, a missing path exits 2 with `cannot read <path>: no such file`. Resolving it without
  `hocon-fmt-cats_3` fails, which is why the adapter is in the set.

Still to verify, and only with the real secrets: `signRelease` (it reaches `gpg` and fails without
`PGP_PASSPHRASE`; the `central` job is where it runs) and `sonaUpload`. The current workflow also signs Maven and Gradle in wave 1; those signing paths and registry
uploads still need the real-secret trial. Mill remains deferred. The user-facing text of the release is
[plans/release-notes-0.1.0.md](plans/release-notes-0.1.0.md).

## Trying a release

The native and npm commands below are for later releases. For v0.1.0, try the JVM artifacts
as in the earlier dry run and the sbt 1.x, Gradle and Maven plugins as below.

```bash
pipx run hocon-fmt --check application.conf          # the wheel, native
npx hocon-fmt@<version> --check application.conf     # the npm package
```

Try the ZIO usage example from [usage](usage.md) on the JVM and Native, and the text API on
Scala.js, with the released adapter version and no local repository configured. Check a refused
file stays untouched and a streamed check still reports a later readable file.

Try the sbt 1.x, Gradle and Maven plugins as [usage](usage.md) shows them for v0.1.0; try
Mill once its plugin ships. Use the released version in a project that
has no local repositories configured: `mavenLocal()`, `~/.m2` and `~/.ivy2/local` would hide a
missing publication.
