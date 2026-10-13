# RISC-V SIMT GPU Core

This repository is a Chisel 7.x implementation of a GPU core whose lanes
execute the RISC-V ISA using a SIMT execution model.

The design adopts a commercial-GPU-style architecture: unified shading on the
SIMT lanes plus separated fixed-function graphics, in a small, dependency-light
package that is incrementally verifiable.

## Milestone: a real simulated graphical desktop

![Weston desktop rendered by OpenGPU RTL in ARTI/QEMU](docs/images/weston-desktop-640x480.png)

On 2026-10-13 the project reached its first complete graphical desktop
milestone. A Debian ARM64 guest now boots the Linux DRM/KMS driver, starts the
Weston Wayland desktop shell, renders through the OpenGPU Mesa/Gallium driver,
and presents the resulting guest framebuffer in a QEMU Cocoa window.

The screenshot above is a live 640x480 FlashSim run. The purple desktop
surface, bottom panel, clock text, launcher icon, and pointer are visible in
the guest scanout. The scanout is backed by guest GEM memory written by
OpenGPU jobs; the host window only displays that memory.

This milestone verifies the complete path:

```text
Weston / Wayland
        ↓
Mesa EGL + OpenGPU Gallium (opengpu_dri.so)
        ↓
Linux gpu_drv.ko and DRM/KMS ioctls
        ↓
ARTI full-system model + OpenGPU RTL
        ↓
guest GEM framebuffer / scanout registers
        ↓
QEMU display window
```

The final orientation fix is part of this path. Mesa uses a lower-left
texture-coordinate origin while the imported linear display resources store
row zero at the top, so the Gallium vertex packer converts `v` to `1.0 - v`
before submitting a textured draw. Render failures remain visible as failures;
the desktop proof does not replace GPU output with a CPU-painted frame.

## Structure

- `src/main/scala/opengpu/core/frontend/decode/` — unified scalar/FPU/RVV decoder and pipeline
- `src/main/scala/opengpu/core/frontend/simt/` — SIMT divergence/reconvergence state
- `src/main/scala/opengpu/core/execute/` — execution units
- `src/main/scala/opengpu/core/simt/` — SIMT lane wrapper
- `src/main/scala/opengpu/graphics/` — graphics pipeline (rasterizer, interpolators, output merger, geometry, command buffer, unified render descriptors and completions), see `docs/GRAPHICS_ROADMAP.md`
- `src/main/scala/opengpu/config/` — architectural configuration
- `src/test/scala/` — tests
- `src/test/scala/opengpu/testutil/` — shared Scala test harness (cached Verilator simulation)
- `build.sbt` — Scala and Chisel dependency configuration
- `docs/GRAPHICS_ROADMAP.md` — implemented surface, capability status, remaining work
- `docs/FUNCTIONAL_QUALIFICATION.md` — full Scala, host driver and guest gate
- `docs/HOST_INTERFACE.md` — AXI registers and shared-memory layouts
- `docs/DRIVER_ARCHITECTURE.md` — Linux driver ownership split
- `docs/GTK_COVERAGE.md` — Debian, Mesa, Weston and GTK/QEMU proof procedure
- `docs/images/` — checked-in screenshots of end-to-end display milestones
- `userspace/` — small DRM userspace library and compute/graphics examples
- `scripts/bench_arti_model.py` — ARTI model throughput probe (idle vs active
  settle rate, no QEMU boot), used to size what a redraw costs under simulation
- `docs/MSAA_DESIGN.md`, `docs/STENCIL_BLEND_DESIGN.md` — feature contracts
- `timing/README.md` — current ASAP7 PPA status

## Quick start

`GpuConfig()` now selects a compact mobile-oriented compute-unit shape: 16
lanes per warp, eight resident warps (128 threads), 16 KiB of banked shared
memory, and a 64 KiB, four-bank L2 (256 sets × four ways × 64-byte lines).
`EmitGpuHostSystemAxi` uses four compute units by default, for 512 resident
compute threads in total. `--compute-units N` overrides that count.
The graphics shader CU retains its eight-thread batch until its kernarg ABI
and guest shader bindings support a larger batch.
The 16-thread compute warp follows [Arm's published Mali-G78 core configuration](https://documentation-service.arm.com/static/620e22c90ca305732a3a5dd0).
The other values are OpenGPU sizing choices; in particular, shared memory is
not a Mali load/store cache, and eight resident warps are well below the
G78's published 1,024-thread maximum per shader core. The ARTI script uses
this compute configuration and four-CU system count. Other
configurations can use explicit `GpuConfig` arguments.

1. Install JDK 11 or newer and sbt.
2. Run:
   - `sbt compile`
   - `sbt test`

### Test runtime

Compiling the Verilator model, not simulating it, dominates the suite, so specs
drive their designs through `opengpu.testutil.GpuSim` instead of
`chisel3.simulator.EphemeralSimulator`. `GpuSim` keeps each compiled model in a
content-addressed cache under `target/chiselsim`, keyed by the digest of the
Verilog and the compiler flags, so a design is built once and reused by every
spec that touches it and by every later run. A cold full run is unchanged; a warm
one is several times faster. The cache is safe to share between concurrent test
threads, is dropped by `sbt clean`, and `-Dopengpu.simCache=off` turns it off.

### Full-system graphical desktop

The desktop run needs the sibling ARTI checkout, the prepared ARM64 kernel
build, QEMU, Mesa's AArch64 dependencies, and a GTK-enabled Debian image. The
one-time image preparation is documented in
[docs/GTK_COVERAGE.md](docs/GTK_COVERAGE.md). After those prerequisites exist,
build the display model and Mesa driver:

```sh
scripts/build_arti_debian_display.sh
bash scripts/build_mesa_opengpu.sh
```

Boot the real Weston desktop in a visible QEMU window:

```sh
OPENGPU_AUTO_DISPLAY=weston \
DEBIAN_BASE=../arti-work/debian-gtk-arm64.qcow2 \
DISK="$PWD/arti-weston-current.qcow2" \
REBUILD_DISK=0 \
REBUILD_CLOUDINIT=1 \
GPU_SIM=flashsim \
QEMU_DISPLAY=cocoa \
bash scripts/run_arti_debian.sh
```

Once the modules ISO has been refreshed, repeated boots can skip the rebuild:

```sh
OPENGPU_AUTO_DISPLAY=weston \
DEBIAN_BASE=../arti-work/debian-gtk-arm64.qcow2 \
DISK="$PWD/arti-weston-current.qcow2" \
REBUILD_DISK=0 \
REBUILD_CLOUDINIT=0 \
BUILD_USERSPACE=0 \
GPU_SIM=flashsim \
QEMU_DISPLAY=cocoa \
bash scripts/run_arti_debian.sh
```

FlashSim models the RTL cycle by cycle, so a 640x480 compositor frame can
take minutes. Seeing the QEMU window with an upright clock and launcher in the
bottom panel is the graphical success condition. The serial log should also
show the OpenGPU renderer and completed fence-backed draws.

## Current scope

- RV32I/M integer, branch, jump, load/store and upper-immediate decoding
- Zicsr, system/fence, and explicit illegal-instruction reporting
- F/D/Zfh floating-point pipeline routing and execution controls
- FP32 FMA, divide, and square root, plus exact sign-injection, min/max,
  compare, classify, bit-move, integer/FP32 conversion with dynamic frm, rtz,
  and NV/NX flags, and flw/fsw memory execution in the FPU backend
- Precise allow-listed RVV arithmetic, mask, configuration, and vector-memory routing
- RVV integer divide/remainder (`vdivu/vdiv/vremu/vrem`) in vv/vx forms
- RVV single-width integer multiply-accumulate (`vmadd/vnmsub/vmacc/vnmsac`)
  in vv/vx forms, accumulating into the destination or into `vs2`
- RVV vector element index (`vid.v`), mask index (`viota.m`) and mask-driven
  compaction (`vcompress.vm`), which together with the indexed stores form the
  compaction idiom
- RVV FP32 lane-local operations: sign injection, min/max, comparisons,
  add/sub/mul/div, vfrdiv/vfrsub, vfmv.v.f/vfmerge.vfm, the
  vfcvt.xu.f.v/vfcvt.x.f.v/vfcvt.f.xu.v/vfcvt.f.x.v and the four rtz
  conversion forms, vfclass.v/vfsqrt.v/vfrec7.v/vfrsqrt7.v, and all eight
  fused FMA forms over FVV/FVF operands
- RVV FP exception flags (NV/DZ/OF/UF/NX) committed alongside scalar FPU flags
  and accumulated with scalar FPU flags into per-warp fflags state; frm feeds
  vector FP rounding and scalar FPU dynamic rounding
- Physical vector register file emission: per-warp 32 x VLEN register banks
  mirror ASAP7 1RW SRAM macros for three reads plus one write, with write-through
  bypass, selected by `useBlackBoxes` in `GpuCore` and emitted by `EmitTimingRtl`
- OpenGPU-compatible vector-branch, join, and cease warp-control decoding
- Round-robin hardware-warp allocation and active/blocked/finish state tracking
- Independent per-warp SIMT divergence stacks with recycling clear
- Scalar branch/JAL/JALR target resolution with signed and unsigned comparisons
- Lane-mask SIMT branch resolution and ordered divergence-stack write sequencing
- Branch and restored-path PC/mask feedback into warp scheduling
- Backpressured in-order instruction fetch with same-cycle request replacement
- Warp ID, PC, and active-mask preservation through unified decode
- Instruction-access-fault routing distinct from illegal-instruction reporting
- Table-derived scalar source-register usage for precise hazard checks
- Scalar decode/RF metadata alignment across scoreboard stalls
- Elastic RV32I integer execution with register/immediate/PC operand selection
- Exclusive scalar dispatch for integer/MUL/DIV/branch/memory/system/trap
- Elastic RV32M `MUL/MULH/MULHSU/MULHU` execution and atomic commit
- Iterative RV32M `DIV/DIVU/REM/REMU` with architectural corner cases
- Scalar conditional/JAL/JALR execution and scheduler redirect
- Atomic RF writeback and warp redirect with fair commit arbitration
- Integrated RV32I plus RV32M multiply fetch-to-commit loop in `GpuCore`
- Per-warp scalar register file with x0 and deterministic writeback bypass
- Atomic per-warp RAW/WAW scoreboard reservation and release
- Parameterized SIMT lane count and active-lane masking
- RV32I integer ALU operations

## Graphics pipeline

`src/main/scala/opengpu/graphics/` implements a graphics front-end and render
pipeline on the unified-shader + separated-fixed-function model:
`MatrixTransform` (4x4 MVP) -> `GeometryStage` (perspective divide + viewport)
-> `NearClipStage` (Sutherland-Hodgman clipping) -> `TriangleRasterizer`
(fixed-point, top-left fill rule, cull) -> `FragmentInterpolator` /
`PerspectiveInterpolator` (barycentric colour + depth, perspective-correct) ->
`OutputMerger` (depth test + write to software-allocated colour/depth buffers),
driven by unified render descriptors and `CommandBufferStage` (reads draw records) and
composed in `RenderPipeline` / `RenderCore`. The framebuffer, depth buffer and
command buffer are all software-allocated shared memory; hardware only computes
addresses and issues reads/writes. Core-backed vertex and fragment shaders share
one SIMT compute unit. The Linux DRM/KMS driver exposes validated resource
bindings, queued fence-aware submission, vertex/fragment shader sandboxes and
ARTI/QEMU end-to-end display tests. Scanout pixels are the pixels those GPU
jobs wrote: shading and rasterization run on this RTL, and a CPU paint of the
framebuffer is not the graphics proof. See `docs/GRAPHICS_ROADMAP.md`.

## Verified end-to-end status

The current graphical acceptance path covers these layers:

| Layer | Verified result |
| --- | --- |
| RTL graphics | Triangle rasterization, interpolation, depth, texture sampling, fill, blit, resolve, and scanout jobs execute in the OpenGPU model. |
| Shader cores | Fragment and vertex shader binaries are validated and run through the shader sandboxes; the default display build enables both cores. |
| Linux driver | GEM resources, validated bindings, DRM/KMS modeset, fences, page flips, and scanout registers are exercised by the guest. |
| Mesa/Gallium | `opengpu_dri.so` lowers the supported Weston/GTK draw forms to OpenGPU submissions and waits for real GPU fences. |
| Desktop shell | Weston starts on the guest's DRM connector and produces a usable Wayland desktop with panel, clock, launcher, shell surface, and pointer. |
| QEMU presentation | QEMU displays the guest-memory scanout at 640x480; the visible window is the final end-to-end artifact. |

The deterministic `opengpu_pipe_desktop` proof and the real Weston desktop are
both useful: the former gives exact pixel checks for a small controlled scene,
while the latter validates the Linux, Mesa, Wayland, and compositor stack as a
single system. Keep the detailed capability matrix, timing data, and remaining
shader-lowering work in [docs/GRAPHICS_ROADMAP.md](docs/GRAPHICS_ROADMAP.md).

## Current priorities

- Broaden the shader sandbox and Gallium lowering coverage for more stock GTK
  and Qt workloads.
- Reduce FlashSim wall-clock cost for large compositor frames and continue
  physical timing closure.
- Extend the automated guest proof from deterministic scanout checks to more
  interactive Wayland and GTK workloads.
- Continue RVV, CSR/trap, memory-system, and multi-render-target coverage.

The full qualification procedures remain in
[docs/FUNCTIONAL_QUALIFICATION.md](docs/FUNCTIONAL_QUALIFICATION.md),
[docs/GTK_COVERAGE.md](docs/GTK_COVERAGE.md), and
[docs/GRAPHICS_ROADMAP.md](docs/GRAPHICS_ROADMAP.md).
