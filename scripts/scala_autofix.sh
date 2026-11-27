#!/usr/bin/env bash
# Apply Scala rewrites and formatting to files changed since the merge base.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
base=$(git merge-base HEAD "${1:-origin/main}")
paths=$(mktemp)
trap 'rm -f -- "$paths"' EXIT

# Skip exact renames and deletions so a source tree move stays a pure move.
git diff -M100% --name-only --diff-filter=AMT -z "$base" -- '*.scala' '*.sbt' |
  while IFS= read -r -d '' path; do
    if [[ -f "$path" && ! -L "$path" ]]; then
      printf '%s\0' "$path"
    fi
  done > "$paths"

if [[ -s "$paths" ]]; then
  SCALA_AUTOFIX_FILES="$paths" sbt -batch 'scalafixAll --syntactic --no-cache' scalafmt
fi
