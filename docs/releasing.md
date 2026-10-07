# Releasing

Every channel ships the same core, so all of them are released together, at one version. Nothing
has been released yet: Maven Central is set up (below), the other registries are still to do, and
the `Release` workflow has never run.

| artifact | built by | published to | users get it through |
|---|---|---|---|
| `hocon-fmt-core_3`, `hocon-fmt-cli_3` | sbt | Maven Central | a library dependency; the JVM command line |
| `sbt-hocon-fmt` | sbt | Maven Central | `addSbtPlugin` |
| `hocon-fmt-maven-plugin` | `maven-plugin/` | Maven Central | `<plugin>` |
| `mill-hocon-fmt_mill1_3` | `mill-plugin/` | Maven Central | `//| mvnDeps` |
| `eu.ww86.hocon-fmt` | `gradle-plugin/` | Gradle Plugin Portal | `plugins { id(...) }` |
| native binaries, `hocon-fmt.js` | `Release` workflow | GitHub release | a download; the playground |
| wheels carrying the native binary | `Release` workflow | PyPI | the pre-commit hooks, `pipx install` |
| npm package carrying the Node build | `Release` workflow | npm | the `-node` pre-commit hooks, `npx` |

The names are free on every registry (checked 2026-09-26).

## One-time setup

Accounts and secrets only you can create; each section ends with the code this repository still
needs, which is a pull request of its own.

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

The `central` job in `release.yml` does the rest. sbt-ci-release is not used: it publishes without
a pause and takes the version from tags, where this repository sets it by hand. Instead, sbt's own
Central Portal support (`publishRelease`, which signs and calls `sonaUpload`), the `release`
profile of `maven-plugin/pom.xml` (`central-publishing-maven-plugin` with `autoPublish` off) and
`mill.javalib.SonatypeCentralPublishModule/publishAll --shouldRelease false` each leave their
deployment in the portal, validated, until someone clicks Publish under Publish → Deployments in the portal.

### Gradle Plugin Portal

1. Sign in to [plugins.gradle.org](https://plugins.gradle.org) with GitHub. The portal only takes
   new plugins under a namespace it can verify. The plugin id is `eu.ww86.hocon-fmt`; if the portal
   will not verify that domain, it falls back to `io.github.kastoestoramadus.hocon-fmt`, which
   signing in with GitHub verifies, and the id changes in `gradle-plugin/`, the README and
   [usage](usage.md).
2. Copy the API key and secret from your profile into the secrets `GRADLE_PUBLISH_KEY` and
   `GRADLE_PUBLISH_SECRET`.

Code still needed: the `com.gradle.plugin-publish` plugin, with the website, VCS URL and tags it
requires, and `./gradlew publishPlugins` in the release job.

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

## Each release

1. Set the version in the five places that carry it: `build.sbt` (`ThisBuild / version`),
   `gradle-plugin/build.gradle.kts` (`version`), `maven-plugin/pom.xml` (the plugin's own version
   and the `hocon-fmt-core_3` dependency), `mill-plugin/build.mill` (`formatterVersion`),
   and the `additional_dependencies` of all four hooks in `.pre-commit-hooks.yaml`. The npm and
   wheel versions follow `build.sbt`.
2. Run the `Release` workflow by hand first (Actions → Release → Run workflow). It builds every
   artifact without releasing anything, which is how to find out the matrix works. Its `central`
   job also signs with the real key and passphrase, uploading nothing: the passphrase is checked
   there, because a typo cannot be seen from outside.
3. Push a tag `v<version>`. `scripts/check-release-version.sh <version>` runs first and stops the
   job if any place that carries the version disagrees with the tag. The workflow links native binaries for Linux (x86_64, aarch64) and
   macOS (aarch64), smoke-tests them, wraps each in a wheel, packs the npm package, builds the web
   script, and attaches all of it to a GitHub release.
4. Publish, in dependency order:
   - Maven Central, first: the Gradle, Maven and Mill plugins and the sbt plugin all resolve the
     core from there. The tag leaves three deployments in the portal (sbt, Maven, Mill). Look each
     over in Publish → Deployments, then publish the sbt one, which carries the core, first. A
     release cannot be undone.
   - The Gradle Plugin Portal.
   - PyPI and npm, before announcing the tag: the hooks at that tag pin those exact versions.
5. Try every channel as a user would (below).

Until the core is on Maven Central, the Gradle and Maven builds resolve it from Maven Local and
the Mill build from the local Ivy repository: run `sbt coreJVM/publishM2` or
`sbt coreJVM/publishLocal` before building them.

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

The wheels cover Linux x86_64 and aarch64 with glibc 2.34 or newer and macOS on Apple silicon.
Anywhere else (Windows, Intel Macs, older Linux) pip finds no wheel, and the `-node` hooks are the
way in.

To check a release, in any repository with a `.conf` file:

```bash
pre-commit try-repo https://github.com/kastoestoramadus/hocon-fmt hocon-fmt --ref v<version> --all-files
```

## Trying a release

```bash
pipx run hocon-fmt --check application.conf          # the wheel, native
npx hocon-fmt@<version> --check application.conf     # the npm package
```

Then the plugins as [usage](usage.md) shows them, with the released version, in a project that
has no local repositories configured: `mavenLocal()`, `~/.m2` and `~/.ivy2/local` would hide a
missing publication.
