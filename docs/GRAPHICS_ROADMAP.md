# GPU graphics roadmap

What the graphics path implements today, and what remains. Contracts:
[HOST_INTERFACE.md](HOST_INTERFACE.md), [DRIVER_ARCHITECTURE.md](DRIVER_ARCHITECTURE.md).
Functional qualification: [FUNCTIONAL_QUALIFICATION.md](FUNCTIONAL_QUALIFICATION.md).
Physical numbers: [../timing/README.md](../timing/README.md).

Hardware architecture proposal dated 2026-10-17:
[Mobile GPU hardware alignment](MOBILE_GPU_HARDWARE_ALIGNMENT.md).
It defines proposed resource sizing and implementation gates separately from
the implemented capabilities and historical measurements below.

Shader compiler, 2026-10-19: device code is compiled by LLVM/clang in the
sibling checkout `../llvm-project` on branch `opengpu`, not by anything in
this Chisel tree. That work is a RISC-V vendor extension `XOpengpu` plus the
triple `opengpu-unknown-none-elf`. MC, the triple, codegen/ABI and
`clang --target=opengpu` are done. This repo now reads the resulting fatbin
(`userspace/libopengpu`) and submits it through the existing compute ioctl
(`examples/compute_offload`). The 2026-10-19 vertex+fragment Verilator guest
ran that example and checked `kadd` (`1 + 2 + 3 = 6`), then a textured
vertex draw (1009 pixels at `0xff0000ff`) and the 2x resolve. That draw
binds clang `fragment_texture.c` (sample plus `vquad.dfdx`) and
`vertex_passthrough.c`. Status and the ISA/ABI
contract live in
[LLVM_TOOLCHAIN_PLAN.md](LLVM_TOOLCHAIN_PLAN.md). Hardware that changes
decoder encodings, `opengpu.cease` / `barrier` / texture opcodes, reserved
registers `x1`, `x8`, `x9`, `x30`, `x31`, the kernarg `imm(x1)` convention, or
the `e32,m1,tu,mu` vtype policy has to update that LLVM branch as well. The
driver validator remains the admission gate. Every Gallium menu shader
now comes from `clang --target=opengpu`, including `vertex_mvp2.c`.
Gallium binds those words. `vtex.sample` is inline asm, and the alpha-one
patch matches any destination vector register.

**functional** = behavioral coverage on the unified path. **physical** = PPA.
A passing sim is not silicon; a completed tool run is not timing closure.

## Milestone update (2026-10-13)

The full-system graphical desktop goal is now demonstrated. A Debian ARM64
guest boots the OpenGPU DRM/KMS driver, starts the Weston Wayland desktop
shell, submits textured and fixed-function work through the OpenGPU
Mesa/Gallium path, and presents the guest-memory scanout in QEMU at 640x480.
The project screenshot is checked in at
[`docs/images/weston-desktop-640x480.png`](images/weston-desktop-640x480.png).

This closes the baseline integration loop across Linux, Mesa, Weston, ARTI,
the RTL graphics pipeline, guest GEM memory, and QEMU presentation. The
display proof does not use a CPU-painted replacement frame. The final desktop
orientation fix converts Mesa's lower-left texture V coordinate to the
top-to-bottom row order of imported linear display resources in the Gallium
vertex packer.

## Architecture (implemented)

- RISC-V SIMT with shared vertex/fragment shaders; fixed-function clip,
  raster, sample and depth/stencil/blend.
- Immediate-mode rendering; 2x2 fragment quads; 1x/2x/4x MSAA; perspective
  colour/UV and screen-space depth.
- RGBA8888 colour and D24S8 depth/stencil in software-owned shared DRAM.
- Product top `GpuHostSystemAxi`: one AXI4 control slave, one AXI4 memory
  master, one IRQ. GPU clients share GPU L2; CPU caches are separate.
- Unified commands for render, compute, copy, fill, strided copy, resolve and
  line invalidate. Render runs beside `RenderHost`; other opcodes use the
  system router; one completion slot/IRQ serves both.
- Driver scheduler owns GEM references, dependencies and fences. Display
  consumes GEM framebuffers; it does not own execution lifetime.
- Sv32 private VA windows and ASIDs. Command, framebuffer, texture,
  kernarg/VB staging, fill/blit/strided/resolve DMA, shader data loads and the
  programmable `vtex.sample` path translate with CU accesses. Context roots
  start empty and only explicit private mappings grant access. ASID-0 identity
  mappings remain read/write and non-executable for Bare bring-up;
  compute, fragment and vertex code use private executable windows. Host
  vertex→fragment translation and instruction-fault recovery are covered.
  VM-enabled mapping failures abort the operation.
- ARTI guest-memory GraphicHwOps owns QEMU scanout from SCANOUT_* registers;
  external display PHY remains out of scope.

## Capability status

Bits live in `GpuCapabilities.scala` and `driver/gpu_abi.h`
(`GpuAbiLayoutSpec` fails on drift).

| Surface | Owner | Status |
|---|---|---|
| Unified commands (6) | `GpuCommandRouter` / `GpuCommandMmio` | functional |
| Unified render + descriptor fetch (20) | `RenderHost` | functional |
| Unified reset (18) | `RenderHost` + router | functional |
| Sample-mode admission (7, 17:16) | `RenderHost` + `Msaa` | functional |
| Descriptor fetch-fault retention | `RenderHost` | functional |
| Fragment / programmable shaders (0) | `KernelFragStage` | functional; instr VA |
| Vertex core (2) | `KernelVertStage` | functional; instr VA |
| Clear / blit / strided (3–5) | DMA engines | functional; strided PPA FAIL |
| MSAA (7) | `Msaa` / `OutputMerger` | functional |
| Persistent depth (19) | `OutputMerger` | functional |
| Texture sampling | `TextureUnit` / `TexturedFragStage` | functional |
| Sv32 + TLB flush | graphics + CU translators | functional |
| Scheduler / GEM / display | `opengpu_scheduler.c`, `opengpu_drm_device.c` | build + host tests |

Also in place: shared translated word clients, generated transaction-ID
ranges, one resolved-draw-state bundle, optional sim performance counters,
`scripts/benchmark_gpu.py` / `scripts/qualify_ppa.py`, platform DRM/GEM with
render-only DT, and the MSAA / stencil-blend / resolve contracts
([MSAA_DESIGN.md](MSAA_DESIGN.md),
[STENCIL_BLEND_DESIGN.md](STENCIL_BLEND_DESIGN.md)).

Legacy dedicated DMA MMIO remains supported. Execution shadow registers are
readable/writable for compatibility but do not configure unified renders.

Submission regressions that must stay green: reset with in-flight render and
delayed writes; reject-while-drain; invalid sample-mode admission (1/2/4);
descriptor-fetch fault retention; texture/page-fault reporting; mixed sample
modes in sequence; STATUS.ERROR W1C leaving the owning completion intact; and
completion-slot backpressure until POP (`RenderHostSpec`, `GpuCommandMmioSpec`,
`GpuHostSystemAxiSpec`, `ProgrammableTextureAxiSpec`, `GpuSystemSpec`). Invalid
sample words, mixed 1x/2x/4x draws, descriptor bus faults, ERROR/completion
retention and a follow-up render held behind an unpopped completion are covered
on the integrated AXI path; render-host port backpressure remains covered in
`RenderHostSpec`. Programmable texture coverage runs `vtex.sample` with
different texture VA/PA, invalid PTE and page-table/texel bus faults, recovery,
and reset while an accepted texture read awaits data. Kernarg/VB staging
covers non-identity VA→PA, invalid PTE and page-table bus faults with
recovery, ASID switch / scoped flush shootdowns, plus a CPU rewrite of the
translated vertex-buffer PA across draws (uncached staging must observe it
without an L2 invalidate).

Workload note (`scripts/benchmark_gpu.py`): flat draws are OM-bound
(`om_stall` ≈ `raster_stall`, `om_conflict` = 0). Measured changes kept:
`GraphicsConfig.omInflight` 4 → 8 (~5–7% flat cycles), then 8 → 16 with
`pendingDepth` 8 → 16 so translation is not refill-starved (~4–6% more on
flat); same-cycle retire/accept on graphics TLB hits (~1%); and a registered
non-flow outstanding-hit pending queue. Programmable shading is still
staging-bound. Further flat gains need a dual color/depth OM memory port or
a wider word fabric. Numbers land in `generated/qualification/workloads/`.

## Qualification baseline

Workload sweep (`scripts/benchmark_gpu.py`) final results. Flat draws are
OM-bound (`om_conflict` = 0, `om_stall` ≈ `raster_stall`); programmable
shading is staging-bound. OM depth 16, hit-path graphics translation and a
registered non-flow outstanding-hit pending queue (`pendingDepth` 16) are
in after measured wins: `flat_16_1x` 5271 → 4975 cycles, `flat_32_1x`
19266 → 18162. The next flat lever needs a cheaper dual-issue path than a
second framebuffer TLB client (a full dual colour/depth client split was
measured and rejected). Shader staging stays intentionally uncached for
CPU coherence.

| Gate | Result |
|---|---|
| Boundary suites (Reproduce `testOnly` list + `GpuCommandMmioSpec`) | pass |
| `scripts/test_driver.py` + `scripts/test_test_selection.py` | pass |
| Shader corpus (`scripts/validate_shader_corpus.py`) | pass, all profiles |
| Workload sweep (`scripts/benchmark_gpu.py`, 12 cases incl. `app_16_4x`) | pass |
| Guest DRM, default / fragment-core / vertex+fragment | pass; powers off |
| `scripts/qualify_functional.sh` (full Scala suite, host driver, guest DRM) | pass |
| Guest userspace examples, all three profiles | pass |

## Debian graphical desktop

The goal is a graphical desktop in the Debian guest under ARTI. Its purpose
is to prove that the RTL and the software stack work together: the session
submits through the DRM driver, the RTL executes the jobs, and the pixels on
scanout are the pixels those jobs wrote. The QEMU window is a view of guest
memory. Host simulation time is not part of the proof; wall-clock numbers
later in this file size the model, and they do not gate this goal.

Graphics rendering uses this GPU. Vertex shading runs on the vertex core,
fragment shading runs on the fragment core, and raster, sample, depth,
stencil, and blend run in the fixed-function RTL. Fill, blit, strided copy,
and resolve are RTL jobs as well. The guest CPU packs vertex buffers, shader
binaries, and ioctls, then waits on the fence. It does not execute a shader,
rasterize a primitive, or write colour pixels in place of those jobs. A Mesa
state tracker is in scope only when its Gallium driver submits through that
same path. llvmpipe, softpipe, and the Gallium `draw` module are not a
stand-in for a shader or a rasterizer that the RTL does not yet run. A shader
that does not lower into the sandbox fails the draw.

`opengpu_drm_test`, `opengpu_triangle_present`, and the `pipe_*` examples
already take that path. `triangle_present` calls `opengpu_render`, waits for
the fence, checks the colour GEM, then `SETCRTC`s that buffer. The 320x240
Debian run (38,160 red triangle pixels, pixel (1,1) `ff0000`) is
fixed-function RTL output. Boot now defaults to both shader cores and to
`OPENGPU_AUTO_DISPLAY=desktop`. `gradient` and `opengpu_kms_present` paint
the framebuffer on the CPU, so they stay outside this proof.

Desktop acceptance status:

1. **Desktop client, verified.** `userspace/examples/pipe_desktop` fills the
   background and panel, blits the window, draws a triangle into it, blits a
   pointer, and `pipe_opengpu_present`s that GEM. It checks those pixels
   before printing `OPENGPU DESKTOP PASS`. `--hold` keeps the session and
   moves the pointer from evdev. `OPENGPU_AUTO_DISPLAY=desktop` is the Debian
   boot default (`opengpu-boot-display.service` runs it). `triangle` remains
   the single-draw boot.
2. **640x480 desktop mode, verified.** `driver/gpu_integration_debian.yaml`
   advertises 640x480 and the ARTI model is elaborated with the matching
   framebuffer and scanout geometry. There is no cursor plane, so the pointer
   is drawn through the GPU fill/blit path.
3. **Debian guest and scanout, verified.** The fresh guest proof reports
   `OPENGPU DESKTOP PASS` for the panel, window, triangle and pointer, then
   checks the complete 640x480 QEMU scanout PPM against those pixels.
4. **Mesa Gallium path, verified.** The GTK Wayland smoke proof submits through
   the OpenGPU Gallium driver and reports GPU fence status 0 for both frames.
   Stock GTK and Qt clients still need the same shader-lowering limits:
   OpenGL. The Gallium spike is [GALLIUM_SPIKE.md](GALLIUM_SPIKE.md). The
   shader sandbox is still 256 instructions, four forward branches, no loops
   or calls, e32/m1 only; see "Workload-driven ISA" below. Widen it when a
   desktop shader hits the limit. Index buffers, instancing, and a second
   colour target wait for the same reason.
5. **Real Weston desktop shell, verified (2026-10-13).** The GTK-enabled
   Debian image boots the stock Weston DRM backend through the OpenGPU
   DRM/EGL path. The 640x480 QEMU window shows the upright shell surface,
   bottom panel, clock, launcher, and pointer. This is the end-to-end desktop
   milestone; GtkGLArea client redraw and broader stock GTK/Qt shader coverage
   remain separate follow-up gates.

## Next work

The baseline desktop and GTK idle/active scanout gate are functional.
The current priority is **FlashSim desktop wall-clock time**. Measure the
existing workload before expanding shader coverage: use the draw trace,
separate preparation/submission/fence/copy timing, and sample the host model.
Then change the measured hotspot and rerun the same GTK pixel and scanout
checks on the same generated RTL. Broader shader forms remain follow-up work
driven by captured client programs. Cycle / PPA work stays secondary unless
a guest path is blocked.
Simulation wall-clock is not a correctness gate.

An opt-in tile-order raster traversal is available through
`EmitGpuHostSystemAxi ... --tile-size 16`. It visits every pixel or 2x2 helper
quad in 16x16 tile order, preserving edge planes, coverage masks, scissor,
MSAA, and backpressure. The default remains scanline order. This is a first
step toward mobile tile rendering: the output merger still reads and writes
external color/depth memory, so tile order alone does not reduce framebuffer
traffic. `--tile-size 16 --tile-attachments` additionally enables an
experimental 1x/2x/4x RGBA8/D24S8 tile store. It fetches words on demand, keeps
dirty color/depth words on chip, and writes them back at tile/draw boundaries
before draw completion. The store retains one 16x16 tile with up to four
samples per pixel. DMA clears/copies and render jobs are serialized in this
mode because a resident tile is newer than external memory. Primitive binning
across draws begins with opt-in `--tile-binning`, used alongside
`--tile-size 16 --tile-attachments`. It buffers eight ordered triangles
at a time and replays each group tile by tile. Inline triangles enter from the
command buffer; vertex-core triangles enter after vertex shading. A tile stays
resident across the group's draws and is flushed between groups. Command lists
and vertex draws can exceed eight triangles. A bin-building pass records an
ordered eight-bit membership mask per tile. Parallel scissor checks discard
empty tiles in one cycle;
unscissored positive-w triangles also get conservative column and row ranges.
Small grids use an axis scan; larger grids use monotonic boundary searches.
Candidate draws receive a final clip-space bounds test. Replay visits only
members of each mask. The masks use one byte per tile.
Full-screen unscissored triangles still require a bounds check for each
draw/tile pair. Fragment-core shading
and 1x/2x/4x samples are supported.
The opt-in schedule assumes draws do not read the render target being written
or depend on cross-pixel shader side effects within a command list.
The 640x480 FlashSim Debian proof runs GTK ellipse coverage and the multi-draw
`pipe_desktop` with tile binning. Its QEMU scanout matches the scanline model
byte for byte across all 307,200 pixels. Run it with
`GPU_TILE_BINNING=1 GTK_PROOF_DESKTOP=1 JDK_JAVA_OPTIONS=-Xmx12g bash scripts/run_gtk_coverage_guest.sh`;
see [GTK_COVERAGE.md](GTK_COVERAGE.md) for the cached-model and reference-image
options. The same full-image comparison also passes for the 640x480 GLES
scissor demo: tile and scanline PPMs have identical SHA-256 hashes. Run that
case with `GTK_PROOF_SCISSOR=1` and the reference-image option documented there.

The functional baseline includes private Sv32 mappings with context-local
revocation and ASID reuse, failure cleanup across compute/render ioctls,
reset and completion-backpressure recovery, seeded AXI fault sequences,
shader assembly validation, helper-lane derivatives, full-image reference
comparisons, and persistent-depth continuation at 1x/2x/4x. A context root
must not address another context's resources or unbound storage; validated VM
jobs must not rely on VA equal to PA. Keep these properties in the
[functional qualification gate](FUNCTIONAL_QUALIFICATION.md). Reproduce the
seeded AXI sequence with `OPENGPU_AXI_SEED=0x5eed2026 sbt -batch 'testOnly
opengpu.system.GpuHostSystemAxiSpec -- -z "replay randomized commands"'`.

1. **ARTI as a usable GPU** — `scripts/qualify_functional.sh` already boots
   fixed-function and vertex+fragment guests with `opengpu_drm_test`.
   Userspace apps on top of that API:
   Both shader cores are the default. Preferred programmable bring-up:
   ```sh
   GPU_USERSPACE_EXAMPLES=1 GPU_USERSPACE_EXAMPLES_ONLY=1 \
     scripts/run_arti_gpu.sh
   ```
   Fixed-function is the opt-out:
   ```sh
   GPU_FRAG_CORE=0 scripts/run_arti_gpu.sh
   ```
   Expect `OPENGPU USERSPACE EXAMPLES PASS`. `GPU_PIPE_SPIKE=1` is a
   compatibility alias for the fragment-core userspace path. Pipe DMA/draw
   surfaces for the spike are in. Debian interactive guest ships the same
   binaries on the OPENGPU ISO:
   ```sh
   scripts/run_arti_debian.sh
   # guest:
   /root/load_opengpu.sh
   /root/load_opengpu.sh examples
   /root/opengpu_triangle_present --hold
   ```
   (`BUILD_USERSPACE=0` skips the cross-build.) Mesa only if NIR stays small;
   see [GALLIUM_SPIKE.md](GALLIUM_SPIKE.md). Guest GLES proofs on the
   320×240 FlashSim desktop, after `gl_fade`: `opengpu_gl_glass` (source-alpha
   over the clear, inside `0x003f80bf`), `opengpu_gl_quad` (two triangles,
   both `0x007f007f`), and `opengpu_gl_pane` (scissor `[80,160)×[0,16)`,
   clipped pixel stays the clear).
   **Display hardware is ARTI guest-memory GraphicHwOps** (`gpu_integration.yaml`:
   SCANOUT BASE/STRIDE/CONTROL/WIDTH/HEIGHT + `refresh_hz`). KMS programs those
   registers; QEMU presents guest GEM memory. ARTI uses the DRM soft timer with
   a nominal 30 Hz period to match `refresh_hz`: its RTL clock advances during
   transactions and IRQ polls, so a 100 MHz hardware period cannot track wall
   time. Continuously clocked devices use hardware vblank
   (`GPU_CAP_HW_VBLANK`, `SCANOUT_PERIOD`, IRQ bit2). ARTI's QEMU
   window follows the GPU scanout width, height and stride registers. The
   profile width and height remain only the copy-buffer maximum
   (`ARTI_FB_WIDTH` / `ARTI_FB_HEIGHT`).
   The integration profile's `display:` block is the elaborated maximum and
   the boot mode: `scripts/gpu_display_config.py` reads it, and the scripts
   derive the elaboration, the driver default mode, the work-tree names and
   the PPM check from it. A modeset can select any smaller size; raising the
   maximum still means editing the profile rather than setting a variable.
   `scripts/test_display_config.py` guards the literals in
   `driver/gpu.dtsi` against it. `driver/gpu_integration_debian.yaml` is
   640x480 at 30 Hz; `driver/gpu_integration.yaml`, the reference profile the
   fast qualification runs use, is 64x64. Lowering `clk_freq_mhz` in the
   integration YAML does not help anything: ARTI ticks
   the model from `eval()` calls with no wall-time pacing, so the field only
   sets a SystemC period literal, and the driver independently hardcodes
   100 MHz for `SCANOUT_PERIOD` (`opengpu_hw.c`) while skipping it entirely
   under the `arti,rtl` compatible. Build the
   interactive Debian model once, then boot it:
   ```sh
   scripts/build_arti_debian_display.sh
   QEMU_DISPLAY=cocoa scripts/run_arti_debian.sh
   ```
   The standalone DRM scanout check uses `scripts/run_arti_display.sh` to
   capture the rendered modeset after the initial console scanout. The Debian runner enables
   `opengpu-boot-display.service` by default: on boot it loads the driver,
   whose DRM fbdev client provides the Debian framebuffer console. Use
   `OPENGPU_AUTO_DISPLAY` defaults to `desktop` (`examples/pipe_desktop`).
   `OPENGPU_AUTO_DISPLAY=triangle` is the single GPU triangle.
   `OPENGPU_AUTO_DISPLAY=0` loads manually. `gradient` asks the CPU to
   paint the framebuffer and is not a GPU proof.
   `examples/triangle_present` and `examples/pipe_present` close
   the graphics-client loop: render into the mode buffer, then SETCRTC for
   ARTI/QEMU present (`pipe_opengpu_present` is the Gallium-shaped path).
   Cocoa uses `zoom-to-fit=on`; an already-running window
   can enable **View → Zoom To Fit** and then be resized.
   Apps may still validate by reading colour GEMs; the QEMU window / PPM dump
   is the primary scanout path under ARTI.
2. **Keep the submission contract covered** — every submission-path change
   must exercise descriptor errors, reset-during-work, delayed writes,
   completion backpressure, recovery and mixed sample modes. Boundary edits
   must pull system integration tests.
3. **Measure before optimizing** — compare with `scripts/benchmark_gpu.py`
   under the same source hash, scene and memory model. OM depth 16,
   hit-path graphics translation (accept on response retire), and a
   registered non-flow outstanding-hit pending queue (`pendingDepth` 16)
   are in after measured wins: framebuffer translation stall collapses and
   flats improve vs `e2e155e` (`flat_16_1x` 5271 → 4975, `flat_32_1x`
   19266 → 18162). Flat counters still show `om_conflict` = 0 with
   `om_stall` ≈ `raster_stall`, so the next flat lever needs a cheaper
   dual-issue path than a second framebuffer TLB client (a full dual colour/
   depth client split was measured and rejected: flat_32 +3.7%). Shader
   staging stays intentionally uncached for CPU coherence; treat the
   `e2e155e` shader cycle rise vs `1194224` as the coherent baseline, not a
   regression to claw back by re-caching.
4. **Physical closure** — the strided-copy descriptor address cone and the
   command-router dispatch cone are pipelined; the FP32 FMA lane now runs
   five stages (completion add cut from invert/LZD-mask/mask-valid).
   Carry-save performance counters, divide finalize, and MSAA resolve
   scanline row bases reach **781.65 MHz** (−279.34 ps), with the limiter
   back on the FMA `csaSumReg` cone. See
   [../timing/README.md](../timing/README.md). Derive real parent IO budgets.
5. **Software-driven growth** — grow the shader ISA from a small compiler
   corpus. Compute and graphics fragment shader code use private executable
   windows; the ASID-0 identity map is read/write but non-executable. Shared-CU
   vertex→fragment reuse and vertex instruction-fault recovery are now covered:
   L1 probes progress while a demand miss waits for an L2 eviction, avoiding
   the circular wait exposed by vector-heavy vertex kernels. Fill/blit/strided
   DMA now translate under `VECTOR_SATP` (legacy kernel-word and unified
   engines). Context fill/blit/strided/resolve jobs map into a private DMA VA
   window at run time; resolve still invalidates L2 with the physical source
   base (`UCMD_PATTERN`) because host invalidate is PA-tagged. Line-invalidate
   also submits physical addresses but keeps the context ASID (satp unused).
   The symbolic corpus also validates fixed-profile
   `vsext`/`vzext`/`vnclip`/`vsmul` (`fixed_width.S` / `examples/fixed_width`),
   `vwadd`/`vwsub`/`vwmul` (`widen_alu.S` / `examples/widen_alu`), the
   programmable `vtex.sample` fragment path (`fragment_texture.S`, exercised by
   `examples/pipe_texture_draw` under `GPU_FRAG_CORE=1`), and FP32 VFUNARY1
   (`fp_unary.S` / `examples/fp_unary`), and unmasked OPFVV
   `vfadd`/`vfsub`/`vfmul`/`vfmin`/`vfmax`/`vfsgnj*` (`fp_binary.S` /
   `examples/fp_binary`), the eight fused FMA forms (`fp_fma.S` /
   `examples/fp_fma`), `vfdiv` (`fp_div.S` / `examples/fp_div`), and OPFVV
   compares (`fp_compare.S` / `examples/fp_compare`). Fragment shaders use
   the same vector FP units on the shader CU (`fragment_fp.S` /
   `examples/fragment_fp`: `vfmul`/`vfdiv`/`vfmacc` into the output colour).
   Remaining ASID-0 identity use is Bare bring-up (`opengpu_hw_enable_mmu`).
6. **Workload-driven ISA** — admitted and covered by `KernelShaderStageSpec`
   (plus the named ALU specs) and the corpus shaders under ARTI:
   - FP32 VFUNARY1 (`vfsqrt`/`vfrec7`/`vfrsqrt7`/`vfclass`), unmasked OPFVV
     `vfadd`/`vfsub`/`vfmul`/`vfmin`/`vfmax`/`vfsgnj*`, the eight fused FMA
     forms, `vfdiv`, and OPFVV compares (`vmfeq`/`vmfle`/`vmflt`/`vmfne`).
   - Fixed-profile integer widen/narrow (`widen_alu` / `fixed_width`).
   - OPFVF forms (`vfadd.vf`, `vfrsub`, `vfrdiv`, `vfmacc.vf`, ...) for compute
     shaders only on hardware advertising `GPU_CAP_COMPUTE_SCALAR_FPU`
     (bit22), with the scalar operand loaded by a validated `flw imm(x1)`
     (`fp_scalar.S` / `examples/fp_scalar`). The fragment and vertex shader CU
     is built with the scalar FPU backend (`KernelShaderStage` passes
     `enableFpuBackend = true`), so `flw` reads the uniform into an
     f-register and a vector op consumes it through the `.vf` operand
     sideband; `fsw` round-trips through the same f-register file in S-type
     layout. `frm`/`fflags` are per-warp state cleared on warp reuse
     (`VectorConfigurationUnitSpec`). The driver admits scalar-FPU ops for
     graphics via bit22, plumbed from `GpuHostSystemAxi` to
     `KernelShaderStage`; a `.vf` operand with an undefined f-register is
     rejected by defined-register analysis.
   - `fsw` (S-type, capability-gated like `flw`), scalar FP arithmetic
     (`fadd`/`fsub`/`fmul`, sign-injection, min/max, FMA forms), compares and
     conversions (`feq`/`flt`/`fle`, `fcvt.*`, `fmv.*`, `fclass.s`),
     `fdiv.s`/`fsqrt.s` on the iterative lanes.
   - Masked vector load/store are implemented and wired (`VectorBackend`
     folds `activeMask & vlMask & predicateMask` into every unit); `v1`
     publishes the per-lane index on the graphics shader CU for predicated
     accesses. The vector register file is never initialised (unwritten
     reads return SRAM power-up contents); the validator's
     defined-register tracking keeps guest shaders from reading it.
   - `vfmerge.vfm`/`vfmv.v.f`, unmasked SEW=32 `vfcvt.*` conversions
     (including rtz forms), masked non-compare FP on the same units.
   - Unit-stride and constant-stride vector memory admit 8/16/32-bit
     elements; scalar `lb`/`lbu`/`lh`/`lhu`/`lw`/`sb`/`sh`/`sw` as naturally
     aligned `imm(x1)` accesses.
   - `vmerge.*`/`vmv.*`, `vmand`/`vmor`/`vmxor` and friends combine packed
     masks; `vmv.s.x`/`vmv.x.s` and `vfmv.s.f`/`vfmv.f.s` move element zero
     through integer/float registers.
   - `vslide1up.vx`/`vslide1down.vx`, `vslideup.vv`/`vslidedown.vv`
     (per-element vector offsets).
   - Ordered FP32 reductions `vfredusum.vs`/`vfredosum.vs` fold sequentially
     through one elastic `Fp32FmaLane` (`fp_reduce.S`); min/max reductions
     stay unimplemented (funct6 001010 collides with `vfsgnjx.vv`).
   - `vid.v`/`viota.m`/`vcompress.vm` index-and-pack pair;
     single-width integer multiply-accumulate (`vmadd`/`vnmsub`/`vmacc`/
     `vnmsac`) on the radix-4 Booth pipeline.
   Grow further **validator + corpus** when a shader needs more vector FP.
   Further VFUNARY0 forms and widening beyond the fixed SEW=32 profile wait
   for a motivating shader, validator rules, and execution/guest coverage
   together.
7. **Graphics feature decision** — keep centre UV interpolation and
   uncompressed storage until a workload demonstrates a need. **Decision
   criteria (hold until a scene breaks them):** keep centre UV while fewer
   than 25% of partially covered textured samples have max-channel delta
   ≥ 8 against the per-sample reference; keep uncompressed storage while a
   representative frame stays under 50% uniform colour lines (solid flats
   are not predictive). Measure target scenes before adding centroid or
   per-sample interpolation or framebuffer compression. Revisit only with a
   product quality bar or a frame that fails these thresholds.
8. **Scalable graphics shader CUs** — keep the current single graphics shader
   CU as the functional baseline while making the scaling path explicit. The
   vertex and fragment shader instructions already execute on the SIMT
   `KernelShaderStage`; raster, sample, depth/stencil and blend remain in the
   fixed-function `RenderPipeline`/`RenderHost` path. `numComputeUnits` scales
   general compute CUs today and does not implicitly replicate the graphics
   shader, so add an independent `graphicsShaderUnits` parameter rather than
   coupling the two paths.

   Start with instrumentation and repeatable scenes that report shader
   occupancy, staging stalls, raster/OM stalls, L2 pressure, transaction
   traffic and frame completion time. If the measurements identify the shader
   as the limiter, add a `GraphicsShaderDispatcher` and begin with two
   `KernelShaderStage` instances. Dispatch complete vertex or fragment batches
   to separate instances while retaining one rasterizer and one output merger.
   Preserve draw ordering and the existing 2x2 fragment-quad ABI. Allocate
   independent transaction-ID ranges, L1 invalidation domains,
   kernarg/staging banks and completion routing for each graphics CU.

   A multi-CU shader implementation is accepted only when it produces the
   same scanout pixels and depth/blend results as the one-CU baseline, keeps
   draw-order and fence semantics intact, and shows a measured reduction in
   shader or staging time. If rasterization, output merge or memory bandwidth
   remains the limiter, scale those structures at tile/draw boundaries before
   considering a unified pool shared by graphics and general compute CUs.

## Known limits

- Guest ARTI/QEMU: the default, fragment-core, and vertex+fragment-core
  end-to-end DRM tests pass and power off cleanly on `cfc045a`. Programmable
  `vtex.sample`, kernarg/VB staging, fill/blit/strided DMA and shader data
  loads all translate under the context ASID (`VECTOR_SATP`). Context
  fill/blit/strided/resolve jobs map buffers into a private DMA VA window at
  run time so they no longer depend on VA==PA; resolve still invalidates with
  the physical source base. VM-enabled mapping failures abort. Line-invalidate
  submits physical addresses under the context ASID (engine ignores satp). Bare
  bring-up still uses the ASID-0 identity map. Context roots expose no identity
  fallback.
- The release-style functional gate runs the full Scala suite, host driver
  tests, and fixed-function plus vertex/fragment ARTI guests
  (`scripts/qualify_functional.sh`). Userspace examples and the workload sweep
  are separate opt-in runs.
- No parent-level per-interface timing budgets.
- Display/scanout under ARTI is guest-memory GraphicHwOps (BASE/STRIDE/
  CONTROL/WIDTH/HEIGHT + refresh timer). ARTI uses the DRM soft timer for
  KMS flips; continuously clocked devices can use the hardware vblank IRQ.
- ASID-0 identity mappings remain read/write and non-executable for Bare
  bring-up (`opengpu_hw_enable_mmu`). Resolve and line-invalidate submit
  physical invalidate addresses; engine/resolve traffic uses private DMA VAs
  under the context ASID where applicable. No resumable page faults or full
  removal of the Bare identity table yet.
- A device compiler exists in the sibling `llvm-project` `opengpu` branch
  ([LLVM_TOOLCHAIN_PLAN.md](LLVM_TOOLCHAIN_PLAN.md)). This repo's shader
  corpus and `opengpu_shader_validate_words` still decide which encodings the
  CU admits. Decoder or ABI changes belong in both places.

## Later / out of scope

- OM depth and nonblocking graphics translation are landed after measured
  wins; larger TLBs or a dual color/depth OM port only when counters
  justify the cost.
- Discrete PCIe/local VRAM, demand paging, tile-based rendering and display
  PHY/timing.

## Reproduce

Local runs keep the default parallel ScalaTest execution. GitHub CI sets
`Test / parallelExecution := false` so the runner stays within memory limits.

```sh
sbt -batch 'testOnly opengpu.graphics.GpuAbiLayoutSpec opengpu.graphics.RenderHostSpec \
   opengpu.graphics.OutputMergerSpec opengpu.graphics.MsaaSpec \
   opengpu.graphics.KernelFragStageSpec opengpu.graphics.RenderPipelineSpec \
   opengpu.core.memory.GraphicsAddressTranslatorSpec opengpu.system.GpuSystemSpec \
   opengpu.system.GpuHostAxiSpec opengpu.system.GpuHostSystemAxiSpec \
   opengpu.system.ProgrammableTextureAxiSpec \
   opengpu.dma.StridedCopyEngineSpec'
python3 scripts/test_driver.py
python3 scripts/test_test_selection.py
python3 scripts/test_display_config.py
python3 scripts/benchmark_gpu.py
# ARTI model timing, no QEMU or guest boot. --render times the path a game
# frame actually uses; --instrument adds the FlashSim dirty-page counters.
python3 scripts/bench_arti_model.py --render 320x240
python3 scripts/bench_arti_model.py --model-dir "$ARTI_WORK/debian-64x64/arti-embedded-gen/generated/embedded" --instrument
```
