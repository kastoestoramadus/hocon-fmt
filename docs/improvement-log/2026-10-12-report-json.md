# Minimal CLI JSON reports

The CLI's --report path or --report - emits version, checked, changed, refused and failed only.
It reports original per-file decisions, preserves the formatter's exit policy and input writes,
and records configuration errors without inspecting selected file contents. Refusal reasons
include the kind. Diagnostic arrays are sorted by path; stdout reports redirect the human
report to stderr. Stdin supports a report file, keeping formatted stdout unchanged.

Before release, rerun JSON goldens, the generated reporting invariants, and the real CLI
process suite on JVM, Node and Native. Check original change counts, aliases and deduplication,
config errors with empty selections, escaped diagnostics, report-output failures and input
collision protection. Keep the five-field schema minimal; adding fields becomes a compatibility
commitment. Reporting remains in the CLI; Java and build-tool aggregation were not added.
