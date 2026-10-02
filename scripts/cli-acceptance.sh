#!/usr/bin/env bash
# Acceptance test of the command line contract, run on the real process: the same checks on every
# runtime, because bytes, locale, streams and exit codes are decided at the process boundary, where
# unit tests of CmdApi cannot see them.
#
#   scripts/cli-acceptance.sh <label> <command...>
#   scripts/cli-acceptance.sh jvm  java -cp "$classpath" ww86.hocon_fmt.CmdApi
#   scripts/cli-acceptance.sh node node cli/.js/target/scala-3.8.2/hocon-formatter-cli-fastopt/main.js
#   scripts/cli-acceptance.sh native cli/.native/target/scala-3.8.2/hocon-formatter
set -uo pipefail

label=$1; shift
cmd=("$@")
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
failures=0

fail() { echo "FAIL [$label] $*" >&2; failures=$((failures + 1)); }

# run <locale> <stdin-file> <args...>: fills $work/out, $work/err and $code
run() {
  local locale=$1 input=$2; shift 2
  env LC_ALL="$locale" LANG="$locale" "${cmd[@]}" "$@" < "$input" > "$work/out" 2> "$work/err"
  code=$?
}

printf 'name   =   "zażółć gęślą jaźń ✓"\nb { c=1 }\n' > "$work/unformatted"
printf 'name: "zażółć gęślą jaźń ✓"\nb.c: 1\n' > "$work/formatted"

# Multi-byte characters must survive the 4096-byte reads that stdin is consumed in.
for i in $(seq 1 1500); do printf 'key%d: "zażółć gęślą ✓"\n' "$i"; done > "$work/large"

# Input is a redirected file here, the form docs/usage.md shows.
for locale in C C.UTF-8; do
  run "$locale" "$work/unformatted" --stdin
  [ "$code" = 0 ] || fail "$locale: --stdin exited $code"
  cmp -s "$work/out" "$work/formatted" || fail "$locale: --stdin wrote $(od -c < "$work/out" | head -2 | tr -s ' ' | tr '\n' ' ')"
  # JDK 24+ warns about sun.misc.Unsafe from scala-library on every start; that is not the formatter's.
  grep -v '^WARNING: ' "$work/err" > "$work/err-own"
  [ ! -s "$work/err-own" ] || fail "$locale: --stdin wrote to stderr: $(cat "$work/err-own")"

  run "$locale" "$work/formatted" --stdin
  cmp -s "$work/out" "$work/formatted" || fail "$locale: already formatted input was changed"

  run "$locale" "$work/large" --stdin
  [ "$code" = 0 ] && cmp -s "$work/out" "$work/large" || fail "$locale: input beyond one read was damaged (exit $code)"

  : > "$work/empty"
  run "$locale" "$work/empty" --stdin
  [ "$code" = 0 ] && [ ! -s "$work/out" ] || fail "$locale: empty input did not give empty output with exit 0"
done

# An editor pipes the buffer in; a shell user redirects a file. Runtimes can treat the two differently.
cat "$work/unformatted" | env LC_ALL=C "${cmd[@]}" --stdin > "$work/out" 2> "$work/err"
code=$?
[ "$code" = 0 ] && cmp -s "$work/out" "$work/formatted" || fail "piped input: exit $code, $(grep -v '^WARNING: ' "$work/err")"

printf 'a: ${' > "$work/broken"
run C "$work/broken" --stdin --stdin-filename editor.conf
[ "$code" = 1 ] || fail "refusal exited $code, want 1"
[ ! -s "$work/out" ] || fail "refusal wrote to stdout"
grep -q 'editor.conf' "$work/err" || fail "refusal does not name the input: $(cat "$work/err")"

printf '\xff\xfe' > "$work/invalid-utf8"
run C "$work/invalid-utf8" --stdin
[ "$code" = 1 ] && [ ! -s "$work/out" ] || fail "invalid UTF-8: exit $code, stdout $(wc -c < "$work/out") bytes, want 1 and none"

run C "$work/empty" --version
[ "$code" = 0 ] && grep -Eq '^hocon-formatter [0-9]' "$work/out" || fail "--version: exit $code, output $(cat "$work/out")"

for args in "--stdin a.conf" "--stdin --check" "--version a.conf" "--stdin-filename x.conf" ""; do
  # shellcheck disable=SC2086
  run C "$work/empty" $args
  [ "$code" = 2 ] && [ ! -s "$work/out" ] || fail "arguments '$args': exit $code, want 2 with nothing on stdout"
done

[ "$failures" = 0 ] && echo "cli acceptance [$label]: OK" || { echo "cli acceptance [$label]: $failures failed" >&2; exit 1; }
