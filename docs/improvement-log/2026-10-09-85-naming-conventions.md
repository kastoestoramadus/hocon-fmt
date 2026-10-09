# 2026-10-09 — #85 — The naming convention of every context

**Change:** [docs/naming.md](../naming.md) gives the rule, its source and this repository's usage
for every context the name appears in — artifact ids, the package, plugin ids, hook ids, commands
and flags, config keys, environment variables, tags, branches, log file names, and the planned
Docker, Homebrew and coursier names — with a verdict per row and the claims that could be checked
settled by running javac, scalac, npm's validator, PyPI's normalisation and the wheel builder.
AGENTS.md gains the rule line; architecture.md links the file.

**Look at again before a release:** the package is the one mismatch a release makes expensive — it
is `eu.ww86.hoconfmt` while the group id and verified domain are `eu.ww86` and `ww86.eu`, so the
convention-conformant name is `eu.ww86.hoconfmt`, and the rename is only cheap before the tag. The
wave-2 names (the Docker image, the Homebrew tap, the coursier app, the Action, `sbt-hocon-fmt_sbt2_3`)
are documented but unbuilt, and the Gradle Plugin Portal's fallback id changes the plugin
coordinates if `ww86.eu` cannot be verified there; re-check each when it is built.
