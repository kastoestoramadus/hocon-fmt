# 2026-10-09 — #93 — formatter research: safety, tests, APIs

**Change:** one research file, `docs/research/formatters/safety-and-ux-history-tests-api.md` (474
lines), covering prettier, black, rustfmt, gofmt, scalafmt and ruff: the safety checks each implements
with file:line in a shallow clone at its 2026-10-09 HEAD (per-fixture second passes and AST
equivalence, corpus runs over real projects, known-bad ledgers and budgets, fuzz targets), 55 rows of
instructive fixes with shas and dates, 73 HOCON-translatable test cases with source URLs and licenses,
a capability comparison against hocon-fmt, the top user requests with reaction counts fetched through
the API, and an ideas-to-migrate table with cost and value. No test in this repository was added or
changed by it.

**Look at again before a release:** the S-sized ideas are the ones a release decides on — `--diff` on
`--check` with the refusal reason in the summary, the golden-output fixed-point check in
`GoldenFileSpec`, BOM tolerance in `.hocon-fmt.conf`, and a corpus idempotence run with a budget — and
every case expectation the research marks **(probe)** needs one run through `cliJVM/run` before it
becomes a fixture, since those rest on `docs/limitations.md` rather than on a run.
