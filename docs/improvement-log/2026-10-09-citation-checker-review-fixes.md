# 2026-10-09 — [#100](https://github.com/kastoestoramadus/hocon-fmt/pull/100) — citation checker review fixes

**Change:** GitHub pull URLs resolve through the issues endpoint, which also serves PRs. The
checker skips and counts URLs in fenced blocks and inline code, treats prose labels as existence
checks, and retains explicit quoted-title and reference checks. Plain URLs keep queries in
requests, deduplication and caches; emphasis wrappers are removed without changing literal
underscores. A GitHub 422 reporting “No commit found” is DEAD; an ordinary 403 is unverifiable.
Confirmed rate limits stop immediately with exit 3 and retry-header advice, including during HEAD
checks. Requests are serial even with the compatibility `--jobs` option. Unauthenticated runs
warn; caches store only definitive answers and invalidate the old format.

**Validation:** canned JSON fixtures reproduce the review findings before the fixes and pass
with them. A fake gh and loopback HTTP transport check request counts, headers, stopping, and
query-specific caches. Live checks of the historical 13 dead citations find 12 genuine wrong-
branch URLs (11 nixfmt, one ormolu); biome #3397 exists as an issue and was a false DEAD.

**Look at again before a release:** rerun the checker on the research refs. Exit 3 is an incomplete
scan: resume after the reported Retry-After or X-RateLimit-Reset deadline. An unquoted paraphrase
claims no title, and a lone backticked identifier remains code rather than a title claim.
