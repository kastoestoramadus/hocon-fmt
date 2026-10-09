# 2026-10-09 — #83 — the pre-commit wave-2 note, the guards in CI, install options

**Change:** README, `docs/usage.md` and the page's pre-commit tab say the hooks ship in wave 2 (they
pin PyPI and npm versions published only then); CI's new `release-guards` job runs
`scripts/check-release-version-test.sh` and `scripts/changes-classifiers-test.sh` on every code
change, and `cli-acceptance` runs `scripts/release-smoke-test.sh` against the native binary it has
just linked; new classifier cases pin `scripts/` and `.github/workflows/` as code, so a docs-only
rule cannot skip the guards; `docs/ideas.md`'s Distribution section opens with an install-options
map for the CLI and the order to build them.

**Look at again before a release:** wave 2 publishes the versions the hooks pin, so the note leaves
README, usage and the page in the wave-2 release PR; the install-options map says which channels
that wave ships. The guard job must stay cheap and gated only by `changes`.
