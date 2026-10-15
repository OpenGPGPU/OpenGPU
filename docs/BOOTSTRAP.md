# Bootstrap and dependency layout

This document defines which repository owns which part of the full-system
simulation, and how a fresh checkout is brought up to a bootable OpenGPU Debian
desktop. It exists because the flow spans two repositories: the platform
machinery lives in ARTI, and everything OpenGPU-specific lives here.

## Repository roles

| Repository | Role | Default path |
| --- | --- | --- |
| OpenGPU (`gpu`, this repo) | The device: RTL, Linux DRM/KMS driver, userspace, Mesa/Gallium driver, integration profiles, and the GPU-specific wiring into ARTI. | — |
| ARTI | Generic full-system **platform**: QEMU co-simulation host, AArch64 Linux kernel, rootfs images, cloud-init, embedded RTL model, and the device-agnostic runners. Knows nothing about OpenGPU. | `../arti` (`ARTI_DIR`) |
| FlashSim | Cycle-accurate RTL **backend** used when `GPU_SIM=flashsim`. | `../FlashSim` (`FLASHSIM_DIR`) |
| QEMU fork | Upstream QEMU carrying the generated `arti-rtl` SysBus device. Required as a sibling by the GPU flow. | `../qemu` (`QEMU_SRC`) |

The rule of thumb: **if it is device-agnostic it belongs to ARTI; if it is
OpenGPU it belongs here.** The GPU repo never re-implements a platform step; it
configures ARTI's scripts through environment variables and calls them.

## What each repository owns

### ARTI (`examples/linux_arti_driver/`)

| Script | Responsibility |
| --- | --- |
| `setup_env.sh` | One-click, skip-aware bootstrap: ninja, QEMU (download/build + ARTI patch), AArch64 Linux kernel (download/build), static Busybox, Debian 12 cloud base image + working disk + cloud-init ISO, `.ko` modules, embedded RTL model. |
| `integration_env.sh` | Loads the integration profile and the default work directory. |
| `build_cloudinit.sh` | Builds the cloud-init seed ISO. The GPU flow reuses it, with `OPENGPU_*` env, to also emit the OpenGPU modules ISO. |
| `build_driver.sh` | Builds an out-of-tree Linux module against `$LINUX_BUILD`. |
| `build_embedded_qemu.sh` | Generates and compiles a given RTL top into the embedded QEMU model. |
| `check_integration.sh`, `driver_preflight.sh` | Preflight and validation. |
| `run.sh` | Setup+run front door (`test` / `interactive` / `debian`). |
| `run_linux_test.sh` | Automated initramfs end-to-end test. |
| `run_debian.sh` | Boots the persistent Debian qcow2. **The GPU launcher execs into this.** |
| `run_interactive.sh`, `run_gpu_display.sh` | Reference-only runs. |
| `integration.yaml`, `integration_gpu_reference.yaml` | Profiles. The "gpu_reference" RTL is ARTI's own example, not OpenGPU. |

### OpenGPU (`scripts/`)

| Script | Responsibility |
| --- | --- |
| `run_arti_gpu.sh` | GPU integration orchestrator: emit RTL from Chisel, apply GPU patches to ARTI/QEMU, call ARTI `setup_env.sh` with GPU env, build `gpu_drv.ko`, build host validators and the guest DRM test, then boot. `BUILD_ONLY=1` stops before boot. |
| `build_arti_debian_display.sh` | `BUILD_ONLY=1` wrapper over `run_arti_gpu.sh` for a display profile; records `display-mode.txt`. |
| `run_arti_debian.sh` | GPU Debian launcher: choose the backend-matched QEMU, create the working disk from `DEBIAN_BASE`, refresh the cloud-init + modules ISOs, stage userspace, then `exec` ARTI `run_debian.sh`. |
| `run_arti_display.sh` | Headless scanout PPM proof. |
| `bake_gtk_debian_image.sh` | Bakes the GTK-enabled Debian image (the OpenGPU desktop image variant). |
| `stage_mesa_opengpu.sh` | Copies the out-of-tree Gallium driver into the Mesa 22.3 tree. |
| `build_mesa_opengpu.sh` | Cross-builds Mesa 22.3 + the OpenGPU DRI driver and `opengpu_gl_*` apps. |
| `build_userspace_guest.sh` | Cross-builds the static `opengpu_*` guest examples. |
| `run_gtk_coverage_guest.sh` | GTK/GLES coverage proof. |
| `qualify_functional.sh` | Release gate: Scala suite + host driver tests + both ARTI guest DRM paths. |
| `link_arti_model.py`, `scripts/patches/*` | Link QEMU against a chosen model archive; GPU-authored ARTI/QEMU patches. |
| `bootstrap_arti_debian.sh` | End-to-end, skip-aware bootstrap for a fresh checkout (see below). |
| `driver/gpu_integration*.yaml` | Integration profiles consumed **by ARTI**. |

## The seam

GPU scripts configure ARTI through environment variables and call its entry
points; they do not duplicate platform logic.

- **Env:** `INTEGRATION_CONFIG`, `ARTI_RTL_SOURCE`, `ARTI_RTL_SOURCE_LIST`,
  `ARTI_RTL_TOP`, `ARTI_RTL_BACKEND`/`GPU_SIM`, `LINUX_SRC`/`LINUX_BUILD`,
  `QEMU_SRC`/`QEMU_BUILD`, `WORK_DIR`, `DRIVER_KO`, `DRIVER_MANIFEST`,
  `ARTI_WORK`, and the `OPENGPU_*` guest knobs.
- **Entry points:** `setup_env.sh`, `build_driver.sh`, `build_cloudinit.sh`,
  `run_debian.sh`.
- **Shared cache:** `../arti-work`. The GPU scripts pin `ARTI_WORK` there; ARTI's
  own default is different, so always set it when sharing the cache.

## Artifact ownership and cache layout

| Artifact | Producer |
| --- | --- |
| `debian-arm64-base.qcow2`, working disk, `cloud-init.iso` | ARTI `setup_env.sh` |
| Debian **GTK** image (`debian-gtk-arm64.qcow2`) | GPU `bake_gtk_debian_image.sh` |
| Kernel `Image`, QEMU + embedded model | ARTI `setup_env.sh`, driven by GPU env |
| `GpuHostSystemAxi.sv`, `gpu_drv.ko`, Mesa `opengpu_dri.so`, `opengpu_*` userspace | GPU scripts |

```text
../arti-work/
  debian-arm64-base.qcow2          ARTI setup_env.sh   (downloaded base)
  debian-gtk-arm64.qcow2           GPU bake_gtk_debian_image.sh
  arti-linux-build/                ARTI setup_env.sh   (kernel)
  debian-<mode>/                   GPU display build
    display-mode.txt               skip marker
    opengpu-driver/                gpu_drv.ko, userspace, cloud-init inputs
  debian-<mode>/cloud-init.iso     ARTI build_cloudinit.sh (GPU env)
  debian-<mode>/opengpu-modules.iso
../gpu/qemu-build/qemu-system-aarch64   ARTI setup_env.sh (linked model)
```

## Bootstrap

```sh
scripts/bootstrap_arti_debian.sh            # bring a fresh checkout up to a boot
scripts/bootstrap_arti_debian.sh --check    # preflight only, no builds
```

The script is **skip-aware**: every stage checks for its artifact first and does
nothing when it already exists, so re-running is cheap. It deliberately does not
re-implement any platform step — the display build delegates to
`run_arti_gpu.sh`, which calls ARTI `setup_env.sh`.

Stages, in order:

1. **Preflight** — host tools (`sbt`, JDK, `python3`, `cc`, `ninja`, `meson`,
   `aarch64-linux-gnu-gcc`, `qemu-img`); `verilator` or FlashSim depending on
   `GPU_SIM`.
2. **Siblings** — clone `ARTI_DIR` / `FLASHSIM_DIR` / `QEMU_SRC` when missing,
   otherwise reuse them.
3. **Base image** — download the Debian 12 ARM64 cloud image when missing.
4. **Display build** — `build_arti_debian_display.sh` (RTL, QEMU, kernel,
   `gpu_drv.ko`). Skipped when `display-mode.txt` and the artifacts exist.
5. **GTK image** — `bake_gtk_debian_image.sh`. Skipped when the image exists.
6. **Mesa** — `build_mesa_opengpu.sh`. Skipped when `opengpu_dri.so` exists.

Stage toggles (default `1`): `BOOTSTRAP_SIBLINGS`, `BOOTSTRAP_BASE`,
`BOOTSTRAP_DISPLAY`, `BOOTSTRAP_GTK`, `BOOTSTRAP_MESA`. `--force` redoes all
stages; `BOOTSTRAP_DRY_RUN=1` prints actions without executing them.

After the bootstrap, boot the desktop:

```sh
OPENGPU_AUTO_DISPLAY=weston \
DEBIAN_BASE="$ARTI_WORK/debian-gtk-arm64.qcow2" \
DISK="$PWD/arti-weston-current.qcow2" REBUILD_DISK=0 REBUILD_CLOUDINIT=1 \
GPU_SIM=flashsim QEMU_DISPLAY=cocoa \
bash scripts/run_arti_debian.sh
```

## Prerequisites

The display build and Mesa cross-build need, on the host:

- `sbt`, a JDK 11+, `python3`, a host C compiler, `ninja`, `meson`.
- The AArch64 cross toolchain (`aarch64-linux-gnu-gcc`; ARTI's `setup_env.sh`
  installs it via brew/apt on demand).
- `verilator` when `GPU_SIM=verilator`; the FlashSim checkout when
  `GPU_SIM=flashsim`.
- Mesa 22.3.6 under `depends/mesa` and an AArch64 libdrm under
  `depends/aarch64-root` for the GLES/GTK desktop. These are **not** fetched
  automatically; the bootstrap reports them and skips the Mesa stage.

## Sibling dependency policy

ARTI, FlashSim, and the QEMU fork are **not** git submodules. They are pinned
sibling checkouts, brought in by the bootstrap script, for three reasons:

- They are actively co-developed with the GPU, so submodules would force
  detached-HEAD edits and two-commit cross-repo changes.
- They are large; `--recurse-submodules` would slow every checkout, including
  the RTL-only `sbt test` path that needs none of them.
- Git submodules pin commits poorly for this workflow; a lock file (repo, URL,
  commit) checked out by the bootstrap gives the same reproducibility without
  the UX cost.

`depends/asap7_sram_0p0` remains a submodule because it is a genuine vendored
dependency. If git-native pinning is later required, add submodules for ARTI and
FlashSim only — not the QEMU fork, which ARTI can also build from upstream.
