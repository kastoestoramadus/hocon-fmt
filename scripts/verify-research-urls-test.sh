#!/usr/bin/env bash
# Exercises scripts/verify-research-urls.py without a network: the checker reads every answer
# from canned JSON when VERIFY_RESEARCH_URLS_STUB names a directory with a manifest.json
# ({url: file name}), so a fixture can pin what GitHub and a plain web server would have said.
# Each case is a small markdown file and the summary line it must produce; the run is
# `python3 -I`, the mode the script is meant to be run in. Run from anywhere:
#
#   scripts/verify-research-urls-test.sh

set -euo pipefail

repo=$(cd "$(dirname "$0")/.." && pwd)
script="$repo/scripts/verify-research-urls.py"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
md="$work/md"
stub="$work/stub"
mkdir -p "$md" "$stub"
failed=0

# --- fixtures -------------------------------------------------------------------------------
cat > "$md/good.md" <<'EOF'
# Good citations

- Fixed in [#1](https://github.com/acme/widgets/issues/1) "A good title"
- Commit [abc1234](https://github.com/acme/widgets/commit/abc1234deadbeef) landed.
- Bare https://github.com/acme/widgets/issues/2 here.
- Docs: [the manual](https://example.com/docs).
- The wrapped quote [#4](https://github.com/acme/widgets/issues/4) "A wrapped
  title" counts too.
EOF

cat > "$md/bad-title.md" <<'EOF'
- [#5](https://github.com/acme/widgets/issues/5) "An invented title"
EOF

cat > "$md/dead.md" <<'EOF'
- The issue [#6](https://github.com/acme/widgets/issues/6) is gone.
- The page [the gone page](https://example.com/gone) is gone too.
EOF

cat > "$md/non-gh.md" <<'EOF'
- See [the manual](https://example.com/docs).
EOF

cat > "$md/no-text.md" <<'EOF'
- [](https://github.com/acme/widgets/issues/3)
EOF

cat > "$md/reference-mismatch.md" <<'EOF'
- [#9](https://github.com/acme/widgets/issues/10) "Ten"
EOF

cat > "$md/flaky.md" <<'EOF'
- The issue [#7](https://github.com/acme/widgets/issues/7) is rate limited.
- [the flaky page](https://example.com/flaky) refused the connection.
- [the unknown page](https://example.com/unknown) is not in the manifest.
EOF

cat > "$md/stray-bracket.md" <<'EOF'
- Backticks hold `a = [1, ` and `a = ${x`, but the link ([#1](https://github.com/acme/widgets/issues/1) "A good title") is real.
EOF

cat > "$md/code-not-title.md" <<'EOF'
- [#5](https://github.com/acme/widgets/issues/5) — `setShowEnvVariableValues` hides secrets.
EOF

cat > "$md/ellipsis.md" <<'EOF'
- [#11](https://github.com/acme/widgets/issues/11) "…different code on the second pass"
EOF

cat > "$stub/manifest.json" <<'EOF'
{
  "https://github.com/acme/widgets/issues/1": "issue-1.json",
  "https://github.com/acme/widgets/issues/2": "issue-2.json",
  "https://github.com/acme/widgets/issues/3": "issue-3.json",
  "https://github.com/acme/widgets/issues/4": "issue-4.json",
  "https://github.com/acme/widgets/commit/abc1234deadbeef": "commit-abc1234.json",
  "https://github.com/acme/widgets/issues/5": "issue-5.json",
  "https://github.com/acme/widgets/issues/6": "issue-6.json",
  "https://github.com/acme/widgets/issues/7": "issue-7.json",
  "https://github.com/acme/widgets/issues/10": "issue-10.json",
  "https://github.com/acme/widgets/issues/11": "issue-11.json",
  "https://example.com/docs": "docs.json",
  "https://example.com/gone": "gone.json",
  "https://example.com/flaky": "flaky.json"
}
EOF
printf '%s\n' '{"state": "closed", "title": "A good title"}'      > "$stub/issue-1.json"
printf '%s\n' '{"state": "open", "title": "Second issue"}'        > "$stub/issue-2.json"
printf '%s\n' '{"state": "open", "title": "Third issue"}'         > "$stub/issue-3.json"
printf '%s\n' '{"state": "open", "title": "A wrapped title"}'     > "$stub/issue-4.json"
printf '%s\n' '{"state": "open", "title": "The real title"}'      > "$stub/issue-5.json"
printf '%s\n' '{"status": 404}'                                   > "$stub/issue-6.json"
printf '%s\n' '{"rate_limit": true}'                              > "$stub/issue-7.json"
printf '%s\n' '{"state": "open", "title": "Ten"}'                 > "$stub/issue-10.json"
printf '%s\n' '{"state": "closed", "title": "INTERNAL ERROR: Black produced different code on the second pass"}' \
  > "$stub/issue-11.json"
printf '%s\n' '{"status": 200}'                                   > "$stub/docs.json"
printf '%s\n' '{"status": 410}'                                   > "$stub/gone.json"
printf '%s\n' '{"error": "connection refused"}'                    > "$stub/flaky.json"
printf '%s\n' '{"sha": "abc1234deadbeef", "commit": {"message": "Fix the thing\n\nand a body"}}' \
  > "$stub/commit-abc1234.json"

# --- harness --------------------------------------------------------------------------------
expect_run() { # name, expected exit status, pattern the output must carry ('-' = none), args...
  local name="$1" want="$2" pattern="$3"
  shift 3
  local out status
  out=$(VERIFY_RESEARCH_URLS_STUB="$stub" python3 -I "$script" "$@" 2>&1) && status=0 || status=$?
  if [ "$status" -ne "$want" ]; then
    echo "FAIL $name: exit $status, wanted $want" >&2
    echo "$out" >&2
    failed=1
  elif [ "$pattern" != "-" ] && ! grep -q -- "$pattern" <<< "$out"; then
    echo "FAIL $name: output carries no '$pattern':" >&2
    echo "$out" >&2
    failed=1
  else
    echo "ok   $name"
  fi
}

summary='checked 5 urls: 5 ok, 0 mismatch, 0 dead, 0 unverifiable (text only)'

expect_run 'a good link, a bare URL, a quote over a line break, a non-GitHub URL and a commit all pass' \
  0 "$summary" "$md/good.md"
expect_run "a wrong title is a MISMATCH naming both strings" \
  1 'claimed: "An invented title"' "$md/bad-title.md"
expect_run 'the real title appears beside an invented one' \
  1 'actual: *"The real title"' "$md/bad-title.md"
expect_run 'a 404 and a 410 are DEAD and fail the run' \
  1 'checked 2 urls: 0 ok, 0 mismatch, 2 dead, 0 unverifiable (text only)' "$md/dead.md"
expect_run 'a non-GitHub URL that answers 200 is ok' \
  0 'checked 1 urls: 1 ok, 0 mismatch, 0 dead, 0 unverifiable (text only)' "$md/non-gh.md"
expect_run 'a link without text is checked for existence only' \
  0 'checked 1 urls: 1 ok, 0 mismatch, 0 dead, 0 unverifiable (text only)' "$md/no-text.md"
expect_run 'a link whose text names the wrong issue number is a MISMATCH' \
  1 'claimed: *"#9"' "$md/reference-mismatch.md"
expect_run 'a rate limit stops the run immediately' \
  3 'checked 1 urls: 0 ok, 0 mismatch, 0 dead, 1 unverifiable (text only)' "$md/flaky.md"
expect_run 'a rate limit is labelled RETRY' 3 'RETRY' "$md/flaky.md"
expect_run 'a bracket inside a code span does not swallow the next link' \
  0 'checked 1 urls: 1 ok, 0 mismatch, 0 dead, 0 unverifiable (text only)' "$md/stray-bracket.md"
expect_run 'a backticked identifier after a link is not read as its title' \
  0 'checked 1 urls: 1 ok, 0 mismatch, 0 dead, 0 unverifiable (text only)' "$md/code-not-title.md"
expect_run 'an ellipsis-marked fragment matches a title it is cut from' \
  0 'checked 1 urls: 1 ok, 0 mismatch, 0 dead, 0 unverifiable (text only)' "$md/ellipsis.md"

# Review regressions; transport fixtures exercise the actual gh path as well as canned URLs.
python3 - "$md" "$stub" <<'PYFIX'
import json, sys
from pathlib import Path
md, stub = map(Path, sys.argv[1:])
fixtures = {
    "prose": '[fixed in #83](https://github.com/acme/widgets/pull/83)',
    "quoted-label": '["WRONG title"](https://github.com/acme/widgets/issues/5)',
    "backtick-title": '[#5](https://github.com/acme/widgets/issues/5) `WRONG title`',
    "literal-underscore": 'https://example.com/name_',
    "unclosed-code": '~~~md\nhttps://example.com/gone',
    "real-title": '[The real title](https://github.com/acme/widgets/issues/5)',
    "code": '```md\n[link](https://example.com/gone)\n```\n~~~\nhttps://example.com/gone\n~~~\n`https://example.com/gone`\n``[x](https://example.com/gone)``\n[docs](https://example.com/docs)',
    "queries": 'https://example.com/search?q=live\nhttps://example.com/search?q=dead',
    "emphasis": '**https://example.com/docs**\n_https://example.com/docs_',
    "missing-commit": 'https://github.com/acme/widgets/commit/deadbee',
    "pull-issue": '[#83](https://github.com/acme/widgets/pull/83)',
    "issue-pr": '[#84](https://github.com/acme/widgets/issues/84)',
    "forbidden": 'https://github.com/acme/widgets/issues/85',
    "limited": 'https://github.com/acme/widgets/issues/86\nhttps://github.com/acme/widgets/issues/84',
    "reset": 'https://github.com/acme/widgets/issues/87',
}
for name, text in fixtures.items():
    (md / (name + '.md')).write_text(text + '\n')
manifest = json.loads((stub / 'manifest.json').read_text())
responses = {
    'https://github.com/acme/widgets/commit/deadbee': {'status': 422, 'message': 'No commit found for SHA: deadbee'},
    'https://github.com/acme/widgets/pull/83': {'title': 'An ordinary issue'},
    'https://example.com/name_': {'status': 200},
    'https://example.com/search?q=live': {'status': 200},
    'https://example.com/search?q=dead': {'status': 404},
    'https://api.github.com/repos/acme/widgets/pulls/83': {'status': 404},
    'https://api.github.com/repos/acme/widgets/issues/83': {'title': 'An ordinary issue'},
    'https://api.github.com/repos/acme/widgets/issues/84': {'title': 'A PR', 'pull_request': {}},
    'https://api.github.com/repos/acme/widgets/commits/deadbee': {'status': 422, 'message': 'No commit found for SHA: deadbee'},
    'https://api.github.com/repos/acme/widgets/issues/85': {'status': 403, 'message': 'Resource not accessible by integration'},
    'https://api.github.com/repos/acme/widgets/issues/86': {'status': 403, 'message': 'API rate limit exceeded', 'headers': {'Retry-After': '120'}},
    'https://api.github.com/repos/acme/widgets/issues/87': {'status': 429, 'headers': {'X-RateLimit-Reset': '4102444800'}},
}
for n, (url, response) in enumerate(responses.items()):
    name = f'regression-{n}.json'
    manifest[url] = name
    (stub / name).write_text(json.dumps(response))
(stub / 'manifest.json').write_text(json.dumps(manifest))
PYFIX
mkdir -p "$work/bin"
cat > "$work/bin/gh" <<'PYGH'
#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
if sys.argv[1] == 'auth':
    sys.exit(1 if os.environ.get('TEST_NO_AUTH') else 0)
path = next(arg for arg in sys.argv[2:] if arg.startswith('repos/'))
with open(os.environ['TEST_REQUEST_LOG'], 'a') as log:
    log.write(path + '\n')
stub = Path(os.environ['TEST_TRANSPORT_STUB'])
manifest = json.loads((stub / 'manifest.json').read_text())
data = json.loads((stub / manifest['https://api.github.com/' + path]).read_text())
status = data.pop('status', 200)
headers = data.pop('headers', {})
if '--include' in sys.argv:
    print(f'HTTP/2.0 {status} Fixture')
    for key, value in headers.items():
        print(f'{key}: {value}')
    print()
print(json.dumps(data))
if status >= 400:
    print(f'gh: {data.get("message", "error")} (HTTP {status})', file=sys.stderr)
    sys.exit(1)
PYGH
chmod +x "$work/bin/gh"
expect_run 'prose labels claim no title' 0 'ok (no title claimed)' --verbose "$md/prose.md"
expect_run 'a quoted wrong link label still mismatches' 1 'WRONG title' "$md/quoted-label.md"
expect_run 'a backticked wrong title still mismatches' 1 'WRONG title' "$md/backtick-title.md"
expect_run 'a real unquoted title passes' 0 '1 ok' "$md/real-title.md"
expect_run 'fenced and inline code links are skipped' 0 'skipped 4 in code' "$md/code.md"
expect_run 'plain query strings identify distinct resources' 1 'checked 2 urls: 1 ok, 0 mismatch, 1 dead' "$md/queries.md"
expect_run 'markdown emphasis wrappers are stripped' 0 'checked 2 urls: 2 ok' "$md/emphasis.md"

transport_run() { # Exercise gh with the same canned JSON, without bypassing fetch/cache.
  local name="$1" want="$2" pattern="$3"
  shift 3
  local out status
  out=$(env -u VERIFY_RESEARCH_URLS_STUB PATH="$work/bin:$PATH" \
    TEST_TRANSPORT_STUB="$stub" TEST_REQUEST_LOG="$work/requests" \
    python3 -I "$script" "$@" 2>&1) && status=0 || status=$?
  if [ "$status" -ne "$want" ] || ! grep -q -- "$pattern" <<< "$out"; then
    echo "FAIL $name: exit $status (wanted $want), expected '$pattern': $out" >&2
    failed=1
  else
    echo "ok   $name"
  fi
}
transport_run 'pull URL can name an issue' 0 '1 ok' "$md/pull-issue.md"
transport_run 'issue URL can name a PR' 0 '1 ok' "$md/issue-pr.md"
expect_run 'canned missing commit HTTP 422 is DEAD' 1 '1 dead' "$md/missing-commit.md"
transport_run 'missing commit HTTP 422 is DEAD' 1 '1 dead' "$md/missing-commit.md"
transport_run 'ordinary HTTP 403 is unverifiable' 0 'UNVERIFIABLE' "$md/forbidden.md"
: > "$work/requests"
transport_run 'rate limit stops with Retry-After advice' 3 'retry after 120 seconds' --sleep 0 --jobs 4 --cache "$work/cache" "$md/limited.md"
if [ "$(wc -l < "$work/requests")" -ne 1 ]; then
  echo 'FAIL rate limit must make exactly one request' >&2; failed=1
else
  echo 'ok   rate limit makes exactly one request'
fi
if [ -d "$work/cache" ] && [ -n "$(ls -A "$work/cache")" ]; then
  echo 'FAIL rate limit must not be cached' >&2; failed=1
else
  echo 'ok   rate limit is not cached'
fi
transport_run 'reset header is reported' 3 'retry at 2100-01-01' --sleep 0 "$md/reset.md"
printf '`https://example.com/gone`\n' > "$md/auth-only.md"
TEST_NO_AUTH=1 transport_run 'unauthenticated runs warn' 0 'warning: running without gh authentication' "$md/auth-only.md"
# Definitive responses remain cacheable; an old RETRY cache entry must be ignored.
transport_run 'definitive response is cached' 0 '1 ok' --cache "$work/good-cache" "$md/issue-pr.md"
: > "$work/requests"
transport_run 'definitive cache avoids requests' 0 '1 ok' --cache "$work/good-cache" "$md/issue-pr.md"
if [ -s "$work/requests" ]; then echo 'FAIL definitive cache was ignored' >&2; failed=1; fi
python3 - "$work/good-cache" <<'PYCACHE'
import json, sys
from pathlib import Path
for path in Path(sys.argv[1]).glob('*.json'):
    data = json.loads(path.read_text()); data['status'] = 'retry'
    path.write_text(json.dumps(data))
PYCACHE
transport_run 'legacy retry cache is ignored' 0 '1 ok' --cache "$work/good-cache" "$md/issue-pr.md"

python3 - "$work/good-cache" <<'PYOLD'
import json, sys
from pathlib import Path
for path in Path(sys.argv[1]).glob('*.json'):
    data = json.loads(path.read_text())
    data.pop('version', None)
    data['status'] = 'dead'
    path.write_text(json.dumps(data))
PYOLD
transport_run 'old-format false DEAD cache is ignored' 0 '1 ok' --cache "$work/good-cache" "$md/issue-pr.md"

expect_run 'an unclosed fence skips the rest of the file' 0 'skipped 1 in code' "$md/unclosed-code.md"
expect_run 'a literal trailing underscore is kept' 0 '1 ok' "$md/literal-underscore.md"

# A loopback HTTP transport reads canned statuses and records requests, including queries.
cat > "$work/http-stub.py" <<'PYHTTP'
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
work = Path(sys.argv[1])
class Handler(BaseHTTPRequestHandler):
    def do_HEAD(self):
        with (work / 'http-requests').open('a') as log:
            log.write(self.path + '\n')
        data = json.loads((work / 'http.json').read_text())[self.path]
        self.send_response(data['status'])
        for key, value in data.get('headers', {}).items():
            self.send_header(key, value)
        self.end_headers()
    def do_GET(self):
        self.do_HEAD()
    def log_message(self, *args):
        pass
server = HTTPServer(('127.0.0.1', 0), Handler)
(work / 'port').write_text(str(server.server_port))
server.serve_forever()
PYHTTP
printf '%s\n' '{"/search?q=live":{"status":200},"/search?q=dead":{"status":404},"/limited":{"status":403,"headers":{"X-RateLimit-Remaining":"0","Retry-After":"120"}}}' > "$work/http.json"
python3 "$work/http-stub.py" "$work" &
http_pid=$!
trap 'kill "$http_pid" 2>/dev/null || true; rm -rf "$work"' EXIT
for attempt in {1..100}; do [ -s "$work/port" ] && break; sleep 0.01; done
port=$(cat "$work/port")
printf 'http://127.0.0.1:%s/search?q=live\nhttp://127.0.0.1:%s/search?q=dead\n' "$port" "$port" > "$md/http-query.md"
transport_run 'query transport keeps both resources' 1 '1 ok, 0 mismatch, 1 dead' --cache "$work/query-cache" "$md/http-query.md"
if [ "$(wc -l < "$work/http-requests")" -ne 2 ] || [ "$(ls "$work/query-cache" | wc -l)" -ne 2 ]; then
  echo 'FAIL query URLs need two requests and two cache entries' >&2; failed=1
fi
: > "$work/http-requests"
transport_run 'query cache keeps both answers' 1 '1 ok, 0 mismatch, 1 dead' --cache "$work/query-cache" "$md/http-query.md"
if [ -s "$work/http-requests" ]; then echo 'FAIL query cache caused requests' >&2; failed=1; fi
printf 'http://127.0.0.1:%s/limited\nhttp://127.0.0.1:%s/search?q=live\n' "$port" "$port" > "$md/http-limited.md"
transport_run 'HEAD rate limit prevents GET fallback and subsequent URLs' 3 'retry after 120 seconds' "$md/http-limited.md"
if [ "$(wc -l < "$work/http-requests")" -ne 1 ]; then
  echo 'FAIL confirmed HEAD rate limit must stop after one request' >&2; failed=1
fi

# --json prints the same run as one document.
json_out=$(VERIFY_RESEARCH_URLS_STUB="$stub" python3 -I "$script" --json "$md/good.md" 2>/dev/null)
if python3 -c 'import json,sys
d = json.loads(sys.stdin.read())
assert d["checked"] == 5 and d["ok"] == 5, d
assert len(d["urls"]) == 5, d' <<< "$json_out" 2>/dev/null; then
  echo "ok   --json prints the summary and every checked URL"
else
  echo "FAIL --json prints the summary and every checked URL:" >&2
  echo "$json_out" >&2
  failed=1
fi

if [ "$failed" -eq 0 ]; then
  echo 'verify-research-urls: every case passes'
else
  echo 'verify-research-urls: FAILED'
fi
exit "$failed"
