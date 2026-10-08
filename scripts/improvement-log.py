#!/usr/bin/env python3
"""Prints every improvement-log entry as one markdown table, newest first.

  scripts/improvement-log.py

Reads docs/improvement-log/, one file per entry (its README has the format), and prints the
table the old single-file log kept, with the same columns. A file that does not parse — a
title line, a Change paragraph or a Look-at-again paragraph missing — is an error, so adding
an entry can be checked with a run. Python 3 standard library only."""

import sys
from pathlib import Path

DIRECTORY = Path(__file__).resolve().parent.parent / "docs" / "improvement-log"

CHANGE = "**Change:**"
REVISIT = "**Look at again before a release:**"


def entry(path):
    """(date, pr, change, revisit) from one entry file, or raise ValueError."""
    paragraphs = [p.strip() for p in path.read_text().split("\n\n") if p.strip()]
    if not paragraphs or not paragraphs[0].startswith("# "):
        raise ValueError(f"{path.name}: first line is not a `# <date> — <pr> — <title>` title")
    head = paragraphs[0][2:].split(" — ", 2)
    if len(head) != 3:
        raise ValueError(f"{path.name}: the title needs `# <date> — <pr> — <title>`")
    date, pr, _ = head
    change = revisit = None
    for paragraph in paragraphs[1:]:
        # A paragraph is wrapped across lines; a table cell is one line.
        one_line = " ".join(paragraph.split())
        if one_line.startswith(CHANGE):
            change = one_line[len(CHANGE):].strip()
        elif one_line.startswith(REVISIT):
            revisit = one_line[len(REVISIT):].strip()
    if change is None or revisit is None:
        raise ValueError(f"{path.name}: a {CHANGE} and a {REVISIT} paragraph are both required")
    if date.count("-") != 2 or len(date) != 10:
        raise ValueError(f"{path.name}: {date} is not a YYYY-MM-DD date")
    return date, pr, change, revisit


def entries():
    """Every entry, newest first: by date, then PR number, then file name."""
    found = [
        (date, pr, change, revisit, path.name)
        for path in sorted(DIRECTORY.glob("*.md"))
        if path.name != "README.md"
        for (date, pr, change, revisit) in [entry(path)]
    ]
    found.sort(key=lambda row: row[4])                          # file name
    found.sort(key=lambda row: pr_key(row[1]), reverse=True)    # PR, newest first
    found.sort(key=lambda row: row[0], reverse=True)            # date, newest first
    return found


def pr_key(pr):
    """Sort key for the PR cell: `#62` by number; `—` (no pull request) last."""
    return int(pr.lstrip("#")) if pr.startswith("#") else -1


def main():
    rows = entries()
    print("| Date | PR | Change | Revisit before release |")
    print("|---|---|---|---|")
    for date, pr, change, revisit, _ in rows:
        print(f"| {date} | {pr} | {change} | {revisit} |")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except ValueError as error:
        print(error, file=sys.stderr)
        sys.exit(1)
