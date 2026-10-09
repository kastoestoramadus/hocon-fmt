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
expect_run 'a rate limit is RETRY, a refused connection and an unknown URL are unverifiable, and none fails the run' \
  0 'checked 3 urls: 0 ok, 0 mismatch, 0 dead, 3 unverifiable (text only)' "$md/flaky.md"
expect_run 'a rate limit is labelled RETRY' 0 'RETRY' "$md/flaky.md"
expect_run 'a bracket inside a code span does not swallow the next link' \
  0 'checked 1 urls: 1 ok, 0 mismatch, 0 dead, 0 unverifiable (text only)' "$md/stray-bracket.md"
expect_run 'a backticked identifier after a link is not read as its title' \
  0 'checked 1 urls: 1 ok, 0 mismatch, 0 dead, 0 unverifiable (text only)' "$md/code-not-title.md"
expect_run 'an ellipsis-marked fragment matches a title it is cut from' \
  0 'checked 1 urls: 1 ok, 0 mismatch, 0 dead, 0 unverifiable (text only)' "$md/ellipsis.md"

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
