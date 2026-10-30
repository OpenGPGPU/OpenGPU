#!/usr/bin/env bash
# Resolve Zinc's bridge before compilation, repairing only a cached bridge miss.
set -euo pipefail

bridge_version="${SCALA_BRIDGE_VERSION:-2.13.18}"
bridge_log=$(mktemp)
trap 'rm -f "$bridge_log"' EXIT

seed_bridge_cache() {
  # Coursier sometimes reports a miss even when Maven Central is healthy.
  # Drop the poisoned entries, then place a fresh jar+pom where Zinc looks.
  local bridge_cache=${COURSIER_CACHE:-"${HOME}/.cache/coursier/v1"}
  local host path base url
  rm -rf "${IVY2_CACHE:-${HOME}/.ivy2/cache}/org.scala-lang/scala2-sbt-bridge"
  for host in repo1.maven.org repo.maven.apache.org maven-central.storage-download.googleapis.com; do
    path="$bridge_cache/https/$host/maven2/org/scala-lang/scala2-sbt-bridge"
    rm -rf "$path"
    mkdir -p "$path/$bridge_version"
    base="https://$host/maven2/org/scala-lang/scala2-sbt-bridge/$bridge_version"
    if curl -fsSL "$base/scala2-sbt-bridge-$bridge_version.jar" \
         -o "$path/$bridge_version/scala2-sbt-bridge-$bridge_version.jar" &&
       curl -fsSL "$base/scala2-sbt-bridge-$bridge_version.pom" \
         -o "$path/$bridge_version/scala2-sbt-bridge-$bridge_version.pom"; then
      # Zinc/Coursier primarily consults repo1; mirror a successful seed there too.
      if [ "$host" != "repo1.maven.org" ]; then
        local repo1="$bridge_cache/https/repo1.maven.org/maven2/org/scala-lang/scala2-sbt-bridge/$bridge_version"
        mkdir -p "$repo1"
        cp -f "$path/$bridge_version/scala2-sbt-bridge-$bridge_version.jar" \
              "$path/$bridge_version/scala2-sbt-bridge-$bridge_version.pom" \
              "$repo1/"
      fi
      echo "Seeded Scala bridge $bridge_version from $host"
      return 0
    fi
  done
  echo "Failed to download scala2-sbt-bridge-$bridge_version from Maven Central" >&2
  return 1
}

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

  seed_bridge_cache
  echo "Retrying Scala bridge resolution after a bridge-only cache repair ($bridge_attempt/3)."
  sleep "$((bridge_attempt * 5))"
done
