# 2026-10-09 — #82 — sbt 2 plugin plan and spike

**Change:** `docs/plans/sbt2-plugin.md` plans a wave-2 sbt 2 plugin that calls the core in process,
from a spike that built, published (`sbt-hocon-fmt_sbt2_3`) and ran one under sbt 2.0.10: the core's
3.8.2 TASTy is readable because 2.0.x runs on 3.8.4, a consumer build refuses a bad file, and
scripted works on sbt 2. The plan repeats the whole 1.x surface, stands the plugin up as its own
build beside the unchanged 1.x plugin, writes in place like Mill (limitation text listed), and
carries the scripted suite over.

**Look at again before a release:** the sbt 2 plugin lands in wave 2, but the artifact name has to
be in `docs/releasing.md`'s version list and the release job before the tag; re-verify the plugin
still compiles when sbt 2.1 (Scala 3.9) goes final.
