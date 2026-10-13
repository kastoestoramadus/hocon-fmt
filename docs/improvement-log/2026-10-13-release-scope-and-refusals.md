# Release scope and refusals on the project page

**Decision (owner, 2026-10-13):** v0.1.0 covers the JVM core and the sbt 1.x, Gradle and Maven
plugins. Native binaries, npm, PyPI, Mill and sbt 2 plugins follow later. The current refusal
list ships without a hand review; the second release tightens it. Refusal never writes or
breaks a file. The page, README, limitations, release runbook and first-release plan state
this scope. The workflow still needs Maven and Gradle publishing aligned before tagging.

The page gives a moved-include example and explains “Left unchanged”. The quick start now
runs the JVM CLI from a checkout. Known limits describes preserved leading headers and
remaining detached-comment refusals. The usage panel no longer implies every channel ships
in v0.1.0. No origin, speed or comparison claims were added.

## Measurement quoted

The measured source is work/codex-jobs/g1/raw/corpus-compare.txt under
/home/walidus/hocon-fmt-analysis-2026-10-11, from the 2026-10-13 JVM CLI refresh at c94d49e:

```text
before: 1257 files; after: 1257 files
refusals before: 310, after: 310
accepted before: 947, after: 947
files whose accepted/refused class changed: 0
```

Counting the raw work/hp-verdicts-after3.tsv rows independently gives 869 would-change,
78 already formatted and 310 refused, total 1,257. The displayed 24.7% is
310 / 1,257 × 100 rounded to one decimal. Method: one JVM CLI `--check` per file against
24 public repositories at pinned commits, as recorded in work/page-draft/refusals-refresh.md.
The corpus includes parser test fixtures and non-HOCON .conf files; this is not a production
prevalence estimate. The counts are not a new measurement at this PR's base bbf1cf8, nor a
measurement of the fork-backed playground.

## Stale-text verification

- `build.sbt` selects released sconfig 2.0.0 for the default core and
  2.0.0-hocon-fmt-efb66e0131 for coreSite. The fork ledger's rendering reason and the current
  build comparison in `docs/site.md` now name 2.0.0 rather than 1.12.4. Existing merge fixtures
  still distinguish the default core's refusal from the fork's formatting.
- `javap` on sconfig_3-2.0.0.jar confirms ConfigDocument exposes editing and render methods,
  with configNodeTree on SimpleConfigDocument only. `docs/ideas.md` retains the traversal-API
  caveat and updates the current version to 2.0.0. Its detached-comment idea now distinguishes
  preserved leading headers from remaining refusals.
- The first-release plan's current dependency blocker now says 2.0.0 and records the owner's
  refusal decision. The dated 2026-10-09 wave plan stays as history, marked superseded.
- The fork's pinned identity, its detached-comment option, upstream history and historical
  measurements are left intact.

**Validation:** the existing component assertions failed before implementation (26 total,
2 failed, 24 passed), naming the missing sections and obsolete quick start. Final checks passed:
`sbt scalafmtAll scalafmtSbt`, `sbt site/build` (Closure: 0 errors, 0 warnings),
`sbt site/test` (89 passed), and the existing example/include-order suites on coreJVM
(48 passed) and coreSite (37 passed). All ran in one sbt JVM with -Xmx3g and a private
Maven repo. The fork artifact was already published locally; no fetch was requested.

**Look at again before a release:** align Maven and Gradle publishing with the owner's scope;
refresh the dated JVM corpus measurement if it is to describe a newer core, and keep it
separate from the playground. Review refusals by hand for the second release.
