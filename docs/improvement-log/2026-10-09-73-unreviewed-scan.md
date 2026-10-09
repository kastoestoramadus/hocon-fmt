# 2026-10-09 — #73 — CLI file handling and workflow checks

**Change:** The review scan of #43–#72 fixes inherited exclusions disappearing below an unmatched
nested `.gitignore`, denied CLI writes passing with exit 0, NOTICE-only changes not deploying,
and the native release smoke expecting the old separator. Regression tests precede fixes;
classifier cases use independent diffs, and a local harness runs the release workflow's actual
smoke step. Rename mutation fails the existing inode and hard-link tests.

**Look at again before a release:** Run `scripts/changes-classifiers-test.sh` and, after native
linking, `scripts/release-smoke-test.sh`. Decide whether a symlink's alias or target name should
control other-format refusal: the CLI currently formats a `.json` alias of a `.conf` target.
The documented in-place fallbacks and Mill writes still do not promise crash-atomicity.
