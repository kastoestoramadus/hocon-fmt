# 2026-09-26 — #12 — Per-phase benchmarks in git notes

**Change:** Per-phase benchmarks track every runtime and remove a redundant output parse, making
formatting faster.

**Look at again before a release:** `scripts/bench.py` stores history in Git notes and CI only
reports regressions; review the maintenance cost and noisy-runner thresholds.
