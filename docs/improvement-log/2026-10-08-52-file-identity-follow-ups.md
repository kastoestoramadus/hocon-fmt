# 2026-10-08 — #52 — File identity and review follow-ups

**Change:** Follow-ups from the #43/#48 reviews: inode and hard-link tests pin that the cats and ZIO
adapters replace by rename on JVM and Native (the identity tests alone would pass with an
always-in-place write); the `zio` module row of the architecture table gains “identity-preserving
formatting”, matching the `cats` row, and `ZioFiles.format`'s doc says the formatted text is written
by a staged replacement or in place; ideas.md records a dependency-free shared file-identity module;
the detached-header banner is reworded away from a corpus line; the `+=` expansion is quoted as it
renders — three lines, not one.

**Look at again before a release:** The rename pins are the guarantee to keep green; the
shared-module idea awaits a decision on a sixth published artifact.
