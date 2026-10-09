# First release: the site's Scala showcase

After publishing 0.1.0, verify the core, cats, ZIO and Java API coordinates on Maven Central,
then flip the single `ScalaShowcase.released` constant and update its pre-release test.
Exercise the URL hand-off with edited code on every Scala tab.
The Java example remains a copyable Java source: Scastie runs Scala, not Java sources.

The page controls the submitted Scala version and dependencies; Scastie's build-settings editor
can change them afterwards. Directive rejection is a UI constraint, not a security boundary.
Enforcing a classpath for arbitrary remote programs requires our own controlled execution service.

Local browser compilation must wait for the browser-scala-probe report. If it proves the core
classpath works in Chrome, stage compiler assets through `site/build`, lazy-load them when the
editor opens, and detect `WebAssembly.JSTag`, `Suspending` and `promising` before loading.
Keep binaries out of Git and retain the Scastie fallback with an explanation when unavailable.

Generated with gpt-6.1-sol/m through Codex
