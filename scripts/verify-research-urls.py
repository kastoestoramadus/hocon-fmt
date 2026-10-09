#!/usr/bin/env python3
"""Checks the GitHub citations in the formatter research against GitHub itself.

  scripts/verify-research-urls.py [--json] [--cache DIR] [--jobs N] FILE.md [FILE.md ...]

Every `[text](https://…)` link and every bare URL in the given files is checked:

  * a github.com issue, pull or commit URL is resolved through `gh api` (unauthenticated
    api.github.com when gh is not installed) and its title is compared with the link's text, or
    with a quoted title right after the link — `[#149](…) "Preserve comments across newlines?"`;
  * any other URL gets a HEAD status check (GET after a 403/405/501).

Exit status 1 when a citation is DEAD (404/410) or its title MISMATCHes; 0 otherwise, including a
run that could check nothing: a rate limit is RETRY, an unreachable URL is unverifiable, and the
summary counts both as "unverifiable (text only)". Answers are cached under --cache, so a rerun
that only edits the prose costs nothing.

The network is replaced by canned JSON when VERIFY_RESEARCH_URLS_STUB names a directory holding
a manifest.json ({url: file name}); scripts/verify-research-urls-test.sh uses it. Python 3
standard library only; meant to run under `python3 -I`.
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

STUB_VARIABLE = "VERIFY_RESEARCH_URLS_STUB"
USER_AGENT = "hocon-fmt-verify-research-urls"
GITHUB_API = "https://api.github.com"
MAX_ATTEMPTS = 3
CACHE_TTL = 86400

# `issues/7#issuecomment-1` and `?plain=1` are the same API resource as the URL without them.
GH_LINK = re.compile(
    r"https?://github\.com/(?P<owner>[^/?#]+)/(?P<repo>[^/?#]+)/"
    r"(?P<kind>issues|pull|commit)/(?P<ref>[^/?#]+)"
)
# The link text may hold no brackets of its own: a `[` inside a code span would otherwise pair
# with the next real link's `]` and swallow it, and CommonMark pairs the innermost brackets too.
MARKDOWN_LINK = re.compile(r"\[(?P<text>[^\[\]]*)\]\((?P<url>https?://[^)\s]+)\)")
URL = re.compile(r"https?://[^\s<>\"'`]+")
REFERENCE = re.compile(r"^(?:[A-Za-z0-9_.\-/]+)?#(?P<number>\d+)$")
PULL_NUMBER = re.compile(r"^PR\s*#?(?P<number>\d+)$", re.IGNORECASE)
SHA = re.compile(r"^[0-9a-f]{7,40}$", re.IGNORECASE)
# Between a link and its quoted title there is punctuation at most: `[#149](…) "Title"`,
# `[b6989a2](…) — "skip errors while formatting"`.
QUOTE_SKIP = " \t\r\n—–−-:;,.*|"
# A link text that names the reference rather than a title; it is not a title claim.
LABELS = {
    "commit", "commits", "diff", "here", "issue", "issues", "link", "patch",
    "pr", "prs", "pull request", "source", "the commit", "the issue", "the pr",
    "this commit", "this issue", "this pr",
}


class Link:
    """One citation in one file: its URL, the markdown text and the position of each."""

    __slots__ = ("url", "text", "line", "end")

    def __init__(self, url, text, line, end):
        self.url = url
        self.text = text
        self.line = line
        # Offset just after the link, where a quoted title right after it starts.
        self.end = end


class Fetched:
    """What the network (or the stub) said about one URL."""

    __slots__ = ("status", "title", "state", "http_status", "reason", "cached")

    def __init__(self, status, title=None, state=None, http_status=None, reason="", cached=False):
        self.status = status
        self.title = title
        self.state = state
        self.http_status = http_status
        self.reason = reason
        self.cached = cached


class Checked:
    """One evaluated citation: the fetched answer plus what the text claimed."""

    __slots__ = ("link", "file", "status", "claimed", "actual", "fetched")

    def __init__(self, link, file, status, claimed="", actual="", fetched=None):
        self.link = link
        self.file = file
        self.status = status
        self.claimed = claimed
        self.actual = actual
        self.fetched = fetched


def line_of(text, offset):
    """The 1-based line number of an offset in text."""
    return text.count("\n", 0, offset) + 1


def clean_bare(url):
    """A bare URL without the sentence punctuation that followed it."""
    url = url.rstrip(".,;:!?")
    while url.endswith(")") and url.count("(") < url.count(")"):
        url = url[:-1]
    return url


def collect_links(path):
    """Every markdown link and bare URL in the file, in order."""
    text = Path(path).read_text(encoding="utf-8")
    spans = []
    for match in MARKDOWN_LINK.finditer(text):
        spans.append(match.span())
        yield Link(match.group("url"), match.group("text"), line_of(text, match.start()), match.end())
    for match in URL.finditer(text):
        # A URL inside a markdown link is that link's target or text, not a citation of its own.
        if any(start <= match.start() < end for start, end in spans):
            continue
        url = clean_bare(match.group(0))
        if url:
            yield Link(url, "", line_of(text, match.start()), match.end())


def fetch_url(url):
    """The URL without the fragment and query the API resource does not need."""
    return url.split("#", 1)[0].split("?", 1)[0]


def github_parts(url):
    """(owner, repo, kind, ref) for an issue, pull or commit URL, or None."""
    match = GH_LINK.match(fetch_url(url))
    if not match:
        return None
    kind = match.group("kind")
    return match.group("owner"), match.group("repo"), kind, match.group("ref")


def reference_number(text):
    """The issue number a link text names — `#149`, `llvm#46933`, `PR 26455` — or None."""
    stripped = text.strip().strip("`").strip()
    match = REFERENCE.match(stripped) or PULL_NUMBER.match(stripped)
    return int(match.group("number")) if match else None


def sha_text(text):
    """The commit sha a link text names, or None."""
    stripped = text.strip().strip("`").strip()
    return stripped if SHA.match(stripped) else None


def quoted_after(text, offset):
    """The quoted title right after a link, or None.

    Only whitespace and punctuation may stand between the link and the quote, so a quote that
    belongs to the running prose is not mistaken for a title. The quote may wrap a line, and a
    `\\"` inside a double-quoted title is an escaped quote, not the end of it.
    """
    index = offset
    skipped = 0
    while index < len(text) and text[index] in QUOTE_SKIP and skipped < 200:
        index += 1
        skipped += 1
    if index >= len(text):
        return None
    opening = text[index]
    # Only a double quote marks a title: a backticked word after a link is code, and reading it
    # as the claim flags ``[#798](…) — `setShowEnvVariableValues` hides secrets`` as a mismatch.
    closing = {'"': '"', "“": "”"}.get(opening)
    if closing is None:
        return None
    index += 1
    out = []
    while index < len(text):
        char = text[index]
        if char == "\\" and index + 1 < len(text) and text[index + 1] in ('"', "\\"):
            out.append(text[index + 1])
            index += 2
            continue
        if char == closing:
            claim = "".join(out).strip()
            return claim if 0 < len(claim) <= 400 else None
        out.append(char)
        index += 1
    return None


def normalise(text):
    """Comparison form of a title: case, whitespace, quotes and a trailing `[#n]` removed."""
    text = re.sub(r"[`\"“”‘’']", "", text)
    text = text.replace("—", "-").replace("–", "-")
    text = re.sub(r"\s+", " ", text).strip()
    text = re.sub(r"\s*[\[(]#\d+[\])]\s*$", "", text).strip()
    return text.rstrip(".").strip().casefold()


def title_matches(claim, title):
    """Whether a claim matches the title: equal, a fragment of it, or ellipsis-marked pieces.

    The research quotes a title partly when only part of it carries the lesson, marking the cut
    with an ellipsis (`"…comments and parentheses"`); a claim without one must still be a
    fragment of the title to count, which is what a shortened quote looks like.
    """
    claimed = normalise(claim)
    real = normalise(title)
    if not claimed or not real:
        return False
    if claimed == real:
        return True
    if "…" in claimed or "..." in claimed:
        pieces = [piece.strip() for piece in re.split(r"…|\.\.\.", claimed) if piece.strip()]
        if not pieces:
            return False
        position = 0
        for piece in pieces:
            found = real.find(piece, position)
            if found < 0:
                return False
            position = found + len(piece)
        return True
    return len(claimed) >= 8 and claimed in real


def gh_success(kind, data):
    """The title and state from a successful gh api response."""
    if kind == "commit":
        message = data.get("commit", {}).get("message", "")
        return Fetched("ok", title=message.splitlines()[0] if message else None)
    return Fetched("ok", title=data.get("title"), state=data.get("state"), http_status=200)


def status_result(code, body="", api=False):
    """Map an HTTP status onto a fetch outcome."""
    if code in (404, 410):
        return Fetched("dead", http_status=code, reason=f"HTTP {code}")
    if 200 <= code < 400:
        return Fetched("ok", http_status=code)
    if code == 429 or (api and code == 403 and "rate limit" in body.lower()):
        return Fetched("retry", http_status=code, reason=f"rate limited (HTTP {code})")
    return Fetched("unverifiable", http_status=code, reason=f"HTTP {code}")


def api_request(url, timeout):
    """GET a JSON API resource; the HTTPError is returned, not raised."""
    request = urllib.request.Request(
        url, headers={"User-Agent": USER_AGENT, "Accept": "application/vnd.github+json"}
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.load(response), None
    except urllib.error.HTTPError as error:
        return None, error
    except (urllib.error.URLError, OSError, ValueError) as error:
        return None, error


def github_fetch(owner, repo, kind, ref, args):
    """The title of one GitHub resource, through gh when it is there, else api.github.com."""
    endpoint = {"pull": "pulls", "commit": "commits"}.get(kind, "issues")
    path = f"repos/{owner}/{repo}/{endpoint}/{ref}"
    if shutil.which("gh"):
        try:
            done = subprocess.run(
                ["gh", "api", path], capture_output=True, text=True, timeout=args.timeout
            )
        except (subprocess.SubprocessError, OSError):
            done = None
        if done is not None and done.returncode == 0:
            try:
                return gh_success(kind, json.loads(done.stdout))
            except (ValueError, AttributeError):
                return Fetched("unverifiable", reason="gh printed no JSON")
        if done is not None:
            message = (done.stderr or done.stdout).strip()
            if "rate limit" in message.lower() or "HTTP 403" in message or "HTTP 429" in message:
                return Fetched("retry", reason=message.splitlines()[0] if message else "rate limited")
            if "HTTP 404" in message or "HTTP 410" in message:
                return Fetched("dead", reason=f"HTTP {message.rsplit('HTTP ', 1)[-1].rstrip(')')}")
    data, error = api_request(f"{GITHUB_API}/{path}", args.timeout)
    if data is not None:
        return gh_success(kind, data)
    if isinstance(error, urllib.error.HTTPError):
        body = error.read(4096).decode(errors="replace")
        return status_result(error.code, body, api=True)
    return Fetched("unverifiable", reason=f"request failed: {error}")


def plain_fetch(url, args):
    """A HEAD status check for anything that is not a GitHub issue, pull or commit."""
    request = urllib.request.Request(url, method="HEAD", headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=args.timeout) as response:
            return status_result(response.status)
    except urllib.error.HTTPError as error:
        if error.code in (403, 405, 501):
            # Some servers refuse HEAD but answer GET; a range keeps the body tiny.
            get = urllib.request.Request(
                url, headers={"User-Agent": USER_AGENT, "Range": "bytes=0-0"}
            )
            try:
                with urllib.request.urlopen(get, timeout=args.timeout) as response:
                    return status_result(response.status)
            except urllib.error.HTTPError as get_error:
                body = get_error.read(2048).decode(errors="replace")
                return status_result(get_error.code, body)
            except (urllib.error.URLError, OSError, ValueError) as get_error:
                return Fetched("unverifiable", reason=f"request failed: {get_error}")
        return status_result(error.code)
    except (urllib.error.URLError, OSError, ValueError) as error:
        return Fetched("unverifiable", reason=f"request failed: {error}")


def load_stub(directory):
    """The stub manifest: {url: canned JSON file name}."""
    try:
        return json.loads((Path(directory) / "manifest.json").read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        print(f"{STUB_VARIABLE}: cannot read {directory}/manifest.json: {error}", file=sys.stderr)
        sys.exit(2)


def stub_fetch(url, manifest, directory):
    """The canned answer for a URL; a URL the manifest does not know cannot be checked."""
    name = manifest.get(fetch_url(url))
    if name is None:
        return Fetched("unverifiable", reason="no canned response")
    try:
        data = json.loads((Path(directory) / name).read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        print(f"{STUB_VARIABLE}: cannot read {directory}/{name}: {error}", file=sys.stderr)
        sys.exit(2)
    if "error" in data:
        return Fetched("unverifiable", reason=str(data["error"]))
    if data.get("rate_limit"):
        return Fetched("retry", reason="rate limited (canned)")
    if "status" in data:
        return status_result(int(data["status"]))
    if "commit" in data:
        message = data["commit"].get("message", "")
        return Fetched("ok", title=message.splitlines()[0] if message else None)
    if "title" in data:
        return Fetched("ok", title=data["title"], state=data.get("state"))
    return Fetched("unverifiable", reason="unreadable canned response")


def cache_path(directory, url):
    """The file one URL's answer is cached in."""
    digest = hashlib.sha256(fetch_url(url).encode("utf-8")).hexdigest()
    return Path(directory) / f"{digest}.json"


def fetch(url, args, stub_manifest):
    """The answer for one URL: the stub, the cache, or the network, retrying a rate limit."""
    kind = github_parts(url)
    if stub_manifest is not None:
        # A canned rate limit answers the same way every time; the retries a real run makes are
        # what the status records, so the answer is read once and reported as RETRY.
        return stub_fetch(url, stub_manifest, os.environ[STUB_VARIABLE])
    if args.cache:
        path = cache_path(args.cache, url)
        try:
            cached = json.loads(path.read_text(encoding="utf-8"))
            if time.time() - cached["fetched_at"] <= args.cache_ttl:
                return Fetched(
                    cached["status"],
                    title=cached.get("title"),
                    state=cached.get("state"),
                    http_status=cached.get("http_status"),
                    reason=cached.get("reason", ""),
                    cached=True,
                )
        except (OSError, ValueError, KeyError):
            pass
    result = Fetched("unverifiable")
    for attempt in range(MAX_ATTEMPTS):
        result = github_fetch(*kind, args=args) if kind else plain_fetch(url, args)
        if result.status != "retry" or attempt == MAX_ATTEMPTS - 1:
            break
        time.sleep(args.sleep)
    if args.cache:
        args.cache.mkdir(parents=True, exist_ok=True)
        try:
            cache_path(args.cache, url).write_text(
                json.dumps(
                    {
                        "url": fetch_url(url),
                        "status": result.status,
                        "title": result.title,
                        "state": result.state,
                        "http_status": result.http_status,
                        "reason": result.reason,
                        "fetched_at": time.time(),
                    }
                ),
                encoding="utf-8",
            )
        except OSError as error:
            print(f"warning: cannot write the cache: {error}", file=sys.stderr)
    return result


def evaluate(link, file, text, fetched):
    """Compare one citation's text with what was fetched; the link's kind picks the rule."""
    parts = github_parts(link.url)
    if fetched.status != "ok" or parts is None:
        return Checked(link, file, fetched.status, fetched=fetched)
    _, _, kind, ref = parts
    if kind in ("issues", "pull"):
        number = reference_number(link.text)
        if number is not None and str(number) != ref:
            return Checked(link, file, "mismatch", f"#{number}", f"#{ref}", fetched)
    if kind == "commit":
        sha = sha_text(link.text)
        if sha is not None and not (
            ref.lower().startswith(sha.lower()) or sha.lower().startswith(ref.lower())
        ):
            return Checked(link, file, "mismatch", sha, ref, fetched)
    claim = link.text.strip()
    if claim.strip("`").strip().lower() in LABELS or reference_number(claim) is not None:
        claim = ""
    if kind == "commit" and sha_text(claim):
        claim = ""
    if not claim:
        claim = quoted_after(text, link.end) or ""
    if claim and fetched.title is not None and not title_matches(claim, fetched.title):
        return Checked(link, file, "mismatch", claim, fetched.title, fetched)
    return Checked(link, file, "ok", fetched=fetched)


def report(checked, args, files):
    """Print the run: the problems, then the summary, or the whole run as one JSON document."""
    if args.json:
        document = {
            "checked": len(checked),
            "ok": sum(1 for row in checked if row.status == "ok"),
            "mismatch": sum(1 for row in checked if row.status == "mismatch"),
            "dead": sum(1 for row in checked if row.status == "dead"),
            "retry": sum(1 for row in checked if row.status == "retry"),
            "unverifiable": sum(
                1 for row in checked if row.status in ("unverifiable", "retry")
            ),
            "files": files,
            "urls": [
                {
                    "file": row.file,
                    "line": row.link.line,
                    "url": row.link.url,
                    "status": row.status,
                    "claimed": row.claimed,
                    "actual": row.actual,
                    "title": row.fetched.title if row.fetched else None,
                    "state": row.fetched.state if row.fetched else None,
                    "http_status": row.fetched.http_status if row.fetched else None,
                    "reason": row.fetched.reason if row.fetched else "",
                    "cached": bool(row.fetched and row.fetched.cached),
                }
                for row in checked
            ],
        }
        print(json.dumps(document, indent=2, sort_keys=False))
        return 1 if document["mismatch"] or document["dead"] else 0
    for row in checked:
        if row.status == "ok":
            if args.verbose:
                print(f"ok           {row.file}:{row.link.line} {row.link.url}")
            continue
        else:
            print(f"{row.status.upper():12} {row.file}:{row.link.line} {row.link.url}")
        if row.status == "mismatch":
            print(f'    claimed: "{row.claimed}"')
            print(f'    actual:  "{row.actual}"')
        elif row.fetched and row.fetched.reason:
            print(f"    {row.fetched.reason}")
    ok = sum(1 for row in checked if row.status == "ok")
    mismatch = sum(1 for row in checked if row.status == "mismatch")
    dead = sum(1 for row in checked if row.status == "dead")
    unverifiable = sum(1 for row in checked if row.status in ("unverifiable", "retry"))
    print(
        f"checked {len(checked)} urls: {ok} ok, {mismatch} mismatch, {dead} dead, "
        f"{unverifiable} unverifiable (text only)"
    )
    return 1 if mismatch or dead else 0


def main():
    parser = argparse.ArgumentParser(
        description="Check the GitHub citations of the formatter research."
    )
    parser.add_argument("files", nargs="+", help="markdown files to check")
    parser.add_argument("--json", action="store_true", help="print the run as one JSON document")
    parser.add_argument("--cache", metavar="DIR", type=Path, help="cache answers in DIR")
    parser.add_argument(
        "--cache-ttl", type=int, default=CACHE_TTL, help=f"seconds a cached answer stays good "
        f"(default {CACHE_TTL})"
    )
    parser.add_argument(
        "--sleep", type=float, default=5.0, help="seconds to wait after a rate limit (default 5)"
    )
    parser.add_argument(
        "--timeout", type=float, default=20.0, help="seconds one request may take (default 20)"
    )
    parser.add_argument(
        "--jobs", type=int, default=1, help="URLs fetched at once (default 1; ignored with a stub)"
    )
    parser.add_argument("--verbose", action="store_true", help="print every URL, not only problems")
    args = parser.parse_args()

    for file in args.files:
        if not Path(file).is_file():
            print(f"no such file: {file}", file=sys.stderr)
            return 2
    stub = os.environ.get(STUB_VARIABLE)
    manifest = load_stub(stub) if stub else None

    texts = {file: Path(file).read_text(encoding="utf-8") for file in args.files}
    occurrences = [
        (file, link) for file in args.files for link in collect_links(file)
    ]
    # One answer per distinct URL: drafting the same citation in several files is one fetch.
    urls = list(dict.fromkeys(fetch_url(link.url) for _, link in occurrences))
    if args.jobs > 1 and manifest is None:
        with ThreadPoolExecutor(max_workers=args.jobs) as pool:
            answers = list(pool.map(lambda url: fetch(url, args, manifest), urls))
    else:
        answers = [fetch(url, args, manifest) for url in urls]
    fetched = dict(zip(urls, answers))
    checked = [
        evaluate(link, file, texts[file], fetched[fetch_url(link.url)])
        for file, link in occurrences
    ]
    return report(checked, args, args.files)


if __name__ == "__main__":
    sys.exit(main())
