# 2026-10-08 — #44 — The cats-effect file API preserves file identity

**Change:** Cross-built cats-effect file API that replaces a file only when the staged copy provably
keeps its owner, group and every mode bit, and writes in place otherwise; streaming checks and
opt-in refusal errors; CLI delegates file operations.

**Look at again before a release:** Verify the per-platform attribute sources (the JVM's unix view,
the C library on Native, Node's fs on Scala.js) and the in-place fallback before release; a
replacement is a new file, so hard links keep the old content.
