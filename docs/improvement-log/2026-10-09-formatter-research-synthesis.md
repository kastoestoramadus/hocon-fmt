# 2026-10-09 — Formatter research synthesis and executable observations

**Change:** Synthesized the four profile families and their history/test/API passes without editing
those inputs. Added weighted tagged-case counts, explicit formatter invariants, byte fixtures and a
real JVM CLI replay runner. Recorded both separators, check/write/second-pass/stdin behavior, bare
sconfig render/value evidence, and filesystem setups. Recommendations include a property plan,
independent resolver, test admission rule and tiered budgets. New backlog entries link to the report;
limitations explain the dependency cost in user terms.

**Look at again before a release:** Both accepted include override-barrier forms change meaning
while parsing and remaining a fixed point; see REPORT's bug table and linked minimal inputs.
Treat this shared safety gap as a release concern, even though it was already documented. Re-check
every recorded observation after a formatter or sconfig upgrade, and triage refusals that improve.
Do not mistake matching known bad actual output for a correctness gate. Keep the in-place fallback
interruption warning accurate: the actual kill probe left its target empty, while a staged-write
kill kept original bytes. The research's CRLF-inside-string hypothesis did not reproduce.

**Validation:** Real JVM CLI process matrix and filesystem probes; `FileFormatterFailureSpec`
reported `Passed: Total 2, Failed 0, Errors 0, Passed 2` for staged failure/cancellation. Replaying
the recording now reports 958 observations, 0 differences, in about 12 minutes; that required
`-XX:-UsePerfData` in the runner (parallel JVMs raced on `/tmp/hsperfdata_<user>`) and normalising
the temporary path inside the hex-encoded bare-sconfig evidence, since unresolved-merge banners
name their source path. Two probe files whose names read like an injection payload were renamed to
`case-placeholder-collision*` with their inputs unchanged. See REPORT for the final probe counts
and replay cost; no production formatter changes, no upstream messages, and no JS/Native claim.

Generated with gpt-6.1-sol/m through [Codex](https://developers.openai.com/codex).
