# 2026-10-09 — [#75](https://github.com/kastoestoramadus/hocon-fmt/pull/75) — Style switches shape the resolved view

**Change:** The playground's separator, nesting and indentation switches moved the Formatted pane
and left the Resolved tab unmoved: `resolvedOutput` put sconfig's own render of the resolved config
straight on screen, so the chosen `FormatOptions` reached only the formatted view. What sconfig
renders resolved is raw text again, so the render now goes through `HoconFormatter.format` with the
chosen options — the same renderer the formatted pane shows. A refused input still stays as typed,
and the (not expected to happen) render our renderer still refuses falls back to the untouched
render instead of a message, so the values stay on screen. Test first in 14753fe: toggling each
switch with the Resolved tab shown, red before (`Failed: Total 19, Failed 1` — the pane showed
sconfig's raw render, unflattened `database { host = localhost }` and all), green after
(`sbt site/test`: `Passed: Total 74, Failed 0, Errors 0, Passed 74`).

**Look at again before a release:** Every resolved render now pays for our formatter's full
pipeline (probe pass, second pass) on top of sconfig's resolution — fine for a playground pane, but
worth a `scripts/bench.py` thought if the page ever feels it. The silent fallback to the untouched
render has no known case to fire in; if one ever shows, it wants a message beside it. And when the
sconfig fork goes (UPSTREAM-SCONFIG), re-check the resolved view against released sconfig's render.
