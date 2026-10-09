# 2026-09-26 — #13 — Includes preserved; lost comments and clobbering refused

**Change:** Formatting preserves quoted-comment includes, refuses lost comments or includes and
avoids concurrent rewrites of duplicate CLI paths.

**Look at again before a release:** Retire sconfig-specific refusal expectations as upstream fixes
ship; `HoconText` and `IncludeMasking` remain machinery an eventual syntax-tree parser would
replace.
