# 2026-10-09 — [#102](https://github.com/kastoestoramadus/hocon-fmt/pull/102) — The #101 review findings

**Change:** Re-owned the two accepted meaning changes (B1/B2) to us and said why: sconfig dropping a
definition a later one overrides is its expected merge behaviour, and the masked-include guard that
misses the include it stopped overriding is ours; `scripts/research-probes/ledger.py` derives `ours`
for a meaning change and `probes.tsv`/`actual.json` follow, refused tallies unchanged. Recorded that a
run failing on duplicates still writes: `--fail-on-duplicates` without `--check` exits 1 with the
changed output already on disk, and B2 has no finding at all. Added the production guard as a Product
recommendation (M, high: a typed refusal when an include precedes a replaced/dead definition on the
same path chain, using the duplicate report's document tree) without implementing it. Corrected the
missing-test inventory (`assertSameMeaning` and per-fixture idempotence already exist; the gap is the
second separator on the approved goldens and examples), the known-bad-ledger entry
(`SconfigDefectsSpec` covers sconfig's defects; ours are missing), the env-override corpus row (626
files with the idiom, 357 refused) and the limitations safety-gap paragraph (a silent meaning change
the formatter writes). Flagged, without editing the profile documents, two debatable semantic-change
tags in data-markup.md.

**Look at again before a release:** The guard this entry only recommends is still absent: a file
whose dead definition stood beside an include is written with changed meaning, and
`--fail-on-duplicates` fails only after that write instead of preventing it. Re-run
`scripts/run-research-probes.sh` and re-check the two B1/B2 probes after any formatter, sconfig or
ledger change; the ownership rule in `ledger.py` is evidence interpretation, not an observation.
