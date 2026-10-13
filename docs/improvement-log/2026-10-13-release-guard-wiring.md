# Release guard wiring

The release workflow tests reject step-level `if` and `continue-on-error` keys on the
ancestry and credentials guards, and require upload steps to follow the guards. The
ancestry step always runs; its shell allows branch dry runs without a tag ancestry check.
Disabled Mill uploads are included in the order check so enabling Mill remains guarded.

Before a release, run `python3 scripts/release-workflow-test.py` and actionlint. These
stdlib-only tests inspect this workflow's indentation and execute its shell against local
fixtures; GitHub expressions, environment approval and real registry uploads still need
the release dry run. Action pin comments name verified exact tags when a tag matches the SHA.
