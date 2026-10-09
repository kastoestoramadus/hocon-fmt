# 2026-10-08 — #62 — The sbt plugin moves onto the Java API

**Change:** The sbt plugin resolves java-api through buildinfo and calls `HoconFmt.check(byte[],
name)` in its isolated loader; a JDK-only shim reads record accessors and `Enum.name()`. Scripted
pins the worker classpath, verdict mapping and untouched invalid UTF-8. The old core facade and its
Java/Scala tests are removed; the published API contract suite covers their reflective boundary and
now also checks named and other-format refusals.

**Look at again before a release:** Step 5 unifies file writes through the Java API. Keep the byte
entry point and isolated-loader contract green when the API changes.
