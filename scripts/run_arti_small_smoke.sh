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
    compute-fence) guest_env=OPENGPU_TEST_COMPUTE_FENCE_ONLY
                guest_source="$repo_dir/driver/tests/opengpu_compute_fence_gpu_smoke.c"
                marker='OPENGPU compute fence ioctl smoke: store and load PASS' ;;
    compute-workgroup) guest_env=OPENGPU_TEST_COMPUTE_WORKGROUP_ONLY
                guest_source="$repo_dir/driver/tests/opengpu_compute_workgroup_gpu_smoke.c"
                marker='OPENGPU compute workgroup ioctl smoke: limits and IDs PASS' ;;
    counter)    guest_env=OPENGPU_TEST_COMPUTE_COUNTER_ONLY
                guest_source="$repo_dir/userspace/examples/compute_counter.c"
                marker='OPENGPU compute counter ioctl smoke: operations, fault and recovery PASS' ;;
    counter-resources) guest_env=OPENGPU_TEST_COMPUTE_COUNTER_RESOURCES_ONLY
                guest_source="$repo_dir/driver/tests/opengpu_compute_counter_resources_gpu_smoke.c"
                marker='OPENGPU counter resources smoke: texture, storage, image, fault and recovery PASS' ;;
    uniform-counter-source) guest_env=OPENGPU_TEST_UNIFORM_COUNTER_SOURCE_ONLY
                guest_source="$repo_dir/driver/tests/opengpu_compute_uniform_counter_source_gpu_smoke.c"
                marker='OPENGPU Linux uniform counter source: full views, actual instructions, fault and fresh reuse PASS' ;;
    compute-barrier) guest_env=OPENGPU_TEST_COMPUTE_BARRIER_ONLY
                guest_source="$repo_dir/driver/tests/opengpu_compute_barrier_gpu_smoke.c"
                marker='OPENGPU compute barrier ioctl smoke: two warps PASS' ;;
    image-atomic-fence) guest_env=OPENGPU_TEST_COMPUTE_IMAGE_ATOMIC_FENCE_ONLY
                guest_source="$repo_dir/driver/tests/opengpu_compute_image_atomic_fence_gpu_smoke.c"
                marker='OPENGPU compute image atomic fence smoke: store, add and load PASS' ;;
    storage-atomic) guest_env=OPENGPU_TEST_COMPUTE_STORAGE_ATOMIC_ONLY
                guest_source="$repo_dir/driver/tests/opengpu_compute_storage_atomic_gpu_smoke.c"
                marker='OPENGPU storage atomic smoke: add and old value PASS' ;;
    image-atomic) guest_env=OPENGPU_TEST_COMPUTE_IMAGE_ATOMIC_ONLY
                guest_source="$repo_dir/driver/tests/opengpu_compute_image_atomic_gpu_smoke.c"
                marker='OPENGPU compute image atomic smoke: add and old value PASS' ;;
    multiview-object) guest_env=OPENGPU_TEST_COMPUTE_MULTIVIEW_OBJECT_ONLY
                guest_source="$repo_dir/driver/tests/opengpu_compute_multiview_object_gpu_smoke.c"
                marker='OPENGPU multiview object smoke: image and storage windows PASS' ;;
    image-storage-ranges) guest_env=OPENGPU_TEST_COMPUTE_IMAGE_STORAGE_RANGES_ONLY
                guest_source="$repo_dir/driver/tests/opengpu_compute_image_storage_ranges_gpu_smoke.c"
                marker='OPENGPU image/storage ranges smoke: both directions PASS' ;;
    image-storage-alias) guest_env=OPENGPU_TEST_COMPUTE_IMAGE_STORAGE_ALIAS_ONLY
                guest_source="$repo_dir/driver/tests/opengpu_compute_image_storage_alias_gpu_smoke.c"
                marker='OPENGPU image/storage alias smoke: both directions PASS' ;;
    image-resource-ranges) guest_env=OPENGPU_TEST_COMPUTE_IMAGE_RESOURCE_RANGES_ONLY
                guest_source="$repo_dir/driver/tests/opengpu_compute_image_resource_ranges_gpu_smoke.c"
                marker='OPENGPU image resource ranges smoke: three-client exchange PASS' ;;
    image-resources) guest_env=OPENGPU_TEST_COMPUTE_IMAGE_RESOURCES_ONLY
                guest_source="$repo_dir/driver/tests/opengpu_compute_image_resources_gpu_smoke.c"
                marker='OPENGPU compute image resources smoke: texture, storage and image PASS' ;;
    image-texture-alias) guest_env=OPENGPU_TEST_COMPUTE_IMAGE_TEXTURE_ALIAS_ONLY
                guest_source="$repo_dir/driver/tests/opengpu_compute_image_texture_alias_gpu_smoke.c"
                marker='OPENGPU image/texture alias smoke: fenced refetch PASS' ;;
    image-texture) guest_env=OPENGPU_TEST_COMPUTE_IMAGE_TEXTURE_ONLY
                guest_source="$repo_dir/driver/tests/opengpu_compute_image_texture_gpu_smoke.c"
                marker='OPENGPU compute textured image smoke: fetch, store and load PASS' ;;
    image-alias) guest_env=OPENGPU_TEST_COMPUTE_IMAGE_ALIAS_ONLY
                guest_source="$repo_dir/driver/tests/opengpu_compute_image_alias_gpu_smoke.c"
                marker='OPENGPU compute image alias smoke: permission and recovery PASS' ;;
    image-barrier) guest_env=OPENGPU_TEST_COMPUTE_IMAGE_BARRIER_ONLY
                guest_source="$repo_dir/driver/tests/opengpu_compute_image_barrier_gpu_smoke.c"
                marker='OPENGPU compute image barrier smoke: two warps PASS' ;;
    image) guest_env=OPENGPU_TEST_COMPUTE_IMAGE_ONLY
                guest_source="$repo_dir/driver/tests/opengpu_compute_image_gpu_smoke.c"
                marker='OPENGPU compute image ioctl smoke: permission and recovery PASS' ;;
    resources-alias) guest_env=OPENGPU_TEST_RESOURCES_ALIAS_ONLY
                guest_source="$repo_dir/driver/tests/opengpu_compute_resources_alias_gpu_smoke.c"
                marker='OPENGPU resources alias ioctl smoke: two warps PASS' ;;
    resources-fence) guest_env=OPENGPU_TEST_RESOURCES_FENCE_ONLY
                guest_source="$repo_dir/driver/tests/opengpu_compute_resources_fence_gpu_smoke.c"
                marker='OPENGPU resources fence ioctl smoke: two warps PASS' ;;
    resources-barrier) guest_env=OPENGPU_TEST_RESOURCES_BARRIER_ONLY
                guest_source="$repo_dir/driver/tests/opengpu_compute_resources_barrier_gpu_smoke.c"
                marker='OPENGPU resources barrier ioctl smoke: two warps PASS' ;;
    storage-barrier) guest_env=OPENGPU_TEST_STORAGE_BARRIER_ONLY
                guest_source="$repo_dir/driver/tests/opengpu_compute_storage_barrier_gpu_smoke.c"
                marker='OPENGPU storage barrier ioctl smoke: two warps PASS' ;;
    storage-barrier-fault) guest_env=OPENGPU_TEST_STORAGE_BARRIER_FAULT
                guest_source="$repo_dir/driver/tests/opengpu_compute_storage_barrier_gpu_smoke.c"
                marker='OPENGPU storage barrier ioctl smoke: fault and recovery PASS' ;;
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
    *) echo "usage: $0 {kms|dmabuf|bind|signal|resolve|texture|image|image-alias|image-barrier|image-texture|image-texture-alias|image-resources|image-resource-ranges|image-storage-alias|image-storage-ranges|multiview-object|image-atomic|storage-atomic|image-atomic-fence|compute-fence|compute-workgroup|counter|counter-resources|uniform-counter-source|compute-barrier|resources-alias|resources-fence|resources-barrier|storage-barrier|storage-barrier-fault|storage|storage-fault|storage-dependency|resources}" >&2; exit 2 ;;
esac

default_extent=64
if [ "$mode" = uniform-counter-source ]; then
    if [ "${OPENGPU_NATIVE_LINUX_RESOURCE_QUALIFICATION:-}" != 1 ]; then
        echo 'uniform-counter-source requires OPENGPU_NATIVE_LINUX_RESOURCE_QUALIFICATION=1' >&2
        exit 2
    fi
    if [ "${ARTI_GUEST_RAM_MIB:-2048}" != 2048 ] || \
       [ "${ARTI_KERNEL_CMDLINE_EXTRA:-cma=768M@0x60000000}" != 'cma=768M@0x60000000' ]; then
        echo 'uniform-counter-source requires exactly 2048 MiB RAM and cma=768M@0x60000000' >&2
        exit 2
    fi
    export ARTI_GUEST_RAM_MIB=2048 ARTI_KERNEL_CMDLINE_EXTRA=cma=768M@0x60000000
    export ARTI_NATIVE_REQUIRE_TWO_CUS=1
    default_extent=16
fi

arti_dir="${ARTI_DIR:-$repo_dir/../arti}"
arti_work="${ARTI_WORK:-$repo_dir/../arti-work}"
linux_build="${LINUX_BUILD:-$arti_work/arti-linux-build}"
linux_source="${LINUX_SOURCE:-$arti_work/linux-src}"
linux_headers="${LINUX_HEADERS:-$arti_work/linux-headers}"
qemu="${QEMU:-$repo_dir/qemu-build/qemu-system-aarch64}"
smoke_dtb="${ARTI_SMOKE_DTB:-}"
integration_config="${INTEGRATION_CONFIG:-$repo_dir/driver/gpu_integration.yaml}"
width="${ARTI_SMOKE_WIDTH:-$default_extent}"
height="${ARTI_SMOKE_HEIGHT:-$default_extent}"
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
if [ "$mode" = uniform-counter-source ] && (( width != 16 || height != 16 )); then
    echo 'uniform-counter-source model and probe extent must both be 16x16' >&2
    exit 2
fi
draw_wait_ms="${ARTI_SMOKE_DRAW_WAIT_MS:-300000}"
if [[ ! "$draw_wait_ms" =~ ^[0-9]{1,6}$ ]] || (( 10#$draw_wait_ms < 1 || 10#$draw_wait_ms > 900000 )); then
    echo 'smoke draw timeout must be between 1 and 900000 ms' >&2
    exit 2
fi
draw_wait_ms=$((10#$draw_wait_ms))
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
if [ -n "$smoke_dtb" ]; then
    qemu="$(cd "$(dirname "$qemu")" && pwd)/$(basename "$qemu")"
    [ -f "$smoke_dtb" ] || { echo "missing guest DTB: $smoke_dtb" >&2; exit 1; }
    smoke_dtb="$(cd "$(dirname "$smoke_dtb")" && pwd)/$(basename "$smoke_dtb")"
fi
if [ "$mode" = uniform-counter-source ]; then
    [ -n "$smoke_dtb" ] || { echo 'uniform-counter-source requires its private matching guest DTB' >&2; exit 2; }
    dtc="${DTC:-$linux_build/scripts/dtc/dtc}"
    python3 - "$repo_dir/scripts" "$smoke_dtb" "$dtc" <<'PY'
import subprocess
import sys
sys.path.insert(0, sys.argv[1])
from build_arti_native_compute_model import check_guest_dtb
check_guest_dtb(subprocess.check_output([sys.argv[3], '-I', 'dtb', '-O', 'dts', sys.argv[2]], text=True))
PY
fi

artifact_root="${ARTI_SMOKE_ARTIFACTS:-$repo_dir/.artifacts/arti-small-smoke}"
mkdir -p "$artifact_root"
artifact_root="$(cd "$artifact_root" && pwd)"
run_dir="$(mktemp -d "$artifact_root/${mode}.XXXXXX")"
mkdir -p "$run_dir/driver" "$run_dir/harness" "$run_dir/root"
if [ -n "$smoke_dtb" ]; then
    export OPENGPU_SMOKE_QEMU="$qemu" OPENGPU_SMOKE_DTB="$smoke_dtb"
    cat > "$run_dir/qemu-with-dtb" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
exec "$OPENGPU_SMOKE_QEMU" "$@" -dtb "$OPENGPU_SMOKE_DTB"
SH
    chmod +x "$run_dir/qemu-with-dtb"
    qemu="$run_dir/qemu-with-dtb"
fi
cp "$repo_dir/driver/Makefile" "$repo_dir"/driver/*.c "$repo_dir"/driver/*.h "$run_dir/driver/"
cp "$repo_dir/driver/tests/arti-linux-init.c" "$run_dir/harness/arti-linux-init.c"
for file in run_linux_test.sh integration_env.sh driver_preflight.sh; do
    cp "$arti_dir/examples/linux_arti_driver/$file" "$run_dir/harness/$file"
done
if [ "$mode" = counter ] || [ "$mode" = counter-resources ] || [ "$mode" = uniform-counter-source ]; then
    # A printed marker alone is insufficient if QEMU later times out or fails.
    python3 - "$run_dir/harness/run_linux_test.sh" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
source = path.read_text()
old = 'QEMU_RC=$?\nset -e\n'
if source.count(old) != 1:
    raise SystemExit('guest QEMU exit handling changed')
path.write_text(source.replace(old, old + '''if [ "$QEMU_RC" -ne 0 ]; then
    echo "QEMU did not exit cleanly: $QEMU_RC" >&2
    tail -n 30 "$SERIAL_LOG" >&2 || true
    exit "$QEMU_RC"
fi
'''))
PY
fi
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
if [ "$mode" = resources ] || [ "$mode" = image-resources ] || \
   [ "$mode" = counter ] || [ "$mode" = counter-resources ]; then
    set -- "$@" "$repo_dir/userspace/opengpu.c"
fi
"$@"
"$make_bin" -C "$linux_source" O="$linux_build" M="$run_dir/driver" \
    ARCH=arm64 CROSS_COMPILE="$cross_prefix" -j1 \
    "KCFLAGS=-DOPENGPU_DRAW_WAIT_MS=$draw_wait_ms -DOPENGPU_DEFAULT_WIDTH=$width -DOPENGPU_DEFAULT_HEIGHT=$height -DOPENGPU_FENCE_SLICE_MS=4" modules

echo "Smoke artifacts: $run_dir"
ARTI_DIR="$arti_dir" ARTI_WORK="$arti_work" INTEGRATION_CONFIG="$integration_config" \
LINUX_BUILD="$linux_build" QEMU="$qemu" DRIVER_KO="$run_dir/driver/gpu_drv.ko" \
DRIVER_MARKER="$marker" WORK="$run_dir/root" TIMEOUT="${ARTI_SMOKE_TIMEOUT:-300}" \
QEMU_DISPLAY=none HOLD_AFTER_TEST=1 "$run_dir/harness/run_linux_test.sh" 2>&1 | tee "$run_dir/host.log"
if [ "$mode" = counter ] || [ "$mode" = counter-resources ] || [ "$mode" = uniform-counter-source ]; then
    grep -qF "$marker" "$run_dir/root/serial.log"
    grep -qF 'ARTI Linux init: done, powering off' "$run_dir/root/serial.log"
    grep -qF 'reboot: Power down' "$run_dir/root/serial.log"
    if grep -qF 'OPENGPU USERSPACE DRM FAIL' "$run_dir/root/serial.log"; then
        echo 'counter guest reported a failure' >&2
        exit 1
    fi
fi
if [ "$mode" = uniform-counter-source ]; then
    grep -qF 'ARTI_NATIVE_COMPUTE_OBSERVER actualProfile=true peakActiveCUs=2 status=PASS' "$run_dir/host.log"
    grep -qF 'NATIVE_LINUX_RESOURCES epoch=1 ' "$run_dir/root/serial.log"
    grep -qF 'NATIVE_LINUX_RESOURCES_FAULT epoch=1 ' "$run_dir/root/serial.log"
    grep -qF 'NATIVE_LINUX_RESOURCES epoch=3 ' "$run_dir/root/serial.log"
fi
