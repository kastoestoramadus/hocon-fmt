# 2026-10-08 — [#60](https://github.com/kastoestoramadus/hocon-fmt/pull/60) — The CLI reports the keys a later definition replaces

**Change:** The CLI reports the keys a later definition replaces (`DuplicateReport` in core, from
the syntax-preserving document parse of the text): a warning per finding,
`--fail-on-duplicates`/`--no-fail-on-duplicates` and the same key in `.hocon-fmt.conf` for the exit
code, no change to what is written. The corpus sweep: 49 of 1,650 GitHub files and 49 of HMRC's
1,168 have a finding; of the files whose repeated paths are the env-override idiom, 599/626 and
944/990 are left unflagged.

**Look at again before a release:** The report reads sconfig's `SimpleConfigDocument.configNodeTree`
because no traversal API is public
([lightbend/config#300](https://github.com/lightbend/config/issues/300)): check it when sconfig
moves, and drop the match for a published tree. The plugins do not report yet — `JvmFacade` has no
report, so this lands in the CLI only. A finding is a warning by design; if a team wants it to fail
a build without `--fail-on-duplicates`, the plugins need the same key.
