# 2026-10-08 — #50 — A refusal names the place a parse tripped

**Change:** A refusal names the place a parse tripped — the path the CLI or a plugin reports,
`playground` for the page — and a file named `.json` or `.properties` is refused as
`Refusal.OtherFormat` rather than rewritten as HOCON; `newLineAtEnd` and `showEnvVariableValues` are
pinned instead of inherited.

**Look at again before a release:** The refusal is by name only, so an integration that ever wants
to write `.json`/`.properties` in place has to say so; the two pins mirror sconfig's current
defaults and are rechecked when sconfig moves them.
