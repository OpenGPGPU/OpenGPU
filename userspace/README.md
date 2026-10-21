# Small OpenGPU userspace API

`opengpu.h` and `opengpu.c` wrap the public DRM ioctls for contexts, mapped GEM
buffers, resource bindings, compute and render submission, and syncobj fences.
The caller owns the DRM file descriptor and releases each object explicitly.
The library passes command structs through without hiding ABI options; initialize
unused fields to zero. An ioctl returning success means the job was queued;
wait on the output syncobj before reading results. The kernel reports failed
jobs through their fences. See `DRM_IOCTL_OPENGPU_GET_FAULT` for the most recent
hardware fault snapshot.

Build with installed DRM headers and a RISC-V GNU assembler/toolchain:

```sh
make -C userspace
```

In this repository's ARTI workspace, build guest AArch64 binaries using:

```sh
make -C userspace CC=aarch64-linux-gnu-gcc \
    DRM_HEADERS=../../arti-work/linux-headers/include
```

`examples/compute` loads `shaders/round_modes.bin`, assembled from symbolic
RISC-V source, through private shader and kernarg bindings. It checks that
the same input rounds to 1 under RNU and 0 under RDN. Pass the shader binary
as the second argument when running outside the guest.

`examples/compute_offload` loads an LLVM offload fatbin
(`libopengpu/opengpu_offload.h`), copies the OpenGPU `.text` into the 64-byte
compute-shader binding, and submits it with `opengpu_compute`. The default
image is `/opengpu_kadd.fatbin` (`void kadd(int *p, int n) { *p = *p + n + 3; }`,
kernarg word 0 in/out, word 1 = `n`). Pass the DRM node and fatbin path as
the first and second arguments when running outside the guest.

`examples/fp_unary` loads clang `fp_unary.c` and checks exact RTL
reference results for `vfsqrt` / `vfrsqrt7` / `vfrec7` / `vfclass` (default
`/opengpu_fp_unary.bin`).
`examples/fp_binary` loads clang `fp_binary.c` and checks unmasked OPFVV
`vfadd` / `vfsub` / `vfmul` / `vfmin` / `vfmax` / `vfsgnj` (default
`/opengpu_fp_binary.bin`).
`examples/fp_fma` loads clang `fp_fma.c` and checks all eight unmasked fused FMA
forms (default `/opengpu_fp_fma.bin`).
`examples/fp_div` loads clang `fp_div.c` and checks unmasked `vfdiv` (default
`/opengpu_fp_div.bin`).
`examples/fp_compare` loads clang `fp_compare.c` and checks `vmfeq` / `vmflt` via a
masked integer add (default `/opengpu_fp_compare.bin`).
`examples/fp_scalar` loads clang `fp_scalar.c`, which reads `f1` with `flw` from
kernarg and checks `vfadd` / `vfrsub` / `vfrdiv` / `vfmul` / `vfmacc` /
`vmfgt` in their `.vf` forms (default `/opengpu_fp_scalar.bin`). It skips
unless the device advertises `OPENGPU_CAP_COMPUTE_SCALAR_FPU`.

`examples/fp_reduce` loads clang `fp_reduce.c` and checks the OPFRED sums
`vfredusum.vs` and `vfredosum.vs`: both write element 0 of the destination
with 1+2+3+4 and leave the three following elements at the integer `vadd.vv`
of each input with itself (default `/opengpu_fp_reduce.bin`).
`examples/widen_alu` loads clang `widen_alu.c` and checks lane-local
`vwadd` / `vwsub` / `vwmul` (default `/opengpu_widen_alu.bin`).
`examples/fixed_width` loads clang `fixed_width.c` and checks `vsext` / `vzext` /
`vnclip` / `vsmul` (default `/opengpu_fixed_width.bin`).

`examples/triangle` draws a native-mode test image into its own colour GEM on
a fixed-function build (64x64 by default). It obtains the mode through
`opengpu_display_size`. Pass a DRM node path as the first argument, or use the
default `/dev/dri/card0`.

`examples/fragment_tint` is the programmable counterpart: it binds clang
`fragment_tint.c` and expects `OPENGPU_CAP_FRAGMENT_CORE`. The
shader XORs `0x00ff00ff` into every lane of the interpolated colour, so
every covered pixel is painted. Pass the DRM
node and optional shader binary path (default
`/opengpu_fragment_tint.bin`).
`examples/fragment_fp` runs clang `fragment_fp.c`, which computes
6.0f per lane with `vfmul` / `vfdiv` / `vfmacc` on the fragment shader CU and
XORs it into the interpolated colour; every painted pixel must be
`0xbec0ffff` (default `/opengpu_fragment_fp.bin`).
`examples/fragment_fp_scalar` is the `uniform float` case: clang
`fragment_fp_scalar.c` reads a per-draw uniform with `flw` and broadcasts
it with `vfadd.vf`, so it needs the scalar FPU on the fragment shader CU. It
skips unless `OPENGPU_CAP_COMPUTE_SCALAR_FPU` is advertised (default
`/opengpu_fragment_fp_scalar.bin`); every painted pixel must be `0xbea0ffff`.
`examples/fragment_alu` is the fragment program the Gallium menu does not
classify: `gl_FragColor = CONST[0][0] * CONST[0][1]`, lowered by
`opengpu_fs_emit` and submitted through the runtime ABI lowerer. It needs
the scalar FPU and a fragment-only device. The two constants are `(1,0,0,1)`
and `(1,1,1,1)`, so every painted pixel must be `0xff0000ff`.
`examples/fragment_min` is the next opcode on that emitter: `gl_FragColor =
min(CONST[0][0], CONST[0][1])`, the `MIN` half of `clamp()`. The constants are
`(1,0,1,1)` and `(1,1,0,1)`, so every painted pixel must be `0xff0000ff`.
`examples/fragment_cover` is the next set on that emitter, still on a
fragment-only device. `sqrt((0.25, 1, 0.25, 1))` is a scalar
`fsqrt.s` splatted to the fragment, and paints `0x80ff80ff`.
`min(max((2, 0.25, 0.5, 1.5), 0.5), 1)` paints `0xff8080ff`. On the 64×64
triangle, `gl_FragCoord.y < 32` paints both `0xff00ffff` and `0x0000ffff`.
A uniform `if` paints `0xff0000ff` on one submit and `0x00ff00ff` on the
next. `KILL_IF` drops lanes below `y = 32`, so the red survivors are a
partial triangle rather than the full 2016 pixels. The GTK
`ellipsis_coverage` program lowers on the same emitter: the Mesa
`MAD` that applies `gl_FbWposYTransform`, `DP2`, `DIV`, `MUL`, a
uniform `if` on a const-only temporary, `RSQ` then `RCP` for the
gradient, and a saturating add. Immediates are materialized with
`fmv.w.x`. The eight-float uniform tail holds colour and
`center_radius`; the Y transform is patched into the instruction
stream at draw time. A `TEX` whose coordinate is a temporary copied from
`TEXCOORD` emits `vtex.sample` from bytes 128 and 160; clang
`fragment_texture` remains the menu path when the coordinate is the
input itself. The 640×480 pixel proof is `scripts/run_gtk_coverage_guest.sh`.
`examples/vertex_alu` is the vertex program the menu does not classify. It
adds the integer at kernarg byte 512 to Q16.16 position x and copies the
other slices. The addend is zero and `fragment_color` copies the vertex
colour, so every covered pixel must be `0xff0000ff`. It runs when both cores
are present. `vertex_passthrough`, `vertex_mvp`, and `vertex_mvp2` stay the
clang images.

`examples/triangle_present` draws into the native KMS mode buffer (64x64 on
the Debian display profile) and programs the CRTC so ARTI/QEMU scanout shows
the result. Fixed-function builds paint a solid red triangle; fragment-core
builds use the tint shader. Pass `--hold` (or `OPENGPU_PRESENT_HOLD=1`) to
keep the framebuffer live for cocoa. Debian auto-display:

```sh
QEMU_DISPLAY=cocoa scripts/run_arti_debian.sh
```

`pipe_opengpu.h` / `pipe_opengpu.c` is the Gallium-shaped winsys spike
(`docs/GALLIUM_SPIKE.md`). `examples/pipe_clear_draw` clears via
`opengpu_fill`, then draws one triangle through `pipe_opengpu_draw_vbo`
(fixed-function or fragment-tint by capability). `examples/pipe_compute`
covers `launch_grid`. `examples/pipe_blit` clears a source GEM and copies it
with `pipe_opengpu_blit`. `examples/pipe_strided_blit` invalidates a
CPU-filled source then copies with `pipe_opengpu_strided_blit`.
`examples/pipe_depth_pass` continues a render across submissions with a
bound depth GEM and `OPENGPU_SUBMIT_DEPTH_LOAD` (fixed-function or tint).
`examples/pipe_msaa_draw` draws at 2x then resolves (fixed-function or tint).
`examples/pipe_resolve` averages a CPU-filled 2x
MSAA buffer. `examples/pipe_texture_draw` binds a mip chain and draws a textured
triangle: fixed-function uses the HW sampler; fragment-core loads the corpus
`fragment_texture` (`vtex.sample`) binary (default
`/opengpu_fragment_texture.bin`). `examples/pipe_vertex_draw`
exercises vertex+fragment cores (`GPU_VERT_CORE=1`).

Mesa uses `pipe_opengpu_draw_vertex_transient` for its private fragment banks.
It enables input omission only when both the driver feature and hardware
capability are present; the driver derives the required inputs from the shader
and runtime CU capacity. Callers of this helper must treat the first nine
fragment SoA slices as transient scratch. Uniforms remain persistent. The
existing draw helpers retain full staging.

`scripts/build_mesa_opengpu.sh` also builds `opengpu_transient_staging_test`.
Run it in the Debian guest with the module loaded to check default CPU-visible
inputs, opt-in omission, pixel equality and unsupported-hardware rejection.
The executable must be copied into the guest explicitly when using an older
ARTI cloud-init payload whitelist.

`examples/pipe_present` allocates a mode-sized 2D colour GEM, clears, draws,
and presents via `pipe_opengpu_present` (same `--hold` / tint rules as
`triangle_present`). `examples/pipe_desktop` is the Debian desktop frame:
GPU fill and blit for the panel, window and pointer, a GPU triangle in the
window, then present. `--hold` follows the pointer from evdev.

**ARTI as a GPU.** Fragment and vertex cores are the default:

```sh
GPU_USERSPACE_EXAMPLES=1 GPU_USERSPACE_EXAMPLES_ONLY=1 \
  scripts/run_arti_gpu.sh
```

Fixed-function smoke:

```sh
GPU_FRAG_CORE=0 GPU_USERSPACE_EXAMPLES=1 GPU_USERSPACE_EXAMPLES_ONLY=1 \
  scripts/run_arti_gpu.sh
```

Debian interactive (same binaries on the OPENGPU ISO):

```sh
scripts/run_arti_debian.sh
# guest after /root/load_opengpu.sh:
/root/load_opengpu.sh examples
# The boot desktop owns DRM master; stop it before a second KMS client.
systemctl stop opengpu-boot-display.service
/root/opengpu_pipe_desktop /dev/dri/card0 /root/opengpu_fragment_tint.bin --hold
```

See the platform-integration section of
[docs/GRAPHICS_ROADMAP.md](../docs/GRAPHICS_ROADMAP.md). Omit `*_ONLY=1` to
run the DRM guest regression before the apps. The full release gate remains
`scripts/qualify_functional.sh`.

## Shader corpus

Run `python3 scripts/validate_shader_corpus.py` from the repository root. It
assembles `compute_copy.S`, `round_modes.S`, `masked_ops.S`, `fixed_width.S`
(lane-local `vsext`/`vzext`/`vnclip`/`vsmul` with `vxrm`; guest
`examples/fixed_width`), `widen_alu.S` (lane-local `vwadd`/`vwsub`/`vwmul`;
guest `examples/widen_alu`), `fragment_texture.S` (`vtex.sample` plus warp
discard / `vquad.dfdx`), `fragment_fp.S` (fragment-profile OPFVV; guest
`examples/fragment_fp`), `fp_unary.S` (FP32 VFUNARY1), `fp_binary.S`
(unmasked OPFVV `vfadd`/`vfsub`/`vfmul`/`vfmin`/`vfmax`/`vfsgnj`), and
`fp_fma.S` (eight fused FMA forms), `fp_div.S` (`vfdiv`),
`fp_compare.S` (`vmfeq`/`vmflt`), `fp_scalar.S` (`flw` plus OPFVF,
validated with the scalar-FPU compute profile) and `fp_reduce.S` (OPFRED
`vfredusum.vs`/`vfredosum.vs`), `fragment_tint.S` (per-lane colour XOR;
guest `examples/fragment_tint` and `examples/pipe_desktop`),
`fragment_color.S` (per-lane colour copy for the Gallium driver),
`vertex_passthrough.S` (vertex-core copy of the fixed 32-byte vertex),
and compiles two
small C shaders with `riscv64-unknown-elf-gcc`, adapts the C argument base to
OpenGPU's direct `x1` kernarg convention, and replaces the C return with the
OpenGPU cease instruction. The script checks every binary with the production
compute, fragment, fragment+texture or vertex shader validator. It rejects
compiler output with labels or indirect memory operands, rather than assuming
arbitrary C is a valid shader. Guest images for the programs that also have
a clang source under `userspace/shaders/` are those clang words. Set
`RISCV_GCC` and `RISCV_OBJCOPY` for another RISC-V toolchain.
