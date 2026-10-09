#!/usr/bin/env python3
"""Checks the GitHub citations in the formatter research against GitHub itself.

  scripts/verify-research-urls.py [--json] [--cache DIR] [--jobs N] FILE.md [FILE.md ...]

Links in fenced blocks and inline code are skipped and counted. GitHub issues, pull requests
and commits are resolved through authenticated `gh api`, or the public API with a warning.
Title claims are explicit quoted phrases or labels equal to the real title after normalisation;
prose labels are checked for existence only. Other URLs get HEAD (GET after 403/405/501).

Exit 1 for DEAD or MISMATCH, 3 on the first confirmed rate limit, 2 for invalid inputs, otherwise
0. Retry-After and X-RateLimit-Reset are reported so the caller can resume when allowed; no more
requests are made after a limit. Only definitive answers (ok/dead) are cached under --cache.

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
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path

STUB_VARIABLE = "VERIFY_RESEARCH_URLS_STUB"
USER_AGENT = "hocon-fmt-verify-research-urls"
GITHUB_API = "https://api.github.com"
CACHE_TTL = 86400
CACHE_VERSION = 2

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


class Link:
    """One citation in one file: its URL, the markdown text and the position of each."""

    __slots__ = ("url", "text", "line", "end", "in_code")

    def __init__(self, url, text, line, end, in_code=False):
        self.in_code = in_code
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


def clean_bare(url, prefix=""):
    """A bare URL without the sentence punctuation that followed it."""
    url = url.rstrip(".,;:!?")
    wrapper = re.search(r"([*_]+)$", prefix)
    if wrapper and url.endswith(wrapper[1]):
        url = url[:-len(wrapper[1])].rstrip(".,;:!?")
    while url.endswith(")") and url.count("(") < url.count(")"):
        url = url[:-1]
    return url


def code_spans(text):
    """Offsets of fenced blocks and matching backtick spans, preserving source positions."""
    spans = []
    fence = None
    offset = 0
    for line in text.splitlines(keepends=True):
        match = re.match(r" {0,3}(`{3,}|~{3,})(.*)$", line.rstrip("\r\n"))
        if fence is None:
            if match and not (match[1][0] == "`" and "`" in match[2]):
                fence = (match[1][0], len(match[1]), offset)
        elif match and match[1][0] == fence[0] and len(match[1]) >= fence[1] and not match[2].strip():
            spans.append((fence[2], offset + len(line)))
            fence = None
        offset += len(line)
    if fence:
        spans.append((fence[2], len(text)))
    runs = list(re.finditer(r"`+", text))
    index = 0
    while index < len(runs):
        run = runs[index]
        if any(start <= run.start() < end for start, end in spans):
            index += 1
            continue
        closing = next((j for j in range(index + 1, len(runs))
                        if len(runs[j][0]) == len(run[0])
                        and not any(start <= runs[j].start() < end for start, end in spans)), None)
        if closing is None:
            index += 1
        else:
            # A code span cannot cross a fenced block.
            if not any(run.start() < start < runs[closing].end() for start, _ in spans):
                spans.append((run.start(), runs[closing].end()))
            index = closing + 1
    return spans


def collect_links(path):
    """Every citation in source order, marking examples in code for the summary."""
    text = Path(path).read_text(encoding="utf-8")
    code = code_spans(text)
    spans = []
    links = []
    for match in MARKDOWN_LINK.finditer(text):
        spans.append(match.span())
        links.append((match.start(), Link(match.group("url"), match.group("text"),
                     line_of(text, match.start()), match.end())))
    for match in URL.finditer(text):
        if any(start <= match.start() < end for start, end in spans):
            continue
        url = clean_bare(match.group(0), text[max(0, match.start() - 3):match.start()])
        if url:
            links.append((match.start(), Link(url, "", line_of(text, match.start()), match.end())))
    for offset, link in sorted(links, key=lambda pair: pair[0]):
        link.in_code = any(start <= offset < end for start, end in code)
        yield link


def fetch_url(url):
    """Drop fragments; only GitHub API citations can also discard their query."""
    url = url.split("#", 1)[0]
    return url.split("?", 1)[0] if GH_LINK.match(url) else url


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
    # A lone backticked identifier is code; a phrase can be a quoted title.
    closing = {'"': '"', "“": "”", "`": "`"}.get(opening)
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
            return claim if 0 < len(claim) <= 400 and (opening != "`" or " " in claim) else None
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


def status_result(code, body="", api=False, headers=None):
    """Map definitive HTTP errors separately from confirmed rate limits."""
    headers = {key.lower(): str(value) for key, value in (headers or {}).items()}
    if code in (404, 410) or (api and code == 422 and "no commit found" in body.lower()):
        return Fetched("dead", http_status=code, reason=f"HTTP {code}")
    if 200 <= code < 400:
        return Fetched("ok", http_status=code)
    if code == 429 or (code == 403 and (
        "rate limit" in body.lower() or headers.get("x-ratelimit-remaining") == "0"
    )):
        advice = ""
        if "retry-after" in headers:
            value = headers["retry-after"]
            try:
                delay = max(0, int(value))
                advice = f"; retry after {delay} seconds"
            except ValueError:
                try:
                    advice = f"; retry at {parsedate_to_datetime(value).isoformat()}"
                except (ValueError, TypeError, OverflowError):
                    pass
        elif "x-ratelimit-reset" in headers:
            try:
                reset = datetime.fromtimestamp(int(headers["x-ratelimit-reset"]), timezone.utc)
                advice = f"; retry at {reset.isoformat()}"
            except (ValueError, OverflowError, OSError):
                pass
        return Fetched("retry", http_status=code, reason=f"rate limited (HTTP {code}){advice}")
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
    endpoint = "commits" if kind == "commit" else "issues"
    path = f"repos/{owner}/{repo}/{endpoint}/{ref}"
    if args.gh_authenticated:
        try:
            done = subprocess.run(
                ["gh", "api", "--include", path], capture_output=True, text=True, timeout=args.timeout
            )
        except (subprocess.SubprocessError, OSError):
            done = None
        if done is not None:
            # --include retains retry headers even when gh exits with an HTTP error.
            raw = done.stdout.replace("\r\n", "\n")
            head, separator, body = raw.partition("\n\n")
            status = re.match(r"HTTP/\S+ (\d+)", head)
            if status and separator:
                headers = dict(line.split(":", 1) for line in head.splitlines()[1:] if ":" in line)
                headers = {key: value.strip() for key, value in headers.items()}
                code = int(status[1])
                if code >= 400:
                    return status_result(code, body, api=True, headers=headers)
                raw = body
            if done.returncode == 0:
                try:
                    return gh_success(kind, json.loads(raw))
                except (ValueError, AttributeError):
                    return Fetched("unverifiable", reason="gh printed no JSON")
            message = (done.stderr or done.stdout).strip()
            code = re.search(r"HTTP (\d+)", message)
            if code:
                return status_result(int(code[1]), message, api=True)
            return Fetched("unverifiable", reason=message or "gh request failed")
    data, error = api_request(f"{GITHUB_API}/{path}", args.timeout)
    if data is not None:
        return gh_success(kind, data)
    if isinstance(error, urllib.error.HTTPError):
        body = error.read(4096).decode(errors="replace")
        return status_result(error.code, body, api=True, headers=error.headers)
    return Fetched("unverifiable", reason=f"request failed: {error}")


def plain_fetch(url, args):
    """A HEAD status check for anything that is not a GitHub issue, pull or commit."""
    request = urllib.request.Request(url, method="HEAD", headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=args.timeout) as response:
            return status_result(response.status)
    except urllib.error.HTTPError as error:
        body = error.read(2048).decode(errors="replace")
        result = status_result(error.code, body, headers=error.headers)
        if result.status == "retry":
            return result
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
                return status_result(get_error.code, body, headers=get_error.headers)
            except (urllib.error.URLError, OSError, ValueError) as get_error:
                return Fetched("unverifiable", reason=f"request failed: {get_error}")
        return result
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
        return status_result(int(data["status"]), data.get("message", ""),
                             api=url.startswith((GITHUB_API, "https://github.com/")),
                             headers=data.get("headers"))
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
    """Fetch once; cache only definitive existence answers."""
    kind = github_parts(url)
    if stub_manifest is not None:
        return stub_fetch(url, stub_manifest, os.environ[STUB_VARIABLE])
    if args.cache:
        path = cache_path(args.cache, url)
        try:
            cached = json.loads(path.read_text(encoding="utf-8"))
            if (cached.get("version") == CACHE_VERSION
                    and cached["status"] in ("ok", "dead")
                    and time.time() - cached["fetched_at"] <= args.cache_ttl):
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
    result = github_fetch(*kind, args=args) if kind else plain_fetch(url, args)
    if args.cache and result.status in ("ok", "dead"):
        args.cache.mkdir(parents=True, exist_ok=True)
        try:
            cache_path(args.cache, url).write_text(
                json.dumps(
                    {
                        "version": CACHE_VERSION,
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
    label = link.text.strip()
    claim = quoted_after(text, link.end) or ""
    if not claim and fetched.title and normalise(label) == normalise(fetched.title):
        claim = label
    if not claim:
        # Reference-prefixed labels also quote titles: [repo#7 “Title”](url).
        quote = re.search(r'["“`]', label)
        claim = quoted_after(label, quote.start()) if quote else ""
        claim = claim or ""
    if claim and fetched.title is not None and not title_matches(claim, fetched.title):
        return Checked(link, file, "mismatch", claim, fetched.title, fetched)
    return Checked(link, file, "ok", claimed=claim, fetched=fetched)


def report(checked, args, files, skipped=0):
    """Print the run: the problems, then the summary, or the whole run as one JSON document."""
    no_title = sum(1 for row in checked if row.status == "ok"
                   and github_parts(row.link.url) and not row.claimed)
    if args.json:
        document = {
            "checked": len(checked),
            "skipped_in_code": skipped,
            "ok_no_title_claimed": no_title,
            "stopped_on_rate_limit": any(row.status == "retry" for row in checked),
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
        return 3 if document["retry"] else (1 if document["mismatch"] or document["dead"] else 0)
    for row in checked:
        if row.status == "ok":
            if args.verbose:
                label = "ok (no title claimed)" if github_parts(row.link.url) and not row.claimed else "ok"
                print(f"{label:12} {row.file}:{row.link.line} {row.link.url}")
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
        f"{unverifiable} unverifiable (text only); skipped {skipped} in code; "
        f"{no_title} ok (no title claimed)"
    )
    return 3 if any(row.status == "retry" for row in checked) else (1 if mismatch or dead else 0)


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
        "--sleep", type=float, default=5.0, help="deprecated compatibility option; rate limits stop immediately"
    )
    parser.add_argument(
        "--timeout", type=float, default=20.0, help="seconds one request may take (default 20)"
    )
    parser.add_argument(
        "--jobs", type=int, default=1, help="compatibility option; requests are serial to stop at the first rate limit"
    )
    parser.add_argument("--verbose", action="store_true", help="print every URL, not only problems")
    args = parser.parse_args()

    for file in args.files:
        if not Path(file).is_file():
            print(f"no such file: {file}", file=sys.stderr)
            return 2
    stub = os.environ.get(STUB_VARIABLE)
    manifest = load_stub(stub) if stub else None

    args.gh_authenticated = False
    if manifest is None:
        if shutil.which("gh"):
            try:
                args.gh_authenticated = subprocess.run(
                    ["gh", "auth", "status"], capture_output=True, timeout=args.timeout
                ).returncode == 0
            except (subprocess.SubprocessError, OSError):
                pass
        if not args.gh_authenticated:
            print("warning: running without gh authentication; GitHub API limits are lower", file=sys.stderr)

    texts = {file: Path(file).read_text(encoding="utf-8") for file in args.files}
    all_links = [(file, link) for file in args.files for link in collect_links(file)]
    skipped = sum(link.in_code for _, link in all_links)
    occurrences = [(file, link) for file, link in all_links if not link.in_code]
    urls = list(dict.fromkeys(fetch_url(link.url) for _, link in occurrences))
    fetched = {}
    # Serial requests ensure even --jobs cannot launch more work after a confirmed limit.
    for url in urls:
        result = fetch(url, args, manifest)
        fetched[url] = result
        if result.status == "retry":
            print(f"stopped: rate limit at {url}: {result.reason}; rerun when allowed (exit 3)", file=sys.stderr)
            break
    checked = [
        evaluate(link, file, texts[file], fetched[fetch_url(link.url)])
        for file, link in occurrences if fetch_url(link.url) in fetched
    ]
    return report(checked, args, args.files, skipped)


if __name__ == "__main__":
    sys.exit(main())
