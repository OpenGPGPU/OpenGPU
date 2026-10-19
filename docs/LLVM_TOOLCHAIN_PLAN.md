# OpenGPU LLVM Toolchain Plan

Status, 2026-10-19: M1–M5 are done, including the M2 custom-2
vector-branch and `join` assembler round-trip. M6's software path is done, and the host
regression is wired into `scripts/test_scripts.sh`. The sources live in the
sibling checkout `../llvm-project` on branch `opengpu` (not in this
repository). This repo contains the fatbin reader (`userspace/libopengpu`),
the compute submission example (`userspace/examples/compute_offload.c`), and
`scripts/validate_offload_kernel.py` (LLVM kernels versus the shader-corpus
validator, including `fragment_color.c` and `vertex_passthrough.c`).
On 2026-10-19 the vertex+fragment Verilator guest ran
`opengpu_compute_offload` and checked `kadd` (`1 + 2 + 3 = 6`), then
`pipe_vertex_draw` (1009 pixels, `0xff0000ff`) and `pipe_resolve`.
The same day's fragment-only guest (`GPU_VERT_CORE=0`, RTL in
`generated/frag-only`) printed `OPENGPU USERSPACE EXAMPLES PASS`.
That run includes clang `fragment_tint` (2016 pixels), `fragment_fp`
(`0xbec0ffff`), `fragment_fp_scalar` (`0xbea0ffff`), `kadd` (`6`),
every `fp_*` program, `widen_alu` (`8` / `0xfffffffe` / `15`),
`fixed_width`, the pipe draws, and `pipe_vertex_draw` skipping because
there is no vertex core. `vfrsqrt7`/`vfrec7` use the RVV vs1 selectors
(`4` and `5`); the clang builtins are the guest image.
Compute and blit examples wait 300 seconds. A 30-second fence expires
on this model before a short kernel retires.
That draw binds clang `fragment_texture.c` and `vertex_passthrough.c`
(`clang --target=opengpu -O2 -mno-relax`), not the hand-written
`fragment_texture.S` image. The guest images for `fragment_tint`,
`fragment_fp`, `fragment_fp_scalar`, and the compute programs
`fp_unary`, `fp_binary`, `fp_fma`, `fp_div`, `fp_compare`, `fp_scalar`,
`fp_reduce`, `widen_alu`, and `fixed_width` are the same kind of clang
`.text`. `pipe_present` and `pipe_desktop` bind clang `vertex_passthrough`
when a vertex core is present.
On 2026-10-20, `clang --target=opengpu -O0 -mno-relax` of every shader in
`scripts/validate_offload_kernel.py` passes `opengpu_shader_validate_words`.
A fresh `-O2` compile of those guest images still matches
`userspace/gallium/opengpu_shaders.h`.
Hardware work that changes the CU ISA or the shader ABI below has to update
the LLVM branch in the same change.

This document defines the plan for a shader/compute toolchain for the OpenGPU
RISC-V SIMT GPU. It supersedes the earlier "independent LLVM target" sketch and
records the decisions made while grounding the work in the actual device ISA.

## 1. Device ISA (authoritative source)

The device ISA is defined by the Chisel decoders in
`src/main/scala/opengpu/core/frontend/decode` (see `ScalarDecoder.scala`,
`FpuDecoder.scala`, `VectorDecoder.scala`, `README.md`):

- RV32IMAFV, plus `Zicsr`, `Zifencei` and the A (atomics) extension.
- Only a **bounded RVV subset** is implemented (allow-list, not a
  `funct6 x funct3` product); FP is FP32 only.
- A small set of GPU-specific custom instructions:

  | Instruction | Encoding |
  | --- | --- |
  | `opengpu.barrier` | custom-0 (`0x0b`), all other fields zero = `0x0000000B` |
  | `opengpu.tex.sample rd, rs1, rs2` | custom-0, `funct7=0000001`, `funct3=000` |
  | `opengpu.cease` | SYSTEM, `funct7=0011000`, `rs2=00101` = `0x30500073` |
  | `opengpu.vtex.sample vd, vs1, vs2` | custom-1 (`0x2b`), `funct6=000001`, `funct3=000` |
  | `opengpu.vquad.dfdx vd, vs2` | custom-1, `funct6=001100`, `funct3=000` |
  | `opengpu.vquad.dfdy vd, vs2` | custom-1, `funct6=001101`, `funct3=000` |
  | custom vector-branch family + `join` | custom-2 (`0x5b`), `funct3`-selected |

- Execution constraints enforced by the driver/validator
  (`driver/opengpu_shader_validator.h`): at most 512 instructions, at most 4
  forward branches, no stack/calls/labels. The runtime ABI
  (`userspace/gallium/opengpu_shader_abi.h`) fixes `x1` = kernarg base,
  reserves `x30`/`x31`, uses `x9` = launch capacity, and rewrites `vsetvli` to
  VLMAX `e32,m1`.

## 2. Architecture

- **Backend strategy**: OpenGPU is a **RISCV vendor extension** named
  `XOpengpu`. The full RV32IMAFV/RVV encoders, assembler, disassembler and
  instruction selection are reused from the in-tree RISCV target; only the GPU
  custom instructions are added under the `+xopengpu` feature.
- **Triple**: a distinct `opengpu` triple identifies device code (fatbin /
  offload bundling) while resolving to the RISCV backend.
- **Code object**: RV32 little-endian ELF. Device-code identity is carried by
  the bundle triple string. `e_machine` is standard `EM_RISCV` (Section 6).

## 3. Layered plan

1. **MC layer** (lowest risk, first)
   - Scalar custom instructions: `barrier`, `tex.sample`, `cease`.
   - Vector custom instructions: `vtex.sample`, `vquad.dfdx/dfdy`.
   - Custom vector-branch family + `join`.
   - `vsetvli`/`vsetivli`/`vsetvl` are standard RISCV instructions and need no
     custom work.
   - Verification: `llvm-mc` round-trip on/off the feature, with every encoding
     compared bit-for-bit against the Chisel decode patterns.

2. **Triple / backend wiring**
   - Fix the canonical triple string.
   - Register an `opengpu` Target object in the RISCV `TargetInfo` that reuses
     the RISCV MC/CodeGen components (this is what "opengpu resolves to the
     RISCV backend" means).
   - Register the `xopengpu` extension in `RISCVISAInfo.cpp` so
     `-march=rv32imafv_xopengpu` parses, and add an `opengpu` CPU in
     `RISCVProcessors.td` implying `rv32imafv_xopengpu` for `-mcpu=opengpu`.
   - **Only at this point** retire the temporary Lanai-derived
     `llvm/lib/Target/OpenGPU/` target (keep `Triple::opengpu`).

3. **CodeGen + device ABI**
   - Use RISCV RV32IMAFV codegen; disable unsupported RVV operations via
     subtarget features (and, if needed, later restrict the legalizer).
   - Realize the device ABI (`opengpu_shader_lower_runtime_abi`: kernarg = `x1`,
     `x30`/`x31` reserved, `vsetvli` -> VLMAX `e32,m1`, `x9` capacity) as a
     target MachineFunctionPass or a runtime/linker step.
   - Verification: `llc -mtriple=... -mcpu=opengpu` output passes
     `opengpu_shader_validate_words`.

4. **clang + Driver**
   - `clang/lib/Basic/Targets/OpenGPU.{h,cpp}` deriving `RISCVTargetInfo`.
   - OpenGPU toolchain in `clang/lib/Driver/ToolChains/` deriving the RISCV
     toolchain; `--target=opengpu` selects the RISCV backend with `+xopengpu`.
   - OpenGPU ABI info in `clang/lib/CodeGen/TargetInfo.cpp` (reuse RISCV ILP32
     first; specialize kernarg/kernel ABI later).
   - Verification: `clang --target=opengpu -S` compiles a compute kernel to
     assembly that `llvm-mc` then assembles.

5. **Runtime / packaging**
   - `clang-offload-packager` bundles (fatbins); `libopengpu` device
     runtime/loader reads fatbins through the existing DRM ioctls and
     dispatches on the `opengpu` triple.
   - Verification: host program + kernel -> fatbin -> loaded and executed on the
     device path.

6. **Verification / regression**
   - Cross-check LLVM-generated device code against the existing Gallium TGSI
     emitter and the `scripts/validate_shader_corpus.py` corpus; wire into CI.
   - Done for the C subset: `scripts/validate_offload_kernel.py` compiles
     device kernels with `clang --target=opengpu`, admits them with the corpus
     validator profiles, and checks `compute_increment` / `vertex_offset`
     against the gcc corpus binaries (same `lw`/`addi`/`sw`/`cease`
     operations; registers may differ). Every Gallium menu program, including
     `vertex_mvp2.c`, lowers to the per-lane `x1+(x8<<2)` vector form and
     passes its existing profile. `opengpu_shader_lower_runtime_abi` retargets
     that form, and Gallium binds the clang words. The host CI job
     `scripts/test_scripts.sh` runs the script and skips it when the OpenGPU
     clang is absent.

## 4. Milestones

| Milestone | Deliverable | Acceptance |
| --- | --- | --- |
| M1 MC scalar | `XOpengpu` feature + `barrier`/`tex.sample`/`cease` | done; encodings match, round-trip works |
| M2 MC complete | vector custom instrs + branch/join | done; `llvm/test/MC/RISCV/xopengpu-valid.s` round-trips `barrier`, `tex.sample`, `cease`, `vtex.sample`, `vquad.dfdx`/`dfdy`, `vbeq`/`vbne`/`vblt`/`vbge`/`vbltu`/`vbgeu` and `join` bit-exact against the Chisel patterns. Codegen does not select the branch/join forms |
| M3 triple + CPU | `opengpu` resolves to RISCV | done; `llvm-mc -triple=opengpu` works; Lanai target retired |
| M4 CodeGen + ABI | `llc -mcpu=opengpu` | done; scalar/float/vector/branch kernels pass `opengpu_shader_validate_words` |
| M5 clang | `clang --target=opengpu -S` | done; compiles a kernel to assembly that `llvm-mc -triple=opengpu` assembles |
| M6 runtime | fatbin + loader | software round-trip passes; 2026-10-19 Verilator guest `kadd` returned 6 |
| Regression | LLVM vs corpus | `scripts/validate_offload_kernel.py` in `scripts/test_scripts.sh`; skips without the OpenGPU clang |

## 5. Repository layout

- LLVM changes live in the `OpenGPGPU/llvm-project` fork on the `opengpu`
  branch, kept rebaseable onto `upstream/main` (upstream added as a remote).
- Integration into this repository follows the existing sibling-checkout
  pattern and pins `llvm-project` to a specific commit once the milestone
  stabilizes.

## 6. Resolved decisions

1. **Triple string**: canonical form is `opengpu-unknown-none-elf`
   (arch `opengpu`, neutral vendor/os/env). `-triple=opengpu` is the tested
   short form.
2. **`e_machine`**: standard `EM_RISCV`. Device-code identity is carried by the
   bundle/triple, not by a custom machine number.
3. **RVV subset boundary**: keep the full `V` surface in the backend for now;
   `opengpu_shader_validate_words` remains the allow-list gate. A target-side
   subset can be added later if codegen produces rejected encodings.
4. **ABI pass location**: a target-side MachineFunctionPass in the RISCV
   backend (guarded by the `opengpu` triple), so `llc -mcpu=opengpu` alone
   produces validator-clean code.

## 7. Implementation notes

- The earlier bootstrap commit added a Lanai-derived `OpenGPU` LLVM target and
  `Triple::opengpu` / `EM_OPENGPU` / `R_OPENGPU_*`. This is temporary scaffolding
  for the triple plumbing and is retired at M3; only the `opengpu` triple
  identity is intended to survive.
- `clang -march=rv32imafv_zicsr_zifencei_xopengpu` is registered in
  `RISCVISAInfo.cpp`. `llvm-mc -triple=opengpu -mcpu=opengpu` round-trips the
  custom instructions.
- M4 kernarg lowering: `CallingConv::RISCV_OpenGPU` (a kernel CC) takes no
  register arguments; `x1` is the kernarg base and arguments are read from
  fixed kernarg offsets. `LowerFormalArguments` turns pointer arguments into
  `x1`-relative addresses and scalars into loads, keeping every access in the
  `imm(x1)` form the sandbox admits.
- M4 vtype: the sandbox only admits the `e32,m1` **tail/mask-undisturbed**
  (`vta=vma=0`) configuration; LLVM emits `ta,ma` by default, so the ABI pass
  clears the policy bits. `tu`/`mu` is a strictly stronger guarantee.
- M4 reserved registers: `x1`, `x8`, `x9`, `x30`, `x31` are reserved for the
  ABI so shader code cannot clobber the preloaded kernarg base, local index,
  launch capacity, staging stride or uniform base.
- `-O0` device kernels pass the same validator profiles as `-O2`. Clang does
  not mark an `opengpu` function `optnone`, does not allocate a frame pointer,
  promotes allocas, inlines helpers defined in the module, folds constant
  branches, and unrolls the small counted loops (the sandbox admits only
  forward branches). Register allocation is the optimized allocator, so a tied
  vector operand stays in one register. The ABI pass rewrites `ret` to
  `opengpu.cease`, clears the `ta`/`ma` vtype bits, drops `vsetvli x0,x0`,
  replaces `vmv1r.v` with `vmv.v.v`, and deletes a branch to the layout
  successor. A stack, a call, or a backward branch is still rejected.
- M5 clang: `OpenGPUTargetInfo` derives `RISCV32TargetInfo` (ILP32 layout/ABI)
  and defines `__opengpu__`/`__OPENGPU__`; it is allocated for the `opengpu`
  triple. The Driver routes `opengpu` through the BareMetal RISCV toolchain,
  defaults `-march` to `rv32imafv_zicsr_zifencei_xopengpu` and `-mcpu` to
  `opengpu`, forwards the RISCV feature list to cc1, and never emits a frame
  pointer, because `x8` is the lane index and the device ABI has no stack. Device kernels
   are marked with `__attribute__((device_kernel))`, which the target maps to
  `CallingConv::RISCV_OpenGPU` so the backend reads arguments from the kernarg
  base; a clang-built kernel now assembles and passes the driver sandbox.
  The `opengpu` triple uses the RISC-V builtin emitter, so `<riscv_vector.h>`
  lowers on that target. Menu kernels read the reserved lane index with
  `slli rd, x8, 2`, add the kernarg base, and address each slice from that
  lane. `vtex.sample` and `vquad.dfdx` are inline asm. All twelve Gallium
  programs pass the profile their assembly already used, and Gallium binds
  those words. `fragment_texture.c` is the same kind of program for the
  pipe spike: lane 0 stores zero and stops, and the other lanes store
  `vquad.dfdx` of U plus the texel. It passes profile 3. The pipe spike
  binds that image and clang `vertex_passthrough` directly. The remaining
  guest shaders (`fragment_fp`, `fragment_fp_scalar`, `fp_unary`,
  `fp_binary`, `fp_fma`, `fp_div`, `fp_compare`, `fp_scalar`, `fp_reduce`,
  `widen_alu`, `fixed_width`) are clang `-O2 -mno-relax` images too.
  `vwadd`/`vwsub`/`vwmul` are `.insn` encodings of funct6 `0x30`..`0x32`,
  because the standard mnemonics are different ops on this core. Compute
  programs that store with VL=1 stay out of the Gallium runtime-ABI list.
- M6 runtime: `userspace/libopengpu/opengpu_offload.{h,c}` is a dependency-free
  reader for the LLVM offload-binary **version 3** container
  (`clang-offload-packager`/`llvm-offload-binary` output). It decodes the header
  and entry tables explicitly (no buffer aliasing), matches a device image by
  triple (exact or component-prefix abbreviations such as `opengpu`), and
  extracts executable code: the `.text` section when the image is an ELF32
  object, or the raw bytes for a pre-linked blob. `opengpu_offload_shader`
  copies that stream into a caller buffer. Compute shader bindings are
  64-byte aligned, so `userspace/examples/compute_offload.c` zeroes a 64-byte
  GEM, copies the stream at offset 0, and submits it with `opengpu_compute`.
  The packaged kernel is `kadd` (`*p = *p + n + 3`); kernarg word 0 is the
  in/out value and word 1 is `n`. End-to-end software verification is
  `scripts/validate_offload_kernel.py`: `clang --target=opengpu` ->
  `clang-offload-packager` -> reader -> `opengpu_shader_validate_words`. Host
  unit tests live in `userspace/libopengpu/tests/opengpu_offload_test.c` and
  the reader runs in `scripts/test_driver.py`. The guest binary and
  `opengpu_kadd.fatbin` are staged by `scripts/run_arti_gpu.sh` and
  `scripts/build_userspace_guest.sh` and run from the userspace-example init.
  The fence wait is 300 seconds, matching the other simulator compute
  examples.   The 2026-10-19 vertex+fragment guest printed
  `offload compute completed; kadd 1+2+3=6`,
  `pipe vertex_draw completed; coloured=1009 sample=0xff0000ff`,
  `pipe resolve completed; mode=2x expected=0x80808080`, and then
  `OPENGPU USERSPACE EXAMPLES PASS`.

