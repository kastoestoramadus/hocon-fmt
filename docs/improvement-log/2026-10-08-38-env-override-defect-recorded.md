# 2026-10-08 — #38 — The env-override defect is recorded

**Change:** `SconfigDefectsSpec` records the env-override defect (`x = 1` then `x = ${?X}`); 357 of
1,650 real files are refused for it, fixed by ekrich/sconfig#600.

**Look at again before a release:** Drop the refusal tests that turn green once a sconfig with #600
is released.
