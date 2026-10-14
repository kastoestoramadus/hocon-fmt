# 2026-10-13 — #127 — Page fork pin and output fixtures

**Change:** The page builds the locally published sconfig fork at 727eb3074a, with remote-tag/script/build/docs consistency tests. Scala.js page goldens now use the requested separator in unresolved merges and preserve same-line include order; the page suite pins append-comment and overflowing-number differences. Published core stays on released sconfig 2.0.0 with unchanged goldens.

**Look at again before a release:** Read the public examples after this fork update. Recheck every page output against the named upstream fixes when moving the pin; run the page golden regeneration on Scala.js and preserve released-core expectations. Remove the local-publish seam when upstream releases the detached-comment option, following docs/site.md.
