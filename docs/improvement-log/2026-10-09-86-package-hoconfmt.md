# 2026-10-09 — [#86](https://github.com/kastoestoramadus/hocon-fmt/pull/86) — Rename the package before the first release

**Change:** Rename the Scala, Java and Kotlin package to `ww86.hoconfmt`, including source directories, generated sources, reflective loading, plugin consumers and documentation. This happens before the first release and changes no formatter behaviour. Distribution, command, Maven artifact, npm and Python wheel names stay the same.

**Look at again before a release:** Check the Java import `ww86.hoconfmt.java.HoconFmt`, CLI manifest and isolated sbt plugin loading against the release artifacts; keep the Python wheel filename patterns unchanged.
