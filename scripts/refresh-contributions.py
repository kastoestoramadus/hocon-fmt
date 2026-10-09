#!/usr/bin/env python3
"""Diff the shipped contributions snapshot against what GitHub reports now.

Run by hand, never in the build: it needs `gh` and it reads the network.
    scripts/refresh-contributions.py

The snapshot is read out of `site/.../site/Contributions.scala` — the one
place it lives, since a second copy here drifted from it. It prints, per
repository, the pull requests of kastoestoramadus as the same search the
page makes live: what changed state since the snapshot, what is new (not in
the snapshot), and what the snapshot lists that GitHub no longer returns (a
reason to look, not necessarily an error). For entries worth adding it
prints a ready-made Scala `Contribution(...)` block with the theme left as
`Theme.Project` for the author to place.

States: open stays open; closed with pull_request.merged_at is merged;
merged before the repository's last release counts as released, after it as
merged upstream, unreleased — check the release dates below before editing
them, and move `Contributions.readOn` forward when you re-read the data.
"""

import json
import re
import subprocess
import sys
from pathlib import Path

AUTHOR = "kastoestoramadus"

# Latest releases when the snapshot was written; refresh by hand when either
# repository publishes: everything merged up to these dates is "released".
RELEASES = {
    "ekrich/sconfig": ("v2.0.0", "2026-08-24"),
    "lightbend/config": ("v1.4.9", "2026-06-03"),
}

SNAPSHOT_FILE = Path(__file__).resolve().parent.parent / "site/src/main/scala/ww86/hoconfmt/site/Contributions.scala"
REPO_ROOT = Path(__file__).resolve().parent.parent

LIBRARY = {"Sconfig": "ekrich/sconfig", "LightbendConfig": "lightbend/config"}
STATE = {
    "Open": "open",
    "MergedUnreleased": "merged-unreleased",
    "Released": "released",
    "Closed": "closed",
}

# Library, number, title and state of one entry; the theme and the note are
# not needed for a diff. Anchored on the entry's own fields, so a note —
# whose prose may hold anything, parentheses included — cannot match.
ENTRY = re.compile(
    r'Contribution\(\s*(\w+),\s*(\d+),\s*"((?:[^"\\]|\\.)*)",\s*Theme\.\w+,\s*PrState\.(\w+),',
    re.S,
)
ESCAPE = re.compile(r"\\(.)")


def unescape(text):
    """The title as the page means it: Scala escapes are Python's for the few that appear."""
    return ESCAPE.sub(lambda m: {"\\": "\\", '"': '"', "n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), text)


def read_snapshot():
    """number -> (title, state) per repository, out of the Scala source."""
    source = SNAPSHOT_FILE.read_text(encoding="utf-8")
    entries = {}
    for match in ENTRY.finditer(source):
        entries[(LIBRARY[match.group(1)], int(match.group(2)))] = (
            unescape(match.group(3)),
            STATE[match.group(4)],
        )
    written = source.count("Contribution(")
    if len(entries) != written:
        sys.exit(f"read {len(entries)} of the {written} entries in {SNAPSHOT_FILE}; the ENTRY pattern needs fixing")
    per_repo = {repo: {n: entry for (library, n), entry in entries.items() if library == repo} for repo in RELEASES}
    print(f"snapshot: {len(entries)} entries in {SNAPSHOT_FILE.relative_to(REPO_ROOT)}")
    return per_repo


def state_of(item, release_date):
    if item.get("pull_request", {}).get("merged_at"):
        merged_on = item["pull_request"]["merged_at"][:10]
        return "released" if merged_on <= release_date else "merged-unreleased"
    return "closed" if item["state"] == "closed" else "open"


def scala_block(repo, item, state):
    owner, name = repo.split("/")
    library = "Sconfig" if name == "sconfig" else "LightbendConfig"
    title = json.dumps(item["title"])
    return f"""    Contribution(
      Library.{library}, {item['number']}, {title},
      Theme.Project, PrState.{ "Released" if state == "released" else "MergedUnreleased" if state == "merged-unreleased" else "Open" if state == "open" else "Closed" },
      "<one sentence, in domain terms, on what was wrong and what the PR fixes.>"
    ),"""


def main():
    try:
        subprocess.run(["gh", "--version"], check=True, capture_output=True)
    except FileNotFoundError:
        sys.exit("gh is not on the PATH; this script reads GitHub through it")

    snapshot = read_snapshot()
    stale = False
    for repo, (release_tag, release_date) in RELEASES.items():
        print(f"=== {repo} (last release {release_tag}, {release_date})")
        query = f"author:{AUTHOR}+type:pr+repo:{repo}"
        raw = subprocess.run(
            ["gh", "api", f"search/issues?q={query}&per_page=100"],
            check=True, capture_output=True, text=True,
        ).stdout
        items = json.loads(raw)["items"]
        live = {item["number"]: item for item in items}

        shipped = snapshot[repo]
        changed = new = gone = 0
        for number, item in sorted(live.items()):
            state = state_of(item, release_date)
            if number not in shipped:
                new += 1
                print(f"  NEW  #{number} [{state}] {item['title']}")
                print("  " + scala_block(repo, item, state).replace("\n", "\n  "))
            elif shipped[number][1] != state:
                changed += 1
                print(f"  STATE #{number}: snapshot {shipped[number][1]} -> live {state}")
        for number, (title, state) in sorted(snapshot[repo].items()):
            if number not in live:
                gone += 1
                print(f"  GONE #{number} [{state}] {title}")
        summary = f"  {len(live)} live, {changed} state changes, {new} new, {gone} not returned"
        print(summary if changed + new + gone else summary + " — snapshot is current")
        stale = stale or bool(changed + new + gone)

    if stale:
        print("\nSet Contributions.readOn to the day you re-read the data.")


if __name__ == "__main__":
    main()
