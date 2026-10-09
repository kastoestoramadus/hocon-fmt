# 2026-10-09 — direct push — Rules for working as one of several agents

**Change:** AGENTS.md makes the stack linear (every new PR on the newest open one) and gains a section on parallel
work learned in the 2026-10-08/09 session: a worktree and a private Maven repository per task, merge on green CI
with review findings in a follow-up PR until the first release, reviews that break the code to prove a test bites,
stop conditions in prompts, plans kept in the repository, and a handoff after the first compaction.

**Look at again before a release:** after the first release, merging moves back behind the review — rewrite that
bullet then, and remove the ruleset's admin bypass.
