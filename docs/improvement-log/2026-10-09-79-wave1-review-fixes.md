# 2026-10-09 — [#79](https://github.com/kastoestoramadus/hocon-fmt/pull/79) — Wave-1 notes and guards after review

**Change:** A review of #79 (haiku-5.5/m) raised ten points; each was run before it was acted on.
The notes' "single limit" claim went, and the known-limits list now names every refusal class the
wave-1 CLI shows (object, array and string concatenations with a substitution, self-reference,
`+=` after a definition, a root array, the `${MY_LIST[]}` suffix, `a : include "x"`, `__INCLUDE_`),
and says that `${?ENV}` on a first definition formats while only an override is refused. The notes
and `docs/usage.md` now say how the released CLI is run — `cs launch
eu.ww86:hocon-fmt-cli_3:0.1.0 -- …`, as no launcher script ships — quoted from a run against the
private repository, and `docs/releasing.md` records the `set ThisBuild / version` publish command
the dry run used. `checkReleaseSet` now also sees the sbt plugin's Java API edge (a `BuildInfo`
coordinate, not a `dependsOn`): with `javaApi` dropped from the set it fails naming `javaApi
(needed by sbtPlugin)`, where before the fix it passed. The `.json`/`.properties` refusal is
documented as decided by the name a symlink resolves to, the version script as checking six places
and naming the five that need the bump, the log as 73 entries, and 803a770 as a commit-named
entry; `docs/limitations.md` gains "Platforms and edge cases" — Windows and a TTY's stdin
untested; a symlink followed with its target's name deciding, and an invitation to say if the
link's own name is needed; the in-place write fallback and the partial file an interrupted run
leaves; and the Mill plugin writing in place as a deliberate simplification, with an invitation to
report if it matters.

**Look at again before a release:** the dry run's version was set for the session only — the real
release still bumps the six places `scripts/check-release-version.sh` reads. Nothing in the notes
or the guards is left open: the symlink name question is documented with an invitation to report,
not a pending decision.
