# Refuse quadratic merges at the scan

**Change:** The scan charges every definition that merges through an already-defined key prefix
(dotted keys and repeated `+=`) and refuses past `MaxMergeWork = 2,000,000` with kind
`slow-merges`, before sconfig's parser spends quadratic time on the file. Written by an idle
task (q33) on the pre-#151 engine and ported onto the document engine; there a `+=` chain under
the budget now formats instead of refusing, and its test says so.

**Before release:** The timings in `docs/limitations.md` were measured before #151; rerun the
1,414 and 16,000 shapes through the JVM and Native CLIs and update them.
