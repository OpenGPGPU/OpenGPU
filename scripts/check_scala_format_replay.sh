#!/usr/bin/env bash
# Verify that the changed sbt sources exactly match Scalafmt's output on the base.
set -euo pipefail

root=$(git rev-parse --show-toplevel)
cd "$root"
base=$(git merge-base HEAD "$1")
sources=(':(glob)src/main/scala/**/*.scala' ':(glob)src/test/scala/**/*.scala')

# Additions, deletions, renames and mode changes require normal suite selection.
[[ -z "$(git diff --summary --no-renames "$base" HEAD -- "${sources[@]}")" ]] || exit 1
changed=$(git diff --name-only --no-renames "$base" HEAD -- "${sources[@]}" | wc -l)
# Small PRs should not pay for a second run of the formatter.
(( changed >= 256 )) || exit 1

mkdir -p .artifacts/worktrees
scratch=$(mktemp -d "$root/.artifacts/worktrees/scalafmt-replay.XXXXXX")
checkout="$scratch/base"
cleanup() {
  if [[ -e "$checkout/.git" ]]; then
    git worktree remove --force "$checkout"
  fi
  rm -f -- "$scratch/paths" "$scratch/base.conf" "$scratch/replay.conf"
  rmdir "$scratch"
}
trap cleanup EXIT

git diff --name-only --no-renames -z "$base" HEAD -- "${sources[@]}" > "$scratch/paths"
git diff --quiet HEAD -- "${sources[@]}"
git worktree add --quiet --detach "$checkout" "$base"
while IFS= read -r -d '' path; do
  [[ -f "$path" && ! -L "$path" && -f "$checkout/$path" && ! -L "$checkout/$path" ]] || exit 1
done < "$scratch/paths"

if ! cmp -s "$checkout/.scalafmt.conf" .scalafmt.conf; then
  cp "$checkout/.scalafmt.conf" "$scratch/base.conf"
  SCALA_FORMAT_BASE_CONFIG="$scratch/base.conf" SCALA_FORMAT_REPLAY_CONFIG="$scratch/replay.conf" \
    sbt -batch scalaFormatReplayConfig
fi
replay_config="$checkout/.scalafmt.conf"
if [[ -f "$scratch/replay.conf" ]]; then
  replay_config="$scratch/replay.conf"
fi
(
  cd "$checkout"
  SCALA_AUTOFIX_FILES="$scratch/paths" SCALA_FORMAT_REPLAY_CONFIG="$replay_config" \
    sbt -batch 'set scalafmtConfig := file(sys.env("SCALA_FORMAT_REPLAY_CONFIG"))' scalafmt
)

while IFS= read -r -d '' path; do
  if ! cmp -s "$path" "$checkout/$path"; then
    printf 'Formatting replay differs for %s\n' "$path"
    exit 1
  fi
done < "$scratch/paths"
printf 'Scalafmt replay matched %s changed Scala files.\n' "$changed"
