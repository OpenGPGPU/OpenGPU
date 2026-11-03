#!/usr/bin/env bash
# Run one bounded guest DRM smoke case against an existing ARTI/QEMU model.
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
mode="${1:-}"
guest_source="$repo_dir/driver/tests/opengpu_drm_test.c"
case "$mode" in
    kms)        guest_env=OPENGPU_TEST_SYNC_ONLY
                marker='OPENGPU KMS sync smoke: two renders and modeset wait PASS' ;;
    dmabuf)     guest_env=OPENGPU_TEST_INVALIDATE_ONLY
                marker='OPENGPU dma-buf invalidate smoke: ordering and GPU read PASS' ;;
    bind)       guest_env=OPENGPU_TEST_BIND_ONLY
                marker='OPENGPU bind invalidate smoke: ordering and two-line GPU read PASS' ;;
    signal)     guest_env=OPENGPU_TEST_SIGNAL_ONLY
                marker='OPENGPU signal smoke: uninterruptible invalidate PASS' ;;
    resolve)    guest_env=OPENGPU_TEST_RESOLVE_ONLY
                marker='OPENGPU resolve alias smoke: same-buffer ranges PASS' ;;
    texture)    guest_env=OPENGPU_TEST_COMPUTE_TEXTURE_ONLY
                guest_source="$repo_dir/driver/tests/opengpu_compute_texture_gpu_smoke.c"
                marker='OPENGPU compute texture ioctl smoke: size, fetch and atomic PASS' ;;
    storage)    guest_env=OPENGPU_TEST_COMPUTE_STORAGE_ONLY
                guest_source="$repo_dir/driver/tests/opengpu_compute_storage_gpu_smoke.c"
                marker='OPENGPU compute storage ioctl smoke: store, load and atomic PASS' ;;
    storage-fault) guest_env=OPENGPU_TEST_STORAGE_FAULT
                guest_source="$repo_dir/driver/tests/opengpu_compute_storage_gpu_smoke.c"
                marker='OPENGPU compute storage ioctl smoke: denied write and recovery PASS' ;;
    storage-dependency) guest_env=OPENGPU_TEST_STORAGE_DEPENDENCY
                guest_source="$repo_dir/driver/tests/opengpu_compute_storage_dependency_gpu_smoke.c"
                marker='OPENGPU compute storage ioctl smoke: cross-context handoff PASS' ;;
    resources)  guest_env=OPENGPU_TEST_COMPUTE_RESOURCES_ONLY
                guest_source="$repo_dir/userspace/examples/compute_resources.c"
                marker='OPENGPU compute resources ioctl smoke: texture, storage and atomic PASS' ;;
    *) echo "usage: $0 {kms|dmabuf|bind|signal|resolve|texture|storage|storage-fault|storage-dependency|resources}" >&2; exit 2 ;;
esac

arti_dir="${ARTI_DIR:-$repo_dir/../arti}"
arti_work="${ARTI_WORK:-$repo_dir/../arti-work}"
linux_build="${LINUX_BUILD:-$arti_work/arti-linux-build}"
linux_source="${LINUX_SOURCE:-$arti_work/linux-src}"
linux_headers="${LINUX_HEADERS:-$arti_work/linux-headers}"
qemu="${QEMU:-$repo_dir/qemu-build/qemu-system-aarch64}"
integration_config="${INTEGRATION_CONFIG:-$repo_dir/driver/gpu_integration.yaml}"
width="${ARTI_SMOKE_WIDTH:-64}"
height="${ARTI_SMOKE_HEIGHT:-64}"
if [[ ! "$width" =~ ^[0-9]{1,2}$ || ! "$height" =~ ^[0-9]{1,2}$ ]]; then
    echo 'smoke dimensions must be one- or two-digit integers' >&2
    exit 2
fi
width=$((10#$width))
height=$((10#$height))
if (( width < 16 || width > 64 || height < 16 || height > 64 )); then
    echo 'smoke dimensions must each be between 16 and 64' >&2
    exit 2
fi
cross_prefix="${CROSS_COMPILE:-aarch64-linux-gnu-}"
cross_gcc="${cross_prefix}gcc"
make_bin="${MAKE:-}"
if [ -z "$make_bin" ]; then
    make_bin="$(command -v gmake || command -v make)"
fi
for file in "$qemu" "$linux_build/arch/arm64/boot/Image" \
            "$linux_source/Makefile" "$linux_headers/include/drm/drm.h" \
            "$integration_config" "$arti_dir/examples/linux_arti_driver/run_linux_test.sh"; do
    [ -f "$file" ] || { echo "missing prerequisite: $file" >&2; exit 1; }
done
command -v "$cross_gcc" >/dev/null || { echo "missing compiler: $cross_gcc" >&2; exit 1; }

artifact_root="${ARTI_SMOKE_ARTIFACTS:-$repo_dir/.artifacts/arti-small-smoke}"
mkdir -p "$artifact_root"
artifact_root="$(cd "$artifact_root" && pwd)"
run_dir="$(mktemp -d "$artifact_root/${mode}.XXXXXX")"
mkdir -p "$run_dir/driver" "$run_dir/harness" "$run_dir/root"
cp "$repo_dir/driver/Makefile" "$repo_dir"/driver/*.c "$repo_dir"/driver/*.h "$run_dir/driver/"
cp "$repo_dir/driver/tests/arti-linux-init.c" "$run_dir/harness/arti-linux-init.c"
for file in run_linux_test.sh integration_env.sh driver_preflight.sh; do
    cp "$arti_dir/examples/linux_arti_driver/$file" "$run_dir/harness/$file"
done
python3 - "$run_dir/harness/arti-linux-init.c" "$guest_env" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
source = path.read_text()
old = 'char *const envp[] = { NULL };'
if source.count(old) != 1:
    raise SystemExit('guest init environment declaration changed')
path.write_text(source.replace(old, f'char *const envp[] = {{ "{sys.argv[2]}=1", NULL }};'))
PY
if [ "$mode" = resources ]; then
    llvm_mc="${LLVM_MC:-llvm-mc}"
    llvm_objcopy="${LLVM_OBJCOPY:-llvm-objcopy}"
    command -v "$llvm_mc" >/dev/null || { echo "missing OpenGPU LLVM assembler: $llvm_mc" >&2; exit 1; }
    command -v "$llvm_objcopy" >/dev/null || { echo "missing LLVM objcopy: $llvm_objcopy" >&2; exit 1; }
    "$llvm_mc" -filetype=obj -triple=opengpu -mcpu=opengpu \
        "$repo_dir/userspace/shaders/compute_resources.S" -o "$run_dir/compute_resources.o"
    "$llvm_objcopy" -O binary --only-section=.text "$run_dir/compute_resources.o" \
        "$run_dir/root/opengpu_compute_resources.bin"
fi
set -- "$cross_gcc" -static -O2 -Wall -Wextra -Werror \
    -I"$linux_headers/include" -I"$repo_dir/driver" \
    -DTEST_WIDTH="$width" -DTEST_HEIGHT="$height" \
    -o "$run_dir/root/opengpu_drm_test" "$guest_source"
if [ "$mode" = resources ]; then
    set -- "$@" "$repo_dir/userspace/opengpu.c"
fi
"$@"
"$make_bin" -C "$linux_source" O="$linux_build" M="$run_dir/driver" \
    ARCH=arm64 CROSS_COMPILE="$cross_prefix" \
    "KCFLAGS=-DOPENGPU_DRAW_WAIT_MS=300000 -DOPENGPU_DEFAULT_WIDTH=$width -DOPENGPU_DEFAULT_HEIGHT=$height -DOPENGPU_FENCE_SLICE_MS=4" modules

echo "Smoke artifacts: $run_dir"
ARTI_DIR="$arti_dir" ARTI_WORK="$arti_work" INTEGRATION_CONFIG="$integration_config" \
LINUX_BUILD="$linux_build" QEMU="$qemu" DRIVER_KO="$run_dir/driver/gpu_drv.ko" \
DRIVER_MARKER="$marker" WORK="$run_dir/root" TIMEOUT="${ARTI_SMOKE_TIMEOUT:-300}" \
QEMU_DISPLAY=none HOLD_AFTER_TEST=1 "$run_dir/harness/run_linux_test.sh" | tee "$run_dir/host.log"
