# 2026-10-09 — #63 — Identity-preserving file writes

**Change:** `HoconFmt.formatFile` owns identity-preserving writes for Gradle, Maven and sbt: resolve
symlinks, stage complete output, adopt and verify owner/group/all mode bits, atomically replace only
with writable file and directory, otherwise write in place. Java regressions and all three plugin
integrations pin hard-link behavior and special modes. Mill stays on core.

**Look at again before a release:** Verify unix-view behavior and the in-place fallback on release
platforms; the fallback retains the inode but loses crash-atomicity. Nothing is released yet.
