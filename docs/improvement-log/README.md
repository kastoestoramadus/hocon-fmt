# Improvement log

Changes made under standing approval, newest first. Before a release we go through this list:
what to keep, simplify, optimise or remove. Each entry names the pull request and what to look
at again. `scripts/improvement-log.py` prints every entry as one table in this order; run it to
check a new file parses.

## Format

One file per entry, named `<date>-<pr number>-<slug>.md` — `2026-10-08-62-sbt-plugin-on-the-java-api.md`.
Entries that predate pull requests use the commit's short sha instead of the number. The file holds a
title line and two paragraphs, both required:

```markdown
# <date> — <PR> — <title>

**Change:** what merged, one paragraph.

**Look at again before a release:** the reason to revisit, or `—` when there is none.
```

`<PR>` is `#<number>` or `—`; `<title>` is a few words for the file list and the table's benefit,
since the paragraphs stay verbatim.

## Adding an entry

Add a new file in that form. Never edit another entry's file: an entry records what was known
when it merged, and later knowledge is a new entry, not a rewrite. Then run
`scripts/improvement-log.py` — it fails on a file whose title line or either paragraph is
missing, and its table should show the new entry first.
