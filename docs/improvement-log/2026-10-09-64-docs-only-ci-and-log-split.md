# 2026-10-09 — #64 — Docs-only CI and the one-file-per-entry log

**Change:** CI's `changes` job skips build jobs for docs-only diffs, fail-open; the log becomes one
file per entry.

**Look at again before a release:** Add `changes` to the ruleset's required checks, and recheck the
docs-only path list when the build starts reading a new doc.
