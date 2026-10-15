# 2026-10-15 — Bound the duplicate report's total work

**Change:** The early scan refuses more than 300,000 squared flattened definition-length units,
including implicit dotted objects, array element levels and every concatenation piece. The
existing `path-too-deep` kind names the budget in its reason. The limit is 11.23 times the
available corpus maximum of 26,725; formatting verdicts, output and ordered duplicate findings
match the baseline across 1,258 corpus entries and 692 tracked repository `.conf` entries,
including their existing unreadable fixtures.

A budget alone did not bound repeated short paths: 30,000 copies of `x = 1` cost 30,000 units
but took 10.06 s. A reverse index now keeps the nearest eligible later replacement instead of
enumerating every twin. The original algorithm remains a test oracle, compared on 1,000
generated sources per platform; deliberately removing object-valued replacements makes it fail.

**Look at again before a release:** Re-measure before changing the limit. The 30-shape historical
fit favoured squares over cubes (R² .99258 vs .98200), but it does not predict overhead for many
short fields. A fresh 130-shape search reached 1.14 s warm JVM report time at 300,000 distinct
root fields (3.49 MB); a separate run measured 2.30 s cold and 0.70–1.04 s warm. JVM CLI time was
4.61–7.67 s, Native CLI 13.76–16.09 s under 8 GiB/no-swap, peaking at 5.68 GiB RSS. These include
formatting and startup, not just the report. Input byte length remains relevant. A single
96-segment dotted path passes the budget; 97 segments do not. The Scala refusal's payload is now
the reason string rather than an integer limit; external refusal kinds stay the same.
