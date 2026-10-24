#!/usr/bin/env bash
# Aggregate the current PR snapshot and all required validation lanes.
set -euo pipefail

[ "${SNAPSHOT_CURRENT:-}" = true ] || exit 1
[ "${CHECKS_RESULT:-}" = success ] || exit 1
[ -n "${SELECTED:-}" ] || exit 1
if [ "$SELECTED" = NONE ]; then
  [ "${UNIT_RESULT:-}" = skipped ] || exit 1
else
  [ "${UNIT_RESULT:-}" = success ] || exit 1
fi

[ -n "${AFFECTED:-}" ] && [ -n "${PARTITIONS:-}" ] || exit 1
if [ "$AFFECTED" = '[]' ]; then
  [ "$PARTITIONS" = '[]' ] && [ "${AFFECTED_RESULT:-}" = skipped ]
else
  [ "$PARTITIONS" != '[]' ] && [ "${AFFECTED_RESULT:-}" = success ]
fi
