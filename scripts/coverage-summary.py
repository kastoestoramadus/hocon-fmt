#!/usr/bin/env python3
"""Per-module statement and branch coverage from the scoverage XML reports, as a markdown
table for the CI step summary. Run after `sbt coverageJvm` from the build root; a module
whose report is missing prints a dash, so a partially failed run still summarises."""

import glob
import xml.etree.ElementTree as ET

MODULES = [
    ("core", "core/jvm/target/scala-*/scoverage-report/scoverage.xml"),
    ("cli", "cli/.jvm/target/scala-*/scoverage-report/scoverage.xml"),
    ("cats", "cats/.jvm/target/scala-*/scoverage-report/scoverage.xml"),
    ("zio", "zio/jvm/target/scala-*/scoverage-report/scoverage.xml"),
    ("aggregate", "target/scala-*/scoverage-report/scoverage.xml"),
]


def load(pattern):
    paths = sorted(glob.glob(pattern))
    return ET.parse(paths[-1]).getroot() if paths else None


def cells(root):
    if root is None:
        return ("—", "—", "—")
    total = sum(1 for s in root.iter("statement") if s.get("branch") == "true")
    invoked = sum(
        1
        for s in root.iter("statement")
        if s.get("branch") == "true" and (s.get("invocation-count") or "0") != "0"
    )
    branch = f"{100.0 * invoked / total:.2f}%" if total else "—"
    return (
        f"{root.get('statements-invoked')}/{root.get('statement-count')}",
        f"{root.get('statement-rate')}%",
        branch,
    )


print("| module | statements | statement coverage | branch coverage |")
print("|---|---|---|---|")
for name, pattern in MODULES:
    print(f"| {name} | {' | '.join(cells(load(pattern)))} |")
