# 2026-10-09 — [#98](https://github.com/kastoestoramadus/hocon-fmt/pull/98) — a citation checker for the formatter research

**Change:** `scripts/verify-research-urls.py` checks every markdown link and bare URL in the
formatter-research files: GitHub issues, pulls and commits resolve through `gh api` (unauthenticated
`api.github.com` without gh), other URLs through a HEAD/GET status check, and the link's text, the
reference number it names or the quoted title right after it is compared with the real title (case,
whitespace, quotes and a trailing `[#n]` normalised, `…` read as a cut). It reports MISMATCH, DEAD,
RETRY and unverifiable, exits 1 on a mismatch or a dead link, caches answers under `--cache`, and
`--json` prints the whole run. `scripts/verify-research-urls-test.sh` pins it against canned JSON
with no network, and CI's `release-guards` job runs that suite. First real run over the research
files: 1634 citations, 0 mismatches, 13 dead (a non-existent biome PR; ormolu and nixfmt links on
the wrong branch name).

**Look at again before a release:** the checker runs by hand — nothing in a release pipeline calls
it, only its test is wired into CI — and a quoted fragment counts as a match unless an ellipsis
marks the cut, a rule a future corpus of paraphrases would have to tighten.
