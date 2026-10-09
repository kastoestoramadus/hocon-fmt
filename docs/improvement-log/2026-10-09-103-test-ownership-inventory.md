# 2026-10-09 — #103 — Test ownership and patch inventory

**Change:** `docs/test-ownership.md` classifies every suite (and each group of the big ones) as ours,
sconfig's or mixed with executed case counts and evidence from bare-sconfig probes and a run against
sconfig main `f351262a`; it ranks the cases worth porting to `ekrich/sconfig`, lists the red ledger
with the release that retires each entry, and inventories every patch carried for sconfig with its
cost and removal steps; `AGENTS.md` gains the policy and `docs/testing.md` links the document.

**Look at again before a release:** the red list is the release checklist — a release containing
`#598`/`#600`/`#605`/`#647` turns listed cases green, and the refusal, the `limitations.md` entry
and the examples' `upstream` notes go with them. Re-run the ledger check (`sconfigJVM/publishLocal`
of sconfig main, then `coreJVM/testOnly …SconfigDefectsSpec …KeepDetachedCommentsGuardSpec`) and
update the tables; the porting backlog (the normalised and supported groups first) is untracked
work, one upstream PR each.
