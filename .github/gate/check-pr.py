#!/usr/bin/env python3
"""Server-side PR convention check (task 1717, prepared; the owner installs it per repo).

Reads the JSON of `gh pr view <n> --json body,commits` from argv[1] and the conventions
file from argv[2] (same file bin/gh-write and the git hooks read). Refuses, exit 1 with one
line per problem: a missing or malformed PR footer block, a forbidden line in the body (bare
Claude Code / robot footers) and a Co-Authored-By trailer (in the body or a commit) naming
nobody on the `human_coauthors` allowlist. The footer block is what bin/gh-write builds: a
`Generated with {model}/{effort} through {assistant}` line, with only the conventions'
efforts and assistant names, optionally followed by `Reviewed by` / `Revised by` lines.
Missing **TLDR:** and a commit without `Helped by:` are printed as notes, not refusals,
because the owner's own commits and small PRs carry none. `gh pr view --json commits` names
no parents (measured 2026-11-15), so no commit is exempt for a `Merge ` subject: a real
merge commit only earns the `Helped by:` note, never a refusal."""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any

COAUTHOR_TRAILER = re.compile(r"\s*co-authored-by:[ \t]*(.+?)\s*", re.I)


def load_conventions(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))  # the maintained JSON file is the boundary


def template_pattern(data: Any, template: str) -> re.Pattern[str]:
    """A footer template as a whole-line pattern: any model id, only the conventions'
    efforts and assistant names - the same lists bin/gh-write reads (review finding 11)."""
    marks = {"model": "QQMODELQQ", "effort": "QQEFFORTQQ", "assistant": "QQASSISTANTQQ"}
    pattern = re.escape(template.format(**marks))
    pattern = pattern.replace(marks["model"], r"[^/\s]+")
    pattern = pattern.replace(marks["effort"], "(?:" + "|".join(map(re.escape, data["efforts"])) + ")")
    return re.compile(pattern.replace(marks["assistant"],
                                      "(?:" + "|".join(map(re.escape, data["assistants"].values())) + ")"))


def unallowed_trailer(data: Any, text: str) -> str | None:
    """The first whole Co-Authored-By line of the text naming nobody on the human allowlist."""
    humans = [str(h).lower() for h in data.get("human_coauthors", [])]
    for line in text.splitlines():
        m = COAUTHOR_TRAILER.fullmatch(line)
        if m is not None and not any(h in m.group(1).lower() for h in humans):
            return line.strip()
    return None


def problems(pr: dict[str, Any], conventions_path: Path) -> list[str]:
    data = load_conventions(conventions_path)
    generated = template_pattern(data, str(data["pr_footer_template"]))
    reviewed = template_pattern(data, str(data["reviewed_by_template"]))
    revised = template_pattern(data, str(data["revised_by_template"]))
    forbidden_lines = [re.compile(p, re.I) for p in data["forbidden"]["lines"]]
    forbidden_trailers = [re.compile(p, re.I) for p in data["forbidden"]["trailers"]]
    verdicts: list[str] = []
    body_lines = [line.strip() for line in str(pr.get("body", "")).splitlines() if line.strip()]
    block = list(body_lines)  # the footer block gh-write builds may end in Reviewed/Revised lines
    while block and (reviewed.fullmatch(block[-1]) or revised.fullmatch(block[-1])):
        block.pop()
    if not block or generated.fullmatch(block[-1]) is None:
        verdicts.append(
            f"refused: the PR body footer is missing or malformed; the body must end with "
            f"`{data['pr_footer_template']}` (bin/gh-write appends it; `gh pr edit` alone does not)"
        )
    for pattern in forbidden_lines:
        for line in body_lines:
            if pattern.search(line):
                verdicts.append(f"refused: forbidden footer line in body: {line.strip()!r} ({pattern.pattern})")
    for pattern in forbidden_trailers:
        if pattern.search(str(pr.get("body", ""))):
            verdicts.append(f"refused: AI Co-Authored-By trailer in the PR body ({pattern.pattern})")
    trailer = unallowed_trailer(data, str(pr.get("body", "")))
    if trailer is not None:
        verdicts.append(f"refused: a Co-Authored-By trailer not on the human allowlist in the PR body: {trailer!r}")
    if not any(line.startswith(str(data["tldr_prefix"])) for line in body_lines):
        verdicts.append("note: the body does not open with **TLDR:** (owner rule for PR bodies)")
    for commit in pr.get("commits", []):
        oid = str(commit.get("oid", "?"))[:8]
        message = f"{commit.get('messageHeadline', '')}\n{commit.get('messageBody', '')}"
        commit_trailer = unallowed_trailer(data, message)
        if commit_trailer is not None or any(pattern.search(message) for pattern in forbidden_trailers):
            verdicts.append(f"refused: commit {oid} carries an AI Co-Authored-By trailer "
                            f"({commit_trailer or 'pattern match'})")
        if "Helped by:" not in message and "AI assistance:" not in message:
            verdicts.append(f"note: commit {oid} has no `Helped by:` line (owner convention)")
    return verdicts


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("usage: check-pr.py <pr-view.json> [conventions.json]", file=sys.stderr)
        return 2
    conventions = Path(argv[2]) if len(argv) > 2 else Path(__file__).parent / "github.json"
    pr = json.loads(Path(argv[1]).read_text(encoding="utf-8"))  # gh pr view JSON is the boundary
    verdicts = problems(pr, conventions)
    for verdict in verdicts:
        print(verdict)
    return 1 if any(v.startswith("refused") for v in verdicts) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
