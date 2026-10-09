# 2026-10-08 — #34 — Includes that would cross fields are refused

**Change:** Formatting refuses includes that would cross fields and change which values win,
including inside arrays.

**Look at again before a release:** `IncludeOrder` adds source/output parses; benchmark the cost and
revisit when sconfig preserves parse order; overridden definitions still escape detection.
