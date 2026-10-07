#!/usr/bin/env python3
"""Diff the shipped contributions snapshot against what GitHub reports now.

Run by hand, never in the build: it needs `gh` and it reads the network.
    scripts/refresh-contributions.py

Prints, per repository, the pull requests of kastoestoramadus as the same
search the page makes live: what changed state since the snapshot, what is
new (not in the snapshot), and what the snapshot lists that GitHub no longer
returns (a reason to look, not necessarily an error). For entries worth
adding it prints a ready-made Scala `Contribution(...)` block with the theme
left as `Theme.Project` for the author to place.

States: open stays open; closed with pull_request.merged_at is merged; merged
before the repository's last release counts as released, after it as merged
upstream, unreleased — check the release dates before editing them.
"""

import json
import subprocess
import sys

AUTHOR = "kastoestoramadus"

# Latest releases when the snapshot was written; refresh by hand when either
# repository publishes: everything merged up to these dates is "released".
RELEASES = {
    "ekrich/sconfig": ("v2.0.0", "2026-08-24"),
    "lightbend/config": ("v1.4.9", "2026-06-03"),
}

# number -> (title, state) exactly as shipped in Contributions.scala; the
# refresh script keeps its own list here so a diff needs no Scala parsing.
SNAPSHOT = {
    "ekrich/sconfig": {
        438: ("Making rendering useful for formatting files.", "closed"),
        598: ("Make the renderer's output parseable and a fixed point", "merged-unreleased"),
        599: ("Let a partially resolved value that cannot become an object hide the merge stack below it", "open"),
        600: ("Write merge stack entries with the render options' key and separator", "open"),
        590: ("Port lightbend/config#832 and #841: two rendering round-trip fixes (#2)", "merged-unreleased"),
        613: ("Drop Option and Vector from the renderer code", "merged-unreleased"),
        497: ("[496] bugfix rendering for multipaths in arrays", "released"),
        522: ("Fix of broken single path optimization on NonRoot.", "released"),
        523: ("Fix and refine previous rendering fix", "released"),
        516: ("Broken String concat at rendering bugfix", "released"),
        466: ("[ISSUE-465] Bugfix for unquoted Strings, exception with '.' kept", "released"),
        467: ("Additional space after assign - rendering improvement", "released"),
        468: ("Compact single nested object entry - rendering improvement", "released"),
        601: ("Attach the comment above a `+=` field to the field, not also to its element", "open"),
        515: ("fix of deleted comments when compacted multipath is turned on", "released"),
        525: ("when comments = no multipath rendering optimization", "released"),
        472: ("[BUGFIX] surplus spaces in array comments", "released"),
        595: ("Port lightbend/config#839+#846: don't evaluate substitutions hidden by values from resolved objects", "merged-unreleased"),
        596: ("Port lightbend/config#725: route replaceChild through a held ConfigConcatenation's pieces", "merged-unreleased"),
        620: ("Keep object merge history at the source key", "open"),
        621: ("Fix resolveWith() for overloaded keys with delayed merge", "open"),
        636: ("Limit parser nesting and wrap resolver stack overflow", "open"),
        635: ("Reject out-of-range long conversions and preserve exact boundaries", "open"),
        634: ("Render non-finite doubles as quoted strings", "open"),
        640: ("Reject signs in \\uXXXX escape hex digits", "open"),
        641: ("Fix stale line numbers in parser errors after multiline strings (#625)", "open"),
        616: ("fix: wrong line number for objects after multiline string", "open"),
        614: ("fix: origin line numbers after newline separators", "open"),
        605: ("List expansion from environment variables", "merged-unreleased"),
        604: ("Port lightbend/config#620+#686: override config with CONFIG_FORCE_* env vars", "merged-unreleased"),
        603: ("Port lightbend/config#619: let application.conf override reference.conf substitutions", "merged-unreleased"),
        602: ("Port lightbend/config#708+#709: ConfigFactory.parseApplicationReplacement", "merged-unreleased"),
        618: ("Port lightbend/config#848: make SimpleConfigObject.keySet unmodifiable", "merged-unreleased"),
        615: ("feat: opt-in unknown key validation for ConfigBeanFactory", "open"),
        610: ("Reject text after arrays and objects", "open"),
        469: ("Porting from Lightbend: Support for huge memory units #663", "released"),
        458: ("[ISSUE-455] showEnvVariableValues code ported, one bugfix", "released"),
        642: ("Add property tests for parser bounds and renderer round-trip", "open"),
        622: ("Run uncached full test suites in CI", "merged-unreleased"),
        617: ("Run the MiMa binary compatibility check in CI", "closed"),
        609: ("Add AGENTS.md, a porting guide, and a porting skill", "open"),
    },
    "lightbend/config": {
        815: ("Make formatting possible", "closed"),
        868: ("Keep unresolved merge rendering stable across round trips", "open"),
        869: ("Collapse partially resolved merge stacks that cannot become objects", "open"),
        870: ("Respect existing render options in unresolved merge entries", "open"),
        871: ("Keep comments above += fields off synthetic list elements", "merged-unreleased"),
        867: ("Keep list comment rendering stable across round trips", "merged-unreleased"),
        878: ("Fix BugOrBroken resolving a delayed merge inside a concatenated list piece (#751)", "open"),
        879: ("fix: look back in delayed merges for self-referential fields inside pieces", "open"),
        880: ("fix: resolve substitutions that pass through object concatenations", "open"),
        881: ("Fix BugOrBroken in resolveWith() when a delayed merge is not under the lookup root (#855, #332, #664)", "open"),
        882: ("fix: += must append to values inherited through object concatenation", "open"),
        874: ("Limit parser collection nesting and wrap resolver stack overflow", "open"),
        873: ("Reject out-of-range long conversions and preserve exact boundaries", "open"),
        872: ("Render non-finite doubles as quoted strings", "open"),
        875: ("Hide CONFIG_FORCE override values when rendering environment values is disabled", "open"),
        876: ("Load environment overrides lazily and avoid poisoning the cache holder", "open"),
        866: ("Reuse patterns when parsing duration and memory size values", "merged-unreleased"),
    },
}


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

    for repo, (release_tag, release_date) in RELEASES.items():
        print(f"=== {repo} (last release {release_tag}, {release_date})")
        query = f"author:{AUTHOR}+type:pr+repo:{repo}"
        raw = subprocess.run(
            ["gh", "api", f"search/issues?q={query}&per_page=100"],
            check=True, capture_output=True, text=True,
        ).stdout
        items = json.loads(raw)["items"]
        live = {item["number"]: item for item in items}

        snapshot = SNAPSHOT[repo]
        changed = new = gone = 0
        for number, item in sorted(live.items()):
            state = state_of(item, release_date)
            if number not in snapshot:
                new += 1
                print(f"  NEW  #{number} [{state}] {item['title']}")
                print("  " + scala_block(repo, item, state).replace("\n", "\n  "))
            elif snapshot[number][1] != state:
                changed += 1
                print(f"  STATE #{number}: snapshot {snapshot[number][1]} -> live {state}")
        for number, (title, state) in sorted(snapshot.items()):
            if number not in live:
                gone += 1
                print(f"  GONE #{number} [{state}] {title}")
        summary = f"  {len(live)} live, {changed} state changes, {new} new, {gone} not returned"
        print(summary if changed + new + gone else summary + " — snapshot is current")


if __name__ == "__main__":
    main()
