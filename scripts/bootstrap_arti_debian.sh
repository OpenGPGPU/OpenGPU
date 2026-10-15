#!/usr/bin/env bash
# Bring a fresh OpenGPU checkout up to a bootable ARTI/QEMU Debian desktop.
#
# This is an orchestrator, not a re-implementation. The platform half (QEMU,
# kernel, rootfs images, cloud-init, embedded model) belongs to ARTI and is
# driven through scripts/run_arti_gpu.sh -> ARTI examples/linux_arti_driver/
# setup_env.sh. This script only gathers the siblings, the base image, and the
# OpenGPU-specific build stages around that, and skips anything already built.
#
# See docs/BOOTSTRAP.md for the repository split.
#
# Usage:
#   scripts/bootstrap_arti_debian.sh            # bring the checkout up to boot
#   scripts/bootstrap_arti_debian.sh --check    # preflight only, no builds
#   BOOTSTRAP_DRY_RUN=1 scripts/bootstrap_arti_debian.sh
#   BOOTSTRAP_FORCE=1 scripts/bootstrap_arti_debian.sh   # redo every stage
#
# Stage toggles (1 runs, 0 skips):
#   BOOTSTRAP_SIBLINGS BOOTSTRAP_BASE BOOTSTRAP_DISPLAY BOOTSTRAP_GTK BOOTSTRAP_MESA
set -euo pipefail

GPU_DIR="$(cd "$(dirname "$0")/.." && pwd)"

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
ARTI_DIR="${ARTI_DIR:-$GPU_DIR/../arti}"
FLASHSIM_DIR="${FLASHSIM_DIR:-$GPU_DIR/../FlashSim}"
QEMU_SRC="${QEMU_SRC:-$GPU_DIR/../qemu}"
ARTI_WORK="${ARTI_WORK:-$(cd "$GPU_DIR/.." && pwd)/arti-work}"
export ARTI_WORK
GPU_SIM="${GPU_SIM:-flashsim}"
INTEGRATION_CONFIG="${INTEGRATION_CONFIG:-$GPU_DIR/driver/gpu_integration_debian.yaml}"
QEMU_BUILD="${QEMU_BUILD:-$GPU_DIR/qemu-build}"

ARTI_URL="${ARTI_URL:-https://github.com/OpenGPGPU/arti.git}"
FLASHSIM_URL="${FLASHSIM_URL:-https://github.com/OpenGPGPU/FlashSim.git}"
QEMU_URL="${QEMU_URL:-https://github.com/OpenGPGPU/qemu.git}"
ARTI_REF="${ARTI_REF:-}"
FLASHSIM_REF="${FLASHSIM_REF:-}"
QEMU_REF="${QEMU_REF:-}"
BOOTSTRAP_SHALLOW="${BOOTSTRAP_SHALLOW:-1}"

DEBIAN_BASE_URL="${DEBIAN_BASE_URL:-https://cloud.debian.org/images/cloud/bookworm/latest/debian-12-generic-arm64.qcow2}"
BASE="${DEBIAN_BASE:-$ARTI_WORK/debian-arm64-base.qcow2}"
GTK_IMAGE="${GTK_DEBIAN_BASE:-$ARTI_WORK/debian-gtk-arm64.qcow2}"

BOOTSTRAP_SIBLINGS="${BOOTSTRAP_SIBLINGS:-1}"
BOOTSTRAP_BASE="${BOOTSTRAP_BASE:-1}"
BOOTSTRAP_DISPLAY="${BOOTSTRAP_DISPLAY:-1}"
BOOTSTRAP_GTK="${BOOTSTRAP_GTK:-1}"
BOOTSTRAP_MESA="${BOOTSTRAP_MESA:-1}"
BOOTSTRAP_FORCE="${BOOTSTRAP_FORCE:-0}"
BOOTSTRAP_DRY_RUN="${BOOTSTRAP_DRY_RUN:-0}"
MODE="run"

info() { echo "  $*"; }
step() { echo; echo "=== $* ==="; }
warn() { echo "  WARN: $*" >&2; }
fail() { echo "FAIL: $*" >&2; exit 1; }

run() {
    if [ "$BOOTSTRAP_DRY_RUN" = "1" ]; then
        info "[dry-run] $*"
        return 0
    fi
    "$@"
}

# ---------------------------------------------------------------------------
# Arguments
# ---------------------------------------------------------------------------
for arg in "$@"; do
    case "$arg" in
        --check) MODE="check" ;;
        --force) BOOTSTRAP_FORCE=1 ;;
        --help|-h)
            sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) fail "unknown argument: $arg (try --check, --force, --help)" ;;
    esac
done

# The profile owns the resolution and therefore the work-tree names.
[ -f "$INTEGRATION_CONFIG" ] || fail "integration profile not found: $INTEGRATION_CONFIG"
eval "$(python3 "$GPU_DIR/scripts/gpu_display_config.py" --shell "$INTEGRATION_CONFIG")"
GPU_TILE_BINNING="${GPU_TILE_BINNING:-0}"
TILE_SUFFIX=""
[ "$GPU_TILE_BINNING" != 1 ] || TILE_SUFFIX="-tile"
DISPLAY_WORK="${DISPLAY_WORK:-$ARTI_WORK/debian-${GPU_MODE}${TILE_SUFFIX}}"
DRIVER_OUTPUT="${DRIVER_OUTPUT:-$DISPLAY_WORK/opengpu-driver}"
DRIVER_KO="$DRIVER_OUTPUT/gpu_drv.ko"
QEMU_BIN="$QEMU_BUILD/qemu-system-aarch64"

echo "=== OpenGPU ARTI Debian bootstrap ==="
info "GPU_SIM   : $GPU_SIM"
info "GPU_MODE  : $GPU_MODE"
info "ARTI_WORK : $ARTI_WORK"
info "ARTI_DIR  : $ARTI_DIR"
info "FlashSim  : $FLASHSIM_DIR"
info "QEMU_SRC   : $QEMU_SRC"
[ "$MODE" = "check" ] && info "MODE     : check (no builds)"

# ---------------------------------------------------------------------------
# 0. Preflight
# ---------------------------------------------------------------------------
step "0/6 Preflight"

have() { command -v "$1" >/dev/null 2>&1; }
missing_display=()
missing_mesa=()

have sbt || missing_display+=("sbt")
have python3 || missing_display+=("python3")
have cc || missing_display+=("cc (host C compiler)")
have curl || missing_display+=("curl")
have ninja || missing_display+=("ninja")
if [ "$GPU_SIM" = "verilator" ]; then
    have verilator || missing_display+=("verilator (GPU_SIM=verilator)")
fi

have meson || missing_mesa+=("meson")
have aarch64-linux-gnu-gcc || missing_mesa+=("aarch64-linux-gnu-gcc")

if [ "${#missing_display[@]}" -gt 0 ]; then
    warn "missing display-build tools: ${missing_display[*]}"
    [ "$MODE" = "check" ] || fail "install the missing tools above (see docs/BOOTSTRAP.md)"
fi
[ "${#missing_mesa[@]}" -gt 0 ] && \
    warn "Mesa stage needs: ${missing_mesa[*]} (stage will be skipped)"

if [ "$GPU_SIM" = "flashsim" ] && [ ! -d "$FLASHSIM_DIR/flashsim" ]; then
    info "FlashSim not present yet; the siblings stage will fetch it"
fi
if [ "$MODE" = "check" ] && [ "${#missing_display[@]}" -gt 0 ]; then
    fail "preflight found missing required tools"
fi
info "host tools OK"

# ---------------------------------------------------------------------------
# 1. Sibling checkouts
# ---------------------------------------------------------------------------
step "1/6 Sibling checkouts"

arti_valid() { [ -f "$ARTI_DIR/examples/linux_arti_driver/setup_env.sh" ]; }
flashsim_valid() { [ -d "$FLASHSIM_DIR/flashsim" ]; }
qemu_valid() { [ -f "$QEMU_SRC/configure" ] && [ -f "$QEMU_SRC/meson.build" ]; }

ensure_sibling() {
    local name="$1" dir="$2" url="$3" ref="$4" valid="$5"
    if "$valid"; then
        info "$name already present (reusing): $dir"
        return 0
    fi
    if [ -d "$dir" ]; then
        fail "$name present but invalid at $dir; remove it or set the *_DIR override"
    fi
    if [ "$BOOTSTRAP_SIBLINGS" != "1" ]; then
        fail "$name missing at $dir (BOOTSTRAP_SIBLINGS=0; clone it manually)"
    fi
    have git || fail "git is required to clone $name"
    local clone_args=(clone)
    [ "$BOOTSTRAP_SHALLOW" = "1" ] && clone_args+=(--depth 1)
    [ -n "$ref" ] && clone_args+=(--branch "$ref")
    info "cloning $name -> $dir"
    run git "${clone_args[@]}" "$url" "$dir"
    [ "$BOOTSTRAP_DRY_RUN" = "1" ] || "$valid" || fail "$name clone did not validate at $dir"
}

ensure_sibling "ARTI"     "$ARTI_DIR"     "$ARTI_URL"     "$ARTI_REF"     arti_valid
ensure_sibling "FlashSim" "$FLASHSIM_DIR" "$FLASHSIM_URL" "$FLASHSIM_REF" flashsim_valid
ensure_sibling "QEMU"     "$QEMU_SRC"     "$QEMU_URL"     "$QEMU_REF"     qemu_valid

if [ "$MODE" = "check" ]; then
    echo
    echo "Preflight complete. Re-run without --check to build."
    exit 0
fi

# ---------------------------------------------------------------------------
# 2. Debian base image
# ---------------------------------------------------------------------------
step "2/6 Debian base image"
if [ "$BOOTSTRAP_FORCE" != 1 ] && [ -s "$BASE" ]; then
    info "base image already present (reusing): $BASE"
elif [ "$BOOTSTRAP_BASE" != "1" ]; then
    warn "base image missing and BOOTSTRAP_BASE=0: $BASE"
else
    have curl || fail "curl is required to download the Debian base image"
    info "downloading Debian 12 ARM64 cloud image -> $BASE"
    mkdir -p "$ARTI_WORK"
    run curl -fL --retry 5 --retry-all-errors --http1.1 -o "$BASE.partial" "$DEBIAN_BASE_URL"
    [ "$BOOTSTRAP_DRY_RUN" = "1" ] || mv "$BASE.partial" "$BASE"
fi

# ---------------------------------------------------------------------------
# 3. Display build (RTL + QEMU + kernel + gpu_drv.ko) via ARTI setup_env.sh
# ---------------------------------------------------------------------------
step "3/6 Display build ($GPU_MODE, $GPU_SIM)"
if [ "$BOOTSTRAP_FORCE" != 1 ] && [ -f "$DISPLAY_WORK/display-mode.txt" ] \
   && [ -x "$QEMU_BIN" ] && [ -f "$DRIVER_KO" ]; then
    info "display build already present (reusing): $DISPLAY_WORK"
elif [ "$BOOTSTRAP_DISPLAY" != "1" ]; then
    warn "display build missing and BOOTSTRAP_DISPLAY=0"
else
    info "building RTL, ARTI/QEMU model, kernel and driver (slow on first run)"
    info "ARTI work cache: $ARTI_WORK"
    run env ARTI_DIR="$ARTI_DIR" FLASHSIM_DIR="$FLASHSIM_DIR" \
        QEMU_BUILD="$QEMU_BUILD" QEMU_SRC="$QEMU_SRC" ARTI_WORK="$ARTI_WORK" \
        GPU_SIM="$GPU_SIM" GPU_FRAG_CORE="${GPU_FRAG_CORE:-1}" \
        INTEGRATION_CONFIG="$INTEGRATION_CONFIG" DISPLAY_WORK="$DISPLAY_WORK" \
        DRIVER_OUTPUT="$DRIVER_OUTPUT" DEBIAN_BASE="$BASE" \
        bash "$GPU_DIR/scripts/build_arti_debian_display.sh"
fi

# ---------------------------------------------------------------------------
# 4. GTK-enabled Debian image
# ---------------------------------------------------------------------------
step "4/6 GTK Debian image"
if [ "$GPU_SIM" != "flashsim" ]; then
    warn "bake_gtk_debian_image.sh hardcodes GPU_SIM=flashsim; skipping GTK image"
elif [ "$BOOTSTRAP_FORCE" != 1 ] && [ -s "$GTK_IMAGE" ] && [ -f "$GTK_IMAGE.gtk-ready" ]; then
    info "GTK image already present (reusing): $GTK_IMAGE"
elif [ "$BOOTSTRAP_GTK" != "1" ]; then
    warn "GTK image missing and BOOTSTRAP_GTK=0: $GTK_IMAGE"
else
    info "baking GTK-enabled Debian image (boots the guest once, slow)"
    run env ARTI_DIR="$ARTI_DIR" ARTI_WORK="$ARTI_WORK" \
        DEBIAN_BASE="$BASE" GTK_DEBIAN_BASE="$GTK_IMAGE" \
        QEMU_BUILD="$QEMU_BUILD" DISPLAY_WORK="$DISPLAY_WORK" \
        bash "$GPU_DIR/scripts/bake_gtk_debian_image.sh"
fi

# ---------------------------------------------------------------------------
# 5. Mesa / OpenGPU DRI driver
# ---------------------------------------------------------------------------
step "5/6 Mesa DRI driver"
MESA_SRC="${MESA_SRC:-$GPU_DIR/depends/mesa}"
PREFIX="${PREFIX:-$GPU_DIR/depends/aarch64-root}"
MESA_BUILD="${MESA_BUILD:-$GPU_DIR/depends/mesa-build}"
CROSS="${CROSS:-$MESA_BUILD/aarch64-linux-gnu-cross.txt}"
DRI_SO="$DRIVER_OUTPUT/opengpu_dri.so"
if [ "$BOOTSTRAP_FORCE" != 1 ] && [ -f "$DRI_SO" ]; then
    info "Mesa driver already present (reusing): $DRI_SO"
elif [ "$BOOTSTRAP_MESA" != "1" ]; then
    warn "Mesa driver missing and BOOTSTRAP_MESA=0"
elif [ ! -f "$MESA_SRC/meson.build" ]; then
    warn "Mesa 22.3.6 checkout missing at $MESA_SRC; clone it, then re-run"
    warn "  git clone -b mesa-22.3.6 --depth 1 https://gitlab.freedesktop.org/mesa/mesa.git $MESA_SRC"
elif [ ! -f "$PREFIX/lib/pkgconfig/libdrm.pc" ]; then
    warn "AArch64 libdrm missing under $PREFIX; build libdrm into that prefix first"
elif [ "${#missing_mesa[@]}" -gt 0 ]; then
    warn "Meson stage tools missing: ${missing_mesa[*]}"
else
    info "cross-building Mesa + OpenGPU Gallium driver -> $DRIVER_OUTPUT"
    mkdir -p "$(dirname "$CROSS")"
    run python3 "$GPU_DIR/scripts/aarch64_cross_prefix.py" \
        "$GPU_DIR/scripts/aarch64-linux-gnu-cross.txt" "$CROSS" "$PREFIX"
    run env PREFIX="$PREFIX" MESA_SRC="$MESA_SRC" MESA_BUILD="$MESA_BUILD" CROSS="$CROSS" \
        INTEGRATION_CONFIG="$INTEGRATION_CONFIG" DRIVER_OUTPUT="$DRIVER_OUTPUT" \
        bash "$GPU_DIR/scripts/build_mesa_opengpu.sh"
fi

# ---------------------------------------------------------------------------
# 6. Summary
# ---------------------------------------------------------------------------
step "6/6 Done"
echo "Artifacts:"
info "base image : $BASE"
info "GTK image  : $GTK_IMAGE"
info "QEMU       : $QEMU_BIN"
info "driver     : $DRIVER_KO"
info "Mesa DRI   : $DRI_SO"
echo
echo "Boot the Weston desktop:"
cat <<EOF
  OPENGPU_AUTO_DISPLAY=weston \\
  DEBIAN_BASE="$GTK_IMAGE" \\
  DISK="\$PWD/arti-weston-current.qcow2" REBUILD_DISK=0 REBUILD_CLOUDINIT=1 \\
  GPU_SIM=$GPU_SIM QEMU_DISPLAY=cocoa \\
  bash scripts/run_arti_debian.sh
EOF
