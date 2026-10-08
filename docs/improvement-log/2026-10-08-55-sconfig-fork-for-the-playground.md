# 2026-10-08 — #55 — The playground runs on a sconfig fork that keeps comments

**Change:** The project page's playground runs on a sconfig fork that keeps comments released
sconfig drops (`setKeepDetachedComments`, ekrich/sconfig#646/#647): `scripts/fetch-sconfig-fork.sh`
publishes it, `coreSite` is the core against it, and `CommentCarrier` (a no-op in the published
core, the real masking only in `coreSite`) restores the comments no field follows, matching the `=`
and `:` spellings and stepping the prefix aside for user text the parse could spell into it (a
render it cannot tell apart is refused, never altered; the parsed tree is judged the way includes'
is). 1,650-file corpus: 326 of 1,650 files format with released sconfig, 1,412 on the page, none
regress; the page says it runs a development build. Scala.js moves to 1.22.0 for the whole build.

**Look at again before a release:** Everything fork-only carries `UPSTREAM-SCONFIG:`; `docs/site.md`
"Returning to upstream sconfig" is the revert, and `KeepDetachedCommentsGuardSpec` turning green is
the signal. Recheck the 85 corpus files still refused (array tails, merge-heavy files) and the fork
sha when the option releases; the fork base also changes merge renderings, which the ledger in
`Variant` lists.
