#!/usr/bin/env bash
# Build the 640x480 FlashSim GPU, stage the GLES driver, and prove GTK ellipse
# coverage in a fresh Debian guest. Prepare Mesa and the AArch64 cross
# libraries first (see docs/GTK_COVERAGE.md).
set -euo pipefail

GPU_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ARTI_DIR="${ARTI_DIR:-$GPU_DIR/../arti}"
FLASHSIM_DIR="${FLASHSIM_DIR:-$GPU_DIR/../FlashSim}"
ARTI_WORK="${ARTI_WORK:-$GPU_DIR/../arti-work}"
PREFIX="${PREFIX:-$GPU_DIR/depends/aarch64-root}"
MESA_SRC="${MESA_SRC:-$GPU_DIR/depends/mesa}"
MESA_BUILD="${MESA_BUILD:-$GPU_DIR/depends/mesa-build}"
GPU_TILE_BINNING="${GPU_TILE_BINNING:-0}"
GTK_PROOF_DESKTOP="${GTK_PROOF_DESKTOP:-0}"
GTK_PROOF_SCISSOR="${GTK_PROOF_SCISSOR:-0}"
GTK_PROOF_SMOKE="${GTK_PROOF_SMOKE:-0}"
GTK_PROOF_KMS_MODE="${GTK_PROOF_KMS_MODE:-640x480}"
GTK_PROOF_REPRO_ONLY="${GTK_PROOF_REPRO_ONLY:-0}"
GTK_PROOF_SHADER_SUITE="${GTK_PROOF_SHADER_SUITE:-0}"
GTK_PROOF_OFFSCREEN_ONLY="${GTK_PROOF_OFFSCREEN_ONLY:-0}"
case "$GTK_PROOF_OFFSCREEN_ONLY" in
    0|1) ;;
    *) echo "GTK_PROOF_OFFSCREEN_ONLY must be 0 or 1" >&2; exit 1 ;;
esac
case "$GTK_PROOF_SHADER_SUITE" in
    0|1) ;;
    *) echo "GTK_PROOF_SHADER_SUITE must be 0 or 1" >&2; exit 1 ;;
esac
case "$GTK_PROOF_REPRO_ONLY" in
    0|1) ;;
    *) echo "GTK_PROOF_REPRO_ONLY must be 0 or 1" >&2; exit 1 ;;
esac
if [ "$GTK_PROOF_REPRO_ONLY" = 1 ] && [ -z "${GTK_PROOF_WESTON_REPRO:-}" ]; then
    echo "GTK_PROOF_REPRO_ONLY requires GTK_PROOF_WESTON_REPRO" >&2
    exit 1
fi
[ "$GTK_PROOF_DESKTOP" != 1 ] || [ "$GTK_PROOF_SCISSOR" != 1 ] || {
    echo "choose either GTK_PROOF_DESKTOP or GTK_PROOF_SCISSOR" >&2
    exit 1
}
if [ "$GTK_PROOF_SMOKE" = 1 ] && { [ "$GTK_PROOF_DESKTOP" = 1 ] || [ "$GTK_PROOF_SCISSOR" = 1 ]; }; then
    echo "GTK_PROOF_SMOKE cannot be combined with desktop or scissor proof" >&2
    exit 1
fi
INTEGRATION_CONFIG="$GPU_DIR/driver/gpu_integration_debian.yaml"
eval "$(python3 "$GPU_DIR/scripts/gpu_display_config.py" --shell "$INTEGRATION_CONFIG")"
[ "$GPU_MODE" = 640x480 ] || {
    echo "expected the 640x480 coverage profile, got $GPU_MODE" >&2
    exit 1
}
if [ "$GTK_PROOF_SMOKE" = 1 ]; then
    case "$GTK_PROOF_KMS_MODE" in
        640x480|320x240|64x64) ;;
        *) echo "GTK_PROOF_KMS_MODE must be 640x480, 320x240 or 64x64" >&2; exit 1 ;;
    esac
    GTK_KMS_WIDTH="${GTK_PROOF_KMS_MODE%x*}"
    GTK_KMS_HEIGHT="${GTK_PROOF_KMS_MODE#*x}"
else
    GTK_KMS_WIDTH=640
    GTK_KMS_HEIGHT=480
fi
if [ "$GPU_TILE_BINNING" = 1 ]; then
    DISPLAY_WORK="${DISPLAY_WORK:-$ARTI_WORK/debian-${GPU_MODE}-tile}"
    PROOF_WORK="${PROOF_WORK:-$ARTI_WORK/gtk-coverage-proof-tile}"
else
    DISPLAY_WORK="${DISPLAY_WORK:-$ARTI_WORK/debian-$GPU_MODE}"
    PROOF_WORK="${PROOF_WORK:-$ARTI_WORK/gtk-coverage-proof}"
fi
DRIVER_OUTPUT="$DISPLAY_WORK/opengpu-driver"
mkdir -p "$PROOF_WORK"
if [ "$GTK_PROOF_SMOKE" = 1 ]; then
    # Include the final scanout verdict, not just the guest's readback output.
    # Direct redirection works in restricted shells where /dev/fd process
    # substitution is unavailable; the complete transcript remains in the
    # retained gate.log artifact.
    exec > "$PROOF_WORK/gate.log" 2>&1
fi

for tool in expect ssh python3 sbt meson ninja aarch64-linux-gnu-gcc; do
    command -v "$tool" >/dev/null || { echo "missing $tool" >&2; exit 1; }
done
[ -f "$ARTI_DIR/examples/linux_arti_driver/setup_env.sh" ] || {
    echo "ARTI checkout missing at $ARTI_DIR" >&2; exit 1;
}
[ -d "$FLASHSIM_DIR/flashsim" ] || {
    echo "FlashSim checkout missing at $FLASHSIM_DIR" >&2; exit 1;
}
[ -f "$MESA_SRC/meson.build" ] || {
    echo "Mesa 22.3.6 checkout missing at $MESA_SRC" >&2; exit 1;
}
[ -f "$PREFIX/lib/pkgconfig/libdrm.pc" ] || {
    echo "AArch64 libdrm missing under $PREFIX" >&2; exit 1;
}

# Meson needs the current workspace's prefix; the checked-in cross file
# contains a developer-specific pkg_config_libdir.
CROSS="$PROOF_WORK/aarch64-linux-gnu-cross.txt"
python3 "$GPU_DIR/scripts/aarch64_cross_prefix.py" \
    "$GPU_DIR/scripts/aarch64-linux-gnu-cross.txt" "$CROSS" "$PREFIX"

if [ "${GTK_PROOF_SKIP_BUILD:-0}" != 1 ]; then
    echo '=== Build FlashSim ARTI GPU ==='
    (cd "$FLASHSIM_DIR" && python3 -m flashsim setup-circt)
    ARTI_DIR="$ARTI_DIR" FLASHSIM_DIR="$FLASHSIM_DIR" ARTI_WORK="$ARTI_WORK" \
        DISPLAY_WORK="$DISPLAY_WORK" \
        GPU_SIM=flashsim INTEGRATION_CONFIG="$INTEGRATION_CONFIG" \
        "$GPU_DIR/scripts/build_arti_debian_display.sh"

    echo '=== Build and stage guest GLES driver ==='
    PREFIX="$PREFIX" MESA_SRC="$MESA_SRC" MESA_BUILD="$MESA_BUILD" \
        CROSS="$CROSS" CROSS_GCC="$GPU_DIR/scripts/aarch64_gcc_compat.sh" \
        DRIVER_OUTPUT="$DRIVER_OUTPUT" \
        INTEGRATION_CONFIG="$INTEGRATION_CONFIG" \
        "$GPU_DIR/scripts/build_mesa_opengpu.sh"
fi

if [ "${GTK_PROOF_SKIP_BUILD:-0}" = 1 ] && [ "$GPU_TILE_BINNING" = 1 ]; then
    grep -q 'tile_binning=1' "$DISPLAY_WORK/display-mode.txt" 2>/dev/null || {
        echo "cached Debian GPU was not built with GPU_TILE_BINNING=1" >&2
        exit 1
    }
fi

for artifact in "${QEMU:-${QEMU_BUILD:-$GPU_DIR/qemu-build}/qemu-system-aarch64}" \
                "$DRIVER_OUTPUT/gpu_drv.ko" \
                "$DRIVER_OUTPUT/opengpu_gl_gtk_coverage.bin" \
                "$DRIVER_OUTPUT/opengpu_dri.so"; do
    [ -f "$artifact" ] || { echo "missing guest artifact: $artifact" >&2; exit 1; }
done
if [ "$GTK_PROOF_SCISSOR" = 1 ]; then
    artifact="$DRIVER_OUTPUT/opengpu_gl_scissor.bin"
    [ -f "$artifact" ] || { echo "missing guest artifact: $artifact" >&2; exit 1; }
fi

# Resolve the same defaults as the launcher before recording metadata.
eval "$(python3 "$GPU_DIR/scripts/qemu_cpu_config.py" \
    --qemu "${QEMU:-${QEMU_BUILD:-$GPU_DIR/qemu-build}/qemu-system-aarch64}" \
    --sim flashsim --shell)"
export QEMU_ACCEL QEMU_CPU
export ARTI_MODEL_IRQ_PUMP ARTI_MODEL_IRQ_PUMP_NS ARTI_MODEL_SETTLE_NS

# GTK is compiled inside the guest, even when the GPU/Mesa build is cached.
# Always stage current sources rather than reusing a previous Mesa build's copy.
if [ "$GTK_PROOF_SMOKE" = 1 ]; then
    for source in gtk_opengpu_smoke.c opengpu_gtk_smoke_build.sh opengpu_gtk_smoke.sh; do
        cp "$GPU_DIR/userspace/examples/$source" "$DRIVER_OUTPUT/$source"
    done
fi

BASE="${GTK_DEBIAN_BASE:-$ARTI_WORK/debian-arm64-base.qcow2}"
[ -f "$BASE" ] || { echo "Debian base image missing at $BASE" >&2; exit 1; }
QEMU_IMG="${QEMU_IMG:-${QEMU_BUILD:-$GPU_DIR/qemu-build}/qemu-img}"
[ -x "$QEMU_IMG" ] || { echo "qemu-img missing at $QEMU_IMG" >&2; exit 1; }
DISK="$PROOF_WORK/guest.qcow2"
rm -f "$DISK"
cp "$BASE" "$DISK"
"$QEMU_IMG" resize "$DISK" 10G >/dev/null

PORT="$(python3 - <<'PY'
import socket
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0))
    print(sock.getsockname()[1])
PY
)"
SERIAL_LOG="$PROOF_WORK/serial.log"
BOOT_LOG="$PROOF_WORK/boot.log"
PROOF_LOG="$PROOF_WORK/proof.log"
LOAD_LOG="$PROOF_WORK/load.log"
METADATA_LOG="$PROOF_WORK/run-metadata.txt"
: > "$SERIAL_LOG"
: > "$BOOT_LOG"
: > "$PROOF_LOG"
: > "$LOAD_LOG"
if [ "$GTK_PROOF_SMOKE" = 1 ]; then
    SCANOUT="$PROOF_WORK/gtk-smoke-scanout.ppm"
    # The GtkGLArea window is composited with Weston, so wait for the active
    # color anywhere in the complete image rather than assuming a fixed pixel.
    SCANOUT_PIXEL=""
    # Keep an explicit empty value useful for isolating guest fence behavior;
    # the qualification default matches the active GTK color.
    SCANOUT_MATCH="${GTK_PROOF_SCANOUT_MATCH-f2592e}"
    # The first enable may be the console; the full-frame match below keeps
    # that capture out while allowing later page-flip/base writes at count 1.
    SCANOUT_ENABLE="${GTK_PROOF_SCANOUT_ENABLE:-1}"
    QMP_SOCKET="$PROOF_WORK/qmp.sock"
    QMP_SCANOUT="$PROOF_WORK/gtk-smoke-qmp.ppm"
    rm -f "$SCANOUT"
    if [ "$GTK_PROOF_REPRO_ONLY" = 1 ]; then
        # Native-only timing has pixel assertions and exits before GTK. Do
        # not repeatedly scan its framebuffer for the later GTK target color.
        SCANOUT=""
        SCANOUT_MATCH=""
    fi
elif [ "$GTK_PROOF_DESKTOP" = 1 ]; then
    SCANOUT="$PROOF_WORK/desktop-scanout.ppm"
    SCANOUT_PIXEL=243044
    rm -f "$SCANOUT"
elif [ "$GTK_PROOF_SCISSOR" = 1 ]; then
    SCANOUT="$PROOF_WORK/scissor-scanout.ppm"
    SCANOUT_PIXEL=ffff00
    rm -f "$SCANOUT"
fi
{
    echo "timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "git_revision=$(git -C "$GPU_DIR" rev-parse HEAD)"
    echo "integration_config=$INTEGRATION_CONFIG"
    echo "integration_config_sha256=$(shasum -a 256 "$INTEGRATION_CONFIG" | awk '{print $1}')"
    echo "gpu_mode=$GPU_MODE gpu_tile_binning=$GPU_TILE_BINNING"
    echo "gtk_kms_mode=$GTK_PROOF_KMS_MODE gtk_kms_width=$GTK_KMS_WIDTH gtk_kms_height=$GTK_KMS_HEIGHT"
    echo "gtk_proof_smoke=$GTK_PROOF_SMOKE gtk_proof_desktop=$GTK_PROOF_DESKTOP gtk_proof_scissor=$GTK_PROOF_SCISSOR"
    echo "fence_timeout_ms=${GTK_PROOF_FENCE_TIMEOUT_MS:-1800000}"
    echo "scanout_dump=${SCANOUT:-none} scanout_enable=${SCANOUT_ENABLE:-1}"
    echo "scanout_pixel=${SCANOUT_PIXEL:-none} scanout_match=${SCANOUT_MATCH:-none}"
    echo "scanout_periodic=${GTK_PROOF_SCANOUT_PERIODIC:-0}"
    echo "qmp_socket=${QMP_SOCKET:-none} qmp_scanout=${QMP_SCANOUT:-none}"
    echo "qemu_display=${GTK_PROOF_QEMU_DISPLAY:-none}"
    echo "qemu_binary=${QEMU:-${QEMU_BUILD:-$GPU_DIR/qemu-build}/qemu-system-aarch64}"
    echo "qemu_accel=${QEMU_ACCEL:-tcg} qemu_cpu=${QEMU_CPU:-auto}"
    echo "gtk_debian_base=$BASE"
    echo "skip_build=${GTK_PROOF_SKIP_BUILD:-0} skip_coverage=${GTK_PROOF_SKIP_COVERAGE:-0}"
    echo "weston_repro=${GTK_PROOF_WESTON_REPRO:-none} repro_only=$GTK_PROOF_REPRO_ONLY"
    echo "compute_smoke=${GTK_PROOF_COMPUTE_SMOKE:-0}"
    echo "shader_suite=$GTK_PROOF_SHADER_SUITE shader_mode=$GTK_PROOF_KMS_MODE"
    echo "irq_pump=${ARTI_MODEL_IRQ_PUMP:-default} irq_pump_ns=${ARTI_MODEL_IRQ_PUMP_NS:-default}"
    echo "smoke_timeout_s=${GTK_PROOF_SMOKE_TIMEOUT_S:-0}"
    for checkout in "$GPU_DIR" "$ARTI_DIR" "$FLASHSIM_DIR" "$MESA_SRC"; do
        echo "checkout=$checkout"
        git -C "$checkout" rev-parse HEAD
        git -C "$checkout" status --porcelain --untracked-files=no
    done
} > "$METADATA_LOG"
cp "$INTEGRATION_CONFIG" "$PROOF_WORK/integration-config.yaml"
git -C "$GPU_DIR" diff --binary HEAD > "$PROOF_WORK/gpu-worktree.patch"
if [ "$GTK_PROOF_SMOKE" = 1 ]; then
    mkdir -p "$PROOF_WORK/sources"
    cp "$GPU_DIR/scripts/run_gtk_coverage_guest.sh" \
       "$GPU_DIR/scripts/qmp_screendump.py" \
       "$GPU_DIR/userspace/opengpu_retry.h" \
       "$GPU_DIR/userspace/examples/gtk_opengpu_smoke.c" "$PROOF_WORK/sources/"
    shasum -a 256 "${QEMU:-${QEMU_BUILD:-$GPU_DIR/qemu-build}/qemu-system-aarch64}" \
        "$DRIVER_OUTPUT/gpu_drv.ko" "$DRIVER_OUTPUT/opengpu_dri.so" \
        "$BASE" > "$PROOF_WORK/artifact-sha256.txt"
fi
qemu_pid=""
ready=0
cleanup() {
    result=$?
    trap - EXIT
    if [ -n "$qemu_pid" ]; then
        kill "$qemu_pid" 2>/dev/null || true
        wait "$qemu_pid" 2>/dev/null || true
    fi
    rm -f "$DISK"
    echo "gate_exit_status=$result" >> "$METADATA_LOG"
    exit "$result"
}
trap cleanup EXIT

echo "=== Boot fresh Debian guest on SSH port $PORT ==="
ARTI_DIR="$ARTI_DIR" ARTI_WORK="$ARTI_WORK" FLASHSIM_DIR="$FLASHSIM_DIR" \
    DISPLAY_WORK="$DISPLAY_WORK" DRIVER_OUTPUT="$DRIVER_OUTPUT" \
    INTEGRATION_CONFIG="$INTEGRATION_CONFIG" GPU_SIM=flashsim \
    QEMU_DISPLAY="${GTK_PROOF_QEMU_DISPLAY:-none}" OPENGPU_AUTO_DISPLAY=0 BUILD_USERSPACE=1 \
    REBUILD_CLOUDINIT=1 CLOUDINIT_PACKAGES="${CLOUDINIT_PACKAGES:-0}" \
    CIDATA="${CIDATA:-$PROOF_WORK/cloud-init.iso}" \
    MODULES_ISO="${MODULES_ISO:-$PROOF_WORK/opengpu-modules.iso}" \
    ARTI_DISPLAY_DUMP="${SCANOUT:-}" ARTI_DISPLAY_DUMP_PIXEL="${SCANOUT_PIXEL:-}" \
    ARTI_DISPLAY_DUMP_MATCH="${SCANOUT_MATCH:-}" \
    ARTI_DISPLAY_DUMP_ENABLE="${SCANOUT_ENABLE:-1}" \
    ARTI_DISPLAY_DUMP_PERIODIC="${GTK_PROOF_SCANOUT_PERIODIC:-0}" \
    ARTI_DISPLAY_REFRESH_HZ="${ARTI_DISPLAY_REFRESH_HZ:-0}" \
    QEMU_QMP_SOCKET="${QMP_SOCKET:-}" \
    DISK="$DISK" DEBIAN_BASE="$BASE" SSH_PORT="$PORT" SERIAL_LOG="$SERIAL_LOG" \
    "$GPU_DIR/scripts/run_arti_debian.sh" > "$BOOT_LOG" 2>&1 &
qemu_pid=$!

ready=0
for _ in $(seq 1 120); do
    kill -0 "$qemu_pid" 2>/dev/null || {
        echo "QEMU exited before guest became ready; see $BOOT_LOG" >&2
        exit 1
    }
    if "$GPU_DIR/scripts/gtk_coverage_guest.exp" "$PORT" 15 \
        'test -x /root/opengpu_gl_gtk_coverage.bin' >/dev/null 2>&1; then
        ready=1
        break
    fi
    sleep 5
done
[ "$ready" = 1 ] || { echo "guest did not become ready; see $SERIAL_LOG" >&2; exit 1; }

echo '=== Load guest GPU driver ==='
"$GPU_DIR/scripts/gtk_coverage_guest.exp" "$PORT" 120 \
    '/root/load_opengpu.sh && test -c /dev/dri/card0' | tee "$LOAD_LOG"

if [ "${GTK_PROOF_SKIP_COVERAGE:-0}" != 1 ]; then
    echo '=== Check GTK ellipse coverage ==='
    "$GPU_DIR/scripts/gtk_coverage_guest.exp" "$PORT" 600 \
        'LD_LIBRARY_PATH=/root LIBGL_DRIVERS_PATH=/root OPENGPU_GL_EXIT=1 /root/opengpu_gl_gtk_coverage.bin' | tee "$PROOF_LOG"
    grep -qF 'gl_gtk_coverage: mode 640x480 inside=0xff0000ff outside=0x00000000' "$PROOF_LOG"
    grep -qF 'OPENGPU GL GTK COVERAGE PASS' "$PROOF_LOG"
    echo 'GTK coverage guest proof: PASS'
fi

if [ "${GTK_PROOF_COMPUTE_SMOKE:-0}" = 1 ]; then
    "$GPU_DIR/scripts/gtk_coverage_guest.exp" "$PORT" 900 \
        'test -x /root/opengpu_compute_example && test -f /root/opengpu_compute_shader.bin && LD_LIBRARY_PATH=/root /root/opengpu_compute_example /dev/dri/card0 /root/opengpu_compute_shader.bin' \
        | tee "$PROOF_WORK/compute-smoke.log"
    grep -qF 'compute completed; RNU=1 RDN=0' "$PROOF_WORK/compute-smoke.log"
fi

# Exercise stock compiler programs in a small advertised mode before the
# compositor gate. Each executable waits for its GPU fence and checks pixels.
if [ "$GTK_PROOF_SHADER_SUITE" = 1 ] || [ "$GTK_PROOF_OFFSCREEN_ONLY" = 1 ]; then
    "$GPU_DIR/scripts/gtk_coverage_guest.exp" "$PORT" 900 \
        "LD_LIBRARY_PATH=/root LIBGL_DRIVERS_PATH=/root OPENGPU_GL_MODE=$GTK_PROOF_KMS_MODE OPENGPU_TRACE_DRAWS=1 OPENGPU_FENCE_TIMEOUT_MS=1800000 OPENGPU_GL_OFFSCREEN=1 /root/opengpu_gl_fill.bin" \
        | tee "$PROOF_WORK/offscreen.log"
    grep -qF 'OPENGPU GL OFFSCREEN PASS:' "$PROOF_WORK/offscreen.log"
    if [ "$GTK_PROOF_OFFSCREEN_ONLY" = 1 ]; then
        echo 'Offscreen attachment proof: PASS'
        exit 0
    fi
fi
if [ "$GTK_PROOF_SHADER_SUITE" = 1 ]; then
    for name in triangle transform textransform texture modulate tint opacity shade fade wash fill grade glass quad blend; do
        log="$PROOF_WORK/shader-$name.log"
        "$GPU_DIR/scripts/gtk_coverage_guest.exp" "$PORT" 900 \
            "LD_LIBRARY_PATH=/root LIBGL_DRIVERS_PATH=/root OPENGPU_GL_MODE=$GTK_PROOF_KMS_MODE OPENGPU_TRACE_DRAWS=1 OPENGPU_FENCE_TIMEOUT_MS=1800000 OPENGPU_GL_EXIT=1 /root/opengpu_gl_$name.bin" \
            | tee "$log"
        grep -qE '^OPENGPU GL.* PASS' "$log"
    done
    "$GPU_DIR/scripts/gtk_coverage_guest.exp" "$PORT" 900 \
        "LD_LIBRARY_PATH=/root LIBGL_DRIVERS_PATH=/root OPENGPU_GL_MODE=$GTK_PROOF_KMS_MODE OPENGPU_GL_GTK_SWIZZLE=1 OPENGPU_GL_EXIT=1 /root/opengpu_gl_texture.bin" \
        | tee "$PROOF_WORK/shader-texture-swizzle.log"
    grep -qF 'OPENGPU GL TEXTURE PASS' "$PROOF_WORK/shader-texture-swizzle.log"
    "$GPU_DIR/scripts/gtk_coverage_guest.exp" "$PORT" 900 \
        "LD_LIBRARY_PATH=/root LIBGL_DRIVERS_PATH=/root OPENGPU_GL_MODE=$GTK_PROOF_KMS_MODE OPENGPU_TRACE_DRAWS=1 OPENGPU_GL_PADDED_TEXTURE=1 OPENGPU_GL_EXIT=1 /root/opengpu_gl_texture.bin" \
        | tee "$PROOF_WORK/shader-texture-padded.log"
    grep -qF 'OPENGPU GL TEXTURE PASS' "$PROOF_WORK/shader-texture-padded.log"
    for variant in distinct distinct-padded distinct-swizzle; do
        extra=""
        case "$variant" in
            distinct-padded) extra="OPENGPU_GL_PADDED_TEXTURE=1" ;;
            distinct-swizzle) extra="OPENGPU_GL_GTK_SWIZZLE=1" ;;
        esac
        "$GPU_DIR/scripts/gtk_coverage_guest.exp" "$PORT" 900 \
            "LD_LIBRARY_PATH=/root LIBGL_DRIVERS_PATH=/root OPENGPU_GL_MODE=$GTK_PROOF_KMS_MODE OPENGPU_TRACE_DRAWS=1 OPENGPU_GL_DISTINCT_TEXTURE=1 $extra OPENGPU_GL_EXIT=1 /root/opengpu_gl_texture.bin" \
            | tee "$PROOF_WORK/shader-texture-$variant.log"
        grep -qF 'OPENGPU GL TEXTURE PASS' "$PROOF_WORK/shader-texture-$variant.log"
    done
    "$GPU_DIR/scripts/gtk_coverage_guest.exp" "$PORT" 900 \
        "LD_LIBRARY_PATH=/root LIBGL_DRIVERS_PATH=/root OPENGPU_GL_MODE=$GTK_PROOF_KMS_MODE OPENGPU_GL_DISTINCT_COLOR=1 OPENGPU_GL_EXIT=1 /root/opengpu_gl_fill.bin" \
        | tee "$PROOF_WORK/shader-fill-distinct.log"
    grep -qF 'OPENGPU GL FILL PASS' "$PROOF_WORK/shader-fill-distinct.log"
    "$GPU_DIR/scripts/gtk_coverage_guest.exp" "$PORT" 900 \
        "LD_LIBRARY_PATH=/root LIBGL_DRIVERS_PATH=/root OPENGPU_GL_MODE=$GTK_PROOF_KMS_MODE OPENGPU_GL_GTK_SWIZZLE_INT=1 OPENGPU_GL_EXIT=1 /root/opengpu_gl_texture.bin" \
        | tee "$PROOF_WORK/texture-swizzle-int.log"
    grep -qF 'OPENGPU GL TEXTURE PASS' "$PROOF_WORK/texture-swizzle-int.log"
    echo 'Native shader suite: PASS'
fi

# Isolate the compositor's uniform-color/opacity shader from Wayland dispatch.
if [ -n "${GTK_PROOF_WESTON_REPRO:-}" ]; then
    case "$GTK_PROOF_WESTON_REPRO" in
        small|medium|full) ;;
        *) echo 'GTK_PROOF_WESTON_REPRO must be small, medium, or full' >&2; exit 1 ;;
    esac
    "$GPU_DIR/scripts/gtk_coverage_guest.exp" "$PORT" 3900 \
        "LD_LIBRARY_PATH=/root LIBGL_DRIVERS_PATH=/root OPENGPU_TRACE_DRAWS=1 OPENGPU_FENCE_TIMEOUT_MS=1800000 OPENGPU_GL_EXIT=1 OPENGPU_GL_WESTON_REPRO=$GTK_PROOF_WESTON_REPRO /root/opengpu_gl_fill.bin" \
        | tee "$PROOF_WORK/weston-repro.log"
    grep -qF 'OPENGPU GL FILL PASS' "$PROOF_WORK/weston-repro.log"
    if [ "$GTK_PROOF_REPRO_ONLY" = 1 ]; then
        echo 'Native GLES reproduction proof: PASS'
        exit 0
    fi
fi

if [ "$GTK_PROOF_SMOKE" = 1 ]; then
    GTK_SMOKE_LOG="$PROOF_WORK/gtk-smoke.log"
    GTK_SMOKE_TIMEOUT_RAW="${GTK_PROOF_FENCE_TIMEOUT_MS:-1800000}"
    [[ "$GTK_SMOKE_TIMEOUT_RAW" =~ ^[0-9]+$ ]] &&
        [ "${#GTK_SMOKE_TIMEOUT_RAW}" -le 10 ] &&
        [ "$GTK_SMOKE_TIMEOUT_RAW" -le 2147483647 ] || {
        echo 'GTK_PROOF_FENCE_TIMEOUT_MS must be 0..2147483647' >&2
        exit 1
    }
    if [ "$GTK_SMOKE_TIMEOUT_RAW" = 0 ]; then
        GTK_SMOKE_TIMEOUT=2147483647
    else
        GTK_SMOKE_TIMEOUT=$((10#$GTK_SMOKE_TIMEOUT_RAW))
    fi
    # Startup and redraw contain multiple synchronous GPU submissions.
    # Keep the whole-test budget separate from the per-fence budget.
    GTK_SMOKE_WAIT="${GTK_PROOF_SMOKE_TIMEOUT_S:-0}"
    [[ "$GTK_SMOKE_WAIT" =~ ^[0-9]+$ ]] &&
        [ "${#GTK_SMOKE_WAIT}" -le 7 ] &&
        [ "$GTK_SMOKE_WAIT" -le 604800 ] || {
        echo 'GTK_PROOF_SMOKE_TIMEOUT_S must be 0..604800' >&2
        exit 1
    }
    GTK_SMOKE_WAIT=$((10#$GTK_SMOKE_WAIT))
    if [ "$GTK_SMOKE_WAIT" = 0 ]; then
        echo '=== Check GTK Wayland/OpenGPU smoke window (log-driven, no total timeout) ==='
    else
        echo "=== Check GTK Wayland/OpenGPU smoke window (budget ${GTK_SMOKE_WAIT}s) ==="
    fi
    GTK_SMOKE_CMD=$(cat <<'EOF'
set -eu
/root/opengpu_gtk_smoke_build.sh
mkdir -p /run/user/0
chmod 700 /run/user/0
export XDG_RUNTIME_DIR=/run/user/0 WAYLAND_DISPLAY=opengpu-gl GDK_BACKEND=wayland GDK_GL=gles
export LD_LIBRARY_PATH=/root LIBGL_DRIVERS_PATH=/root MESA_LOADER_DRIVER_OVERRIDE=opengpu
export OPENGPU_FENCE_TIMEOUT_MS=1800000
export OPENGPU_TRACE_DRAWS=1
: >/tmp/weston-gtk-smoke.log
: >/tmp/gtk-client.log
tail -n +1 -f /tmp/weston-gtk-smoke.log /tmp/gtk-client.log & logger=$!
w= a=
cleanup() {
    kill ${a:-} ${w:-} "$logger" 2>/dev/null || true
}
trap cleanup EXIT
# A simulated draw can exceed the normal five-minute desktop idle timeout.
# Keep the desktop visible and avoid spending GPU time on shell fades.
cat >/tmp/opengpu-gtk-weston.ini <<'WESTON_CONFIG'
[core]
idle-time=0
[shell]
startup-animation=none
animation=none
close-animation=none
focus-animation=none
[output]
name=Virtual-1
mode=@GTK_KMS_MODE@
WESTON_CONFIG
weston --config=/tmp/opengpu-gtk-weston.ini --backend=drm-backend.so --tty=1 --socket=opengpu-gl >/tmp/weston-gtk-smoke.log 2>&1 & w=$!
for i in $(seq 1 60); do
    test -S /run/user/0/opengpu-gl && break
    kill -0 "$w" 2>/dev/null || exit 1
    sleep 1
done
test -S /run/user/0/opengpu-gl
if [ '@GTK_KMS_MODE@' = 64x64 ]; then
    export OPENGPU_GTK_COMPACT=1
fi
OPENGPU_GTK_AUTOTEST=1 /root/opengpu_gtk_smoke.bin >/tmp/gtk-client.log 2>&1 & a=$!
if [ @SMOKE_TIMEOUT_S@ = 0 ]; then
    deadline=0
else
    deadline=$(( $(date +%s) + @SMOKE_TIMEOUT_S@ ))
fi
while [ "$deadline" = 0 ] || [ "$(date +%s)" -lt "$deadline" ]; do
    if grep -qF 'OPENGPU GTK FRAME PASS: active' /tmp/gtk-client.log; then
        cat /tmp/gtk-client.log
        # Keep both producers alive for the host QMP presentation check.
        # QEMU teardown in the outer EXIT trap owns their final cleanup.
        trap 'kill "$logger" 2>/dev/null || true' EXIT
        exit 0
    fi
    if grep -qE 'OPENGPU GTK .*ERROR|shader compile failed|program link failed' /tmp/gtk-client.log; then
        cat /tmp/gtk-client.log
        exit 1
    fi
    kill -0 "$w" 2>/dev/null && kill -0 "$a" 2>/dev/null || exit 1
    sleep 30
done
cat /tmp/gtk-client.log
exit 1
EOF
)
    GTK_SMOKE_CMD="${GTK_SMOKE_CMD/OPENGPU_FENCE_TIMEOUT_MS=1800000/OPENGPU_FENCE_TIMEOUT_MS=$GTK_SMOKE_TIMEOUT}"
    GTK_SMOKE_CMD="${GTK_SMOKE_CMD//@SMOKE_TIMEOUT_S@/$GTK_SMOKE_WAIT}"
    GTK_SMOKE_CMD="${GTK_SMOKE_CMD//@GTK_KMS_MODE@/$GTK_PROOF_KMS_MODE}"
    GTK_EXPECT_TIMEOUT=-1
    [ "$GTK_SMOKE_WAIT" = 0 ] || GTK_EXPECT_TIMEOUT=$((GTK_SMOKE_WAIT + 300))
    if ! "$GPU_DIR/scripts/gtk_coverage_guest.exp" "$PORT" "$GTK_EXPECT_TIMEOUT" "$GTK_SMOKE_CMD" \
        | tee "$GTK_SMOKE_LOG"; then
        "$GPU_DIR/scripts/gtk_coverage_guest.exp" "$PORT" 120 \
            'command -v gcc pkg-config weston || true; cat /tmp/gtk-client.log /tmp/weston-gtk-smoke.log 2>/dev/null || true; dmesg' \
            > "$PROOF_WORK/failure-guest.log" 2>&1 || true
        exit 1
    fi
    grep -qF 'OPENGPU GTK FRAME PASS' "$GTK_SMOKE_LOG"
    grep -qF 'OPENGPU GTK REDRAW: active' "$GTK_SMOKE_LOG"
    grep -qF 'OPENGPU GTK FRAME PASS: idle' "$GTK_SMOKE_LOG"
    grep -qF 'OPENGPU GTK FRAME PASS: active' "$GTK_SMOKE_LOG"
    grep -qE 'opengpu: complete draw=[0-9]+ status=0 ' "$GTK_SMOKE_LOG"
    # Save the guest-side logs before QMP polling or teardown can obscure the
    # compositor's final state.
    "$GPU_DIR/scripts/gtk_coverage_guest.exp" "$PORT" 120 \
        'cat /tmp/gtk-client.log /tmp/weston-gtk-smoke.log; dmesg' \
        > "$PROOF_WORK/final-guest.log" 2>&1 || true
    echo 'GTK Wayland/OpenGPU smoke proof: PASS'
fi

if [ "$GTK_PROOF_SMOKE" = 1 ]; then
    # ARTI's guest-memory dump is the authoritative QEMU framebuffer capture.
    # It is refreshed by QEMU's display timer and matched against the active
    # client colour, so client readback alone is deliberately insufficient.
    if [ "$GTK_SMOKE_WAIT" = 0 ]; then
        deadline=0
    else
        deadline=$(( $(date +%s) + GTK_SMOKE_WAIT ))
    fi
    gtk_scanout_verified=0
    GTK_VERIFIED_SCANOUT="$PROOF_WORK/gtk-smoke-verified.ppm"
    while [ "$deadline" = 0 ] || [ "$(date +%s)" -lt "$deadline" ]; do
        if python3 "$GPU_DIR/scripts/verify_gtk_scanout.py" "$SCANOUT" \
            "$GTK_KMS_WIDTH" "$GTK_KMS_HEIGHT" "$GTK_SMOKE_LOG" \
            --save-frame "$GTK_VERIFIED_SCANOUT" \
            --output "$PROOF_WORK/gtk-scanout-verification.json" \
            >"$PROOF_WORK/scanout-last-check.log" 2>&1
        then
            gtk_scanout_verified=1
            SCANOUT="$GTK_VERIFIED_SCANOUT"
            break
        fi
        # Keep a QMP capture when the selected backend exposes one. Headless
        # QEMU builds may return a non-scanout console surface; that optional
        # image is retained for diagnosis while the ARTI dump remains the gate.
        python3 "$GPU_DIR/scripts/qmp_screendump.py" "$QMP_SOCKET" "$QMP_SCANOUT" \
            >"$PROOF_WORK/qmp-last-attempt.log" 2>&1 || true
        sleep 10
    done
    [ "$gtk_scanout_verified" = 1 ] || {
        echo "GTK active client content did not reach QEMU scanout" >&2
        exit 1
    }
fi

if [ "$GTK_PROOF_DESKTOP" = 1 ]; then
    DESKTOP_LOG="$PROOF_WORK/desktop.log"
    echo '=== Check multi-draw desktop and present ==='
    "$GPU_DIR/scripts/gtk_coverage_guest.exp" "$PORT" 1800 \
        '/root/opengpu_pipe_desktop /dev/dri/card0 /root/opengpu_fragment_tint.bin' \
        | tee "$DESKTOP_LOG"
    grep -qF 'OPENGPU DESKTOP PASS: 640x480 ' "$DESKTOP_LOG"
    echo 'Desktop guest proof: PASS'
elif [ "$GTK_PROOF_SCISSOR" = 1 ]; then
    SCISSOR_LOG="$PROOF_WORK/scissor.log"
    echo '=== Check GL scissor draw and present ==='
    "$GPU_DIR/scripts/gtk_coverage_guest.exp" "$PORT" 1800 \
        'LD_LIBRARY_PATH=/root LIBGL_DRIVERS_PATH=/root OPENGPU_GL_EXIT=1 /root/opengpu_gl_scissor.bin' \
        | tee "$SCISSOR_LOG"
    grep -qF 'OPENGPU GL SCISSOR PASS' "$SCISSOR_LOG"
    echo 'Scissor guest proof: PASS'
fi

if [ -n "${SCANOUT:-}" ]; then
    # Captures already exist: ARTI dumps on scanout register writes and QMP
    # dumps on request. Teardown is not itself a capture trigger.
    SCENE=desktop
    [ "$GTK_PROOF_SMOKE" != 1 ] || SCENE=gtk-smoke
    [ "$GTK_PROOF_SCISSOR" != 1 ] || SCENE=scissor
    python3 - "$SCANOUT" "${GTK_PROOF_SCANOUT_REFERENCE:-}" "$SCENE" "$GTK_KMS_WIDTH" "$GTK_KMS_HEIGHT" "${GTK_PROOF_SCANOUT_PERIODIC:-0}" "$GPU_DIR/scripts/verify_gtk_scanout.py" "${GTK_SMOKE_LOG:-}" <<'PY'
import pathlib
import re
import subprocess
import sys

path = pathlib.Path(sys.argv[1])
scene = sys.argv[3]
expected_width = int(sys.argv[4])
expected_height = int(sys.argv[5])
periodic = sys.argv[6] == "1"
if not path.is_file():
    sys.exit(f"missing QEMU scanout dump: {path}")
data = path.read_bytes()
header = re.match(rb"P6\n([0-9]+) ([0-9]+)\n255\n", data)
if header is None or tuple(map(int, header.groups())) != (expected_width, expected_height):
    sys.exit(f"invalid {expected_width}x{expected_height} scanout dump: {path}")
pixels = data[header.end():]
if len(pixels) != expected_width * expected_height * 3:
    sys.exit(f"incomplete scanout dump: {path}")
def pixel(x, y):
    index = 3 * (y * expected_width + x)
    return pixels[index:index + 3]

if scene == "gtk-smoke":
    subprocess.run([sys.executable, sys.argv[7], str(path),
                    str(expected_width), str(expected_height), sys.argv[8]], check=True)
elif scene == "desktop":
    if pixel(1, 1) != bytes.fromhex("243044"):
        sys.exit(f"desktop panel missing from scanout: {path}")
    colors = {pixels[i:i + 3] for i in range(0, len(pixels), 3)}
    for label, color in (("window", "f2efe8"), ("cursor", "ffe14a"),
                         ("triangle", "ffbf40")):
        if bytes.fromhex(color) not in colors:
            sys.exit(f"desktop {label} missing from scanout: {path}")
elif scene == "scissor":
    for x, y, color in ((1, 1, "ffff00"), (8, 8, "ffff00"),
                        (8, 48, "0000ff"), (632, 8, "0000ff")):
        if pixel(x, y) != bytes.fromhex(color):
            sys.exit(f"scissor scanout pixel ({x},{y}) differs: {path}")
if sys.argv[2]:
    reference = pathlib.Path(sys.argv[2])
    if not reference.is_file():
        sys.exit(f"missing scanout reference: {reference}")
    if data != reference.read_bytes():
        sys.exit(f"{scene} scanout differs from {reference}")
print(f"{scene.capitalize()} scanout proof: PASS ({path})")
PY
fi
