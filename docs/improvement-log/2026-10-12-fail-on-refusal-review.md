# Refusal policy review follow-ups

Strict refusal policy includes selected JSON and properties files through OtherFormat, even
when their bytes are valid HOCON. Default policy remains unchanged. Tests pin this in core,
CLI, the Java boundary and all four plugins, with unchanged-byte assertions. Clean CLI runs
omit the skipped summary; positive counts remain visible.

Scripted sbt now inherits the outer Ivy home through build settings. Its refusal fixture
asserts both the nested Ivy home and the loaded plugin's code source. Before release, run the
fixture with a private Ivy home and no manual scriptedLaunchOpts override; also rerun strict
and default name-based refusals in format and check modes across the integrations.
