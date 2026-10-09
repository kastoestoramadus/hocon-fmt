# 2026-10-09 — [#76](https://github.com/kastoestoramadus/hocon-fmt/pull/76) — Site UI behaviour coverage

**Change:** Added seven mounted component tests for formatted-view switches in both directions, tab accessibility and pane state, refusal links and recovery, and partial/all upstream refresh failures with snapshot badges and other recent work. Every new test failed under a deliberate production mutation; production code is unchanged. Documented the Scala.js scoverage link failure and the boundary of FakeDom's checks in docs/site.md.

**Look at again before a release:** Run the page in a browser for responsive CSS, native keyboard/focus behaviour, details disclosure, pane resizing and bootstrap. FakeDom cannot validate those. Revisit Scala.js coverage when the Scala coverage runtime links; UseIt currently has no copy buttons, so any future clipboard feature needs its own behavioural tests.
