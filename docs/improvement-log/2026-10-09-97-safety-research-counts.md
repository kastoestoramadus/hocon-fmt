# 2026-10-09 — #97 — formatter research counts corrected

**Change:** A self-review of the merged safety-and-UX research (#93) found three counts in its
improvement-log entry that the file does not support: it said 474 lines (the file is 475), 57
instructive fixes (the history tables hold 55 rows) and 72 HOCON-translatable cases (the case tables
hold 73). The entry now carries the numbers counted from the file. The research file itself is
unchanged; its claim that ruff's formatter fuzz targets are built but not run in CI and that
`ruff_formatter_validity.rs` asserts the wrong polarity was checked at the clone's HEAD before it
merged.

**Look at again before a release:** — (the counts are the entry's own; the research conclusions are
untouched).
