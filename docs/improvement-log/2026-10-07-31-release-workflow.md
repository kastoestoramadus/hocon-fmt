# 2026-10-07 — #31 — Tagged releases upload signed artifacts

**Change:** Tagged releases upload signed sbt, Maven and Mill artifacts to Central for manual
publication, after checking version agreement.

**Look at again before a release:** Exercise `.github/workflows/release.yml` with real secrets; the
Central job is unverified and Mill has no signing-only dry run.
