# 2026-10-08 — #54 — The style becomes a choice

**Change:** The style becomes a choice: the default separator is `=` where the core forced `:`, set
per run by `--separator`, `--double-indent`/`--no-double-indent` and
`--simplify-nested-objects`/`--no-simplify-nested-objects`, or per repository by a `.hocon-fmt.conf`
found from the formatted file upwards and stopped at `.git`; unknown keys and mistyped values are
errors, and every formatter property runs for every option combination. The plugins and the java API
format with the new default but take no settings yet — plugin options and config-file lookup there
wait for the java-api migration; the value parsing is already shared (`FormatOptions.parse` in
core), the lookup is cli-only. Review fixes: a file with an include is refused
(`Refusal.ReservedName`) when user text spells `__INCLUDE_` instead of being restored on a guess; an
explicit `null` option is an error.

**Look at again before a release:** The reserved-name refusal is blunt: look at whether real files
hit it before a release. Which refusal a defect gets depends on `simplify-nested-objects` (pinned in
`ExamplesSpec`).
