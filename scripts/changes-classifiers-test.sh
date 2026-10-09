#!/usr/bin/env bash
# Pins the `changes` classifiers of .github/workflows/ci.yml and pages.yml on synthetic
# repositories: each case builds a tiny git repository, makes a change, and runs the `id:
# diff` step's run block — extracted from the workflow file itself, so the shipped
# classifier is what is under test — with BASE at the base commit; the value it writes to
# GITHUB_OUTPUT is the assertion. Network-free, Python-free; run from anywhere:
#
#   scripts/changes-classifiers-test.sh

set -euo pipefail

cd "$(dirname "$0")/.."

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
step=$work/step.sh
output=$work/github-output
stderr=$work/stderr
failures=0

extract() {  # extract <workflow.yml>: the `id: diff` run block, dedented
  awk '
    /^        id: diff$/                { armed = 1; next }
    armed && $0 == "        run: |"     { on = 1; armed = 0; next }
    on && ( $0 == "" || /^          / ) { sub(/^          /, ""); print; next }
    on                                  { exit }
  ' "$1"
}

classify() {  # classify <workflow.yml> <repo> <base> <key>: the step's output value
  extract "$1" > "$step"
  rm -f "$output"
  if ! (cd "$2" && BASE="$3" GITHUB_OUTPUT="$output" bash "$step") 2> "$stderr"; then
    cat "$stderr" >&2
    echo STEP-FAILED
    return
  fi
  sed -n "s/^$4=//p" "$output"
}

check() {  # check <case> <expected> <actual>
  if [ "$2" = "$3" ]; then
    echo "ok   $1 -> $3"
  else
    echo "FAIL $1: expected $2, got $3"
    failures=$((failures + 1))
  fi
}

repo=$work/repo
git init -q -b main "$repo"
git -C "$repo" config user.email classifier-test@example.com
git -C "$repo" config user.name classifier-test
mkdir -p "$repo/docs" "$repo/examples" "$repo/core"
printf 'a = 1\n'         > "$repo/examples/other.conf"
printf '# the readme\n'  > "$repo/docs/readme.md"
printf 'object Probe2\n' > "$repo/core/probe2.scala"
git -C "$repo" add -A
git -C "$repo" commit -qm base
base=$(git -C "$repo" rev-parse HEAD)

# Each change is compared with its immediate parent, so earlier changes cannot satisfy it.
# A docs-only edit stays docs-only, for both classifiers.
printf 'edited\n' > "$repo/docs/readme.md"
git -C "$repo" commit -qam "docs: edit"
check "a docs-only edit (ci.yml)" false \
  "$(classify .github/workflows/ci.yml "$repo" "$base" code)"
check "a docs-only edit (pages.yml)" false \
  "$(classify .github/workflows/pages.yml "$repo" "$base" site)"

base=$(git -C "$repo" rev-parse HEAD)

# A rename inside docs/ too.
git -C "$repo" mv docs/readme.md docs/readme2.md
git -C "$repo" commit -qam "docs: rename"
check "a rename inside docs/ (ci.yml)" false \
  "$(classify .github/workflows/ci.yml "$repo" "$base" code)"

base=$(git -C "$repo" rev-parse HEAD)

# A pure rename surfaces as its destination only under plain --name-only, so the source
# must be listed too: a code file moved into docs/ would otherwise skip CI (ci.yml) and a
# core file moved into docs/ would leave the page stale (pages.yml).
git -C "$repo" mv examples/other.conf docs/other.md
git -C "$repo" commit -qam "move a config into docs"
check "a code file renamed into docs/ (ci.yml)" true \
  "$(classify .github/workflows/ci.yml "$repo" "$base" code)"

base=$(git -C "$repo" rev-parse HEAD)

git -C "$repo" mv core/probe2.scala docs/probe2.md
git -C "$repo" commit -qam "move a core file into docs"
check "a core file renamed into docs/ (pages.yml)" true \
  "$(classify .github/workflows/pages.yml "$repo" "$base" site)"

base=$(git -C "$repo" rev-parse HEAD)

# The other rename direction is caught by the destination alone; pinned so --no-renames
# keeps both directions working.
git -C "$repo" mv docs/readme2.md examples/moved.conf
git -C "$repo" commit -qam "move docs into examples"
check "a docs file renamed into code (ci.yml)" true \
  "$(classify .github/workflows/ci.yml "$repo" "$base" code)"

# NOTICE is copied into the site bundle, so changing only it must deploy the new bytes.
base=$(git -C "$repo" rev-parse HEAD)
printf 'updated attribution\n' > "$repo/NOTICE"
git -C "$repo" add NOTICE
git -C "$repo" commit -qm "update the shipped notice"
check "the shipped NOTICE changes (pages.yml)" true \
  "$(classify .github/workflows/pages.yml "$repo" "$base" site)"

# An empty diff (BASE == HEAD) has no paths: docs-only, nothing to run for.
check "no change at all (ci.yml)" false \
  "$(classify .github/workflows/ci.yml "$repo" HEAD code)"
check "no change at all (pages.yml)" false \
  "$(classify .github/workflows/pages.yml "$repo" HEAD site)"

# A diff that cannot run is not evidence of a docs-only change: delete the base commit's
# root tree object, so `git cat-file -e` still passes but `git diff` fails, and the
# classifier must answer true (run everything) rather than a silent false.
tree=$(git -C "$repo" rev-parse "$base^{tree}")
rm "$repo/.git/objects/${tree:0:2}/${tree:2}"
check "an unreadable diff (ci.yml)" true \
  "$(classify .github/workflows/ci.yml "$repo" "$base" code)"
check "an unreadable diff (pages.yml)" true \
  "$(classify .github/workflows/pages.yml "$repo" "$base" site)"

if [ "$failures" -eq 0 ]; then
  echo "all classifier cases pass"
else
  echo "$failures case(s) failed"
  exit 1
fi
