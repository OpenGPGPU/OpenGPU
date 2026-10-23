#!/usr/bin/env bash
# Resolve Zinc's bridge before compilation, repairing only a cached bridge miss.
set -euo pipefail

bridge_log=$(mktemp)
trap 'rm -f "$bridge_log"' EXIT

for bridge_attempt in 1 2 3; do
  bridge_status=0
  sbt --batch 'show scalaCompilerBridgeBinaryJar' > "$bridge_log" 2>&1 || bridge_status=$?
  cat "$bridge_log"
  if [ "$bridge_status" -eq 0 ]; then
    exit 0
  fi
  if [ "$bridge_attempt" -eq 3 ] ||
      ! grep -Fq 'Missing org.scala-lang:scala2-sbt-bridge:' "$bridge_log"; then
    exit "$bridge_status"
  fi

  bridge_cache=${COURSIER_CACHE:-"${HOME}/.cache/coursier/v1"}
  for bridge_repository in repo1.maven.org repo.maven.apache.org; do
    rm -rf "$bridge_cache/https/$bridge_repository/maven2/org/scala-lang/scala2-sbt-bridge"
  done
  echo "Retrying Scala bridge resolution after a bridge-only cache repair ($bridge_attempt/3)."
  sleep "$((bridge_attempt * 5))"
done
