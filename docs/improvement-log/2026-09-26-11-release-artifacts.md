# 2026-09-26 — #11 — Tags build binaries, wheels and npm archives

**Change:** Pre-commit can format or check through native wheels or Node, and tagged releases build
binaries, wheels and npm archives.

**Look at again before a release:** Exercise the release matrix and registry installs;
`.github/workflows/release.yml` does not yet publish to PyPI or npm, and Windows relies on Node
hooks.
