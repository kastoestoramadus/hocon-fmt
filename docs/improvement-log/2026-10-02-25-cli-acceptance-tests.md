# 2026-10-02 — #25 — Named acceptance tests for the CLI

**Change:** Named Scala acceptance tests check the CLI’s bytes, diagnostics and exit codes as real
JVM, Node and Native processes.

**Look at again before a release:** `CliAcceptanceSuite` does not cover Windows or terminal stdin;
decide the supported platform contract before release.
