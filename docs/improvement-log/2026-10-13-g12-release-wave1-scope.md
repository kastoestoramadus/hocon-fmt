# 2026-10-13 — Wave-1 release includes Maven and Gradle plugins

**Change:** Wave 1 builds and signs the JVM artifacts and sbt 1.x, Maven and Gradle plugins.
Maven uses its existing Central Portal profile; Gradle uses Plugin Publish with a clear skip
when either Portal credential is absent. Manual runs default to a dry run, including on tags,
and the version and dependency-closure guards include both standalone plugins.

**Look at again before a release:** Run the default dry run with real signing secrets. The owner
must create a Plugin Portal account and API key, supply its two secrets, and prove ownership of
`ww86.eu` for `eu.ww86.hocon-fmt` when the Portal requests it. Confirm initial Portal approval,
then publish both Central deployments in dependency order. Registry uploads and the real signing
key are unverified locally. Mill remains disabled and later channels require the former wave-2 switch.
