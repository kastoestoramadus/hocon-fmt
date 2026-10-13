# 2026-10-13 — [#118](https://github.com/kastoestoramadus/hocon-fmt/pull/118) — Stack-safe duplicate reporting

**Change:** Duplicate-report syntax and predicate walks and include-shadow value walks use explicit pending lists or tail recursion. A thousand nested objects now formats on the default JVM stack (since `#124`, nesting past 80 levels is refused first, and `TraversalStackSpec` reaches the walks below that guard); input beyond the remaining parser or renderer stack capacity is refused as `NotHocon` with an explicit capacity reason. The existing verdict types and integrations remain sufficient.

**Look at again before a release:** Recheck the deep corpus probe and the include-shadow cost ratios when sconfig changes. Its parser and renderer still recurse, and the duplicate-report matching algorithm retains its existing cost on deep paths.
