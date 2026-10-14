# 2026-10-14 — [#130](https://github.com/kastoestoramadus/hocon-fmt/pull/130) — Mill through the Java API

**Change:** The Mill adapter compiles for the host's Scala 3.8.2, resolves the Java API and formatter runtime separately, and calls inspection through a platform-parent loader. Both Mill 1.1.4 and 1.1.10 run the published plugin. A Scala consumer regression prevents its Scala version forcing from leaking into the worker; formatting now uses the Java API's staged replacement. Mill CI is blocking again.

**Look at again before a release:** Mill remains outside the first publish selection. Before selecting it, rerun the real-host suites and confirm the worker's Scala version remains independent of the consumer; keep generated formatter coordinates tied to the standalone build's version.
