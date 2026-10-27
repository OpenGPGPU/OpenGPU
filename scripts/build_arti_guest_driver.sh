#!/usr/bin/env bash
# Rebuild only the guest module with the ARTI simulation watchdog/polling ABI.
# Usage: scripts/build_arti_guest_driver.sh OUTPUT_DIRECTORY
set -euo pipefail
[ "$#" = 1 ] || { echo "usage: $0 OUTPUT_DIRECTORY" >&2; exit 2; }
GPU_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ARTI_DIR="${ARTI_DIR:-$GPU_DIR/../arti}"
ARTI_WORK="${ARTI_WORK:-$GPU_DIR/../arti-work}"
LINUX_BUILD="${LINUX_BUILD:-$ARTI_WORK/arti-linux-build}"
INTEGRATION_CONFIG="${INTEGRATION_CONFIG:-$GPU_DIR/driver/gpu_integration_debian.yaml}"
eval "$(python3 "$GPU_DIR/scripts/gpu_display_config.py" --shell "$INTEGRATION_CONFIG")"
GPU_FRAG_CORE="${GPU_FRAG_CORE:-1}"
draw_default=$((60000 * GPU_WIDTH * GPU_HEIGHT / 256))
[ "$GPU_FRAG_CORE" != 1 ] || draw_default=$((draw_default * 15))
ARTI_GPU_DRAW_WAIT_MS="${ARTI_GPU_DRAW_WAIT_MS:-$draw_default}"
ARTI_GPU_FENCE_SLICE_MS="${ARTI_GPU_FENCE_SLICE_MS:-4}"
stage="$(mktemp -d "${TMPDIR:-/tmp}/opengpu-sim-driver.XXXXXX")"
trap 'rm -rf "$stage"' EXIT
cp "$GPU_DIR/driver/Makefile" "$GPU_DIR"/driver/*.c "$GPU_DIR"/driver/*.h "$stage/"
flags="${KCFLAGS:-} -DOPENGPU_DRAW_WAIT_MS=$ARTI_GPU_DRAW_WAIT_MS -DOPENGPU_DEFAULT_WIDTH=$GPU_WIDTH -DOPENGPU_DEFAULT_HEIGHT=$GPU_HEIGHT -DOPENGPU_FENCE_SLICE_MS=$ARTI_GPU_FENCE_SLICE_MS"
LINUX_BUILD="$LINUX_BUILD" ARTI_DIR="$ARTI_DIR" KCFLAGS="$flags" \
    "$ARTI_DIR/examples/linux_arti_driver/build_driver.sh" \
    --dir "$stage" --module gpu_drv --output "$1"
python3 - "$1" "$flags" "$INTEGRATION_CONFIG" <<'PY'
import hashlib,json,pathlib,sys
out=pathlib.Path(sys.argv[1]);module=out/'gpu_drv.ko'
with module.open('rb') as stream:
    digest=hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b''):
        digest.update(chunk)
(out/'simulation-driver.json').write_text(json.dumps(dict(
    module_sha256=digest.hexdigest(),kcflags=sys.argv[2],integration_config=sys.argv[3],
    scope='Simulation watchdog and sliced fence waits; real hardware defaults unchanged'),indent=2)+'\n')
PY
