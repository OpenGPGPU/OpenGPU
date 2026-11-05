# Bounded Gallium compute submission

The out-of-tree Mesa 22.3.6 driver registers `create_compute_state`,
`bind_compute_state`, `delete_compute_state`, `set_shader_buffers(COMPUTE)`
and `launch_grid`. Supported TGSI is compiled to native instructions and
submitted through the production private pipe, `opengpu_compute_storage`
library call and existing Linux `COMPUTE_STORAGE` ioctl. Shader admission
retains the 512-word/four-forward-branch kernel sandbox.

`PIPE_CAP_COMPUTE` remains zero and GL/GLES versions remain unchanged. These
callbacks do not establish a complete GL, GLES or OpenCL compute profile.

## Accepted contract

* Straight-line uint32 `MOV`, `UADD`, `UMUL`, `AND`, `OR`, `XOR`, `SHL`,
  `USHR` and checked buffer `LOAD`/`STORE`, with static buffer indices 0–3.
  Components and swizzles retain parallel TGSI assignment semantics. Register
  pressure or expansion beyond 512 native words fails compilation.
* `THREAD_ID.xyz`, `BLOCK_ID.xyz`, `BLOCK_SIZE.xyz` and `GRID_SIZE.xyz` use
  the actual compute initialization contract: local-linear `v1`, group
  scalars x2–4 and local-size scalars x5–7. Local coordinates use unsigned
  divide/remainder; graphics ABI conversion is not applied.
* Four distinct storage views, each at most 128 MiB, retain exact offsets,
  byte limits and read/write permissions. No range is rounded upward. Legacy
  kernel storage admission requires 4096-byte offsets and 64-byte sizes.
  `OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_BYTE_VIEWS` authorizes 256-byte
  offsets and exact positive byte lengths; absent that negotiated bit the
  legacy restrictions remain. [Byte-view admission](COMPUTE_STORAGE_BYTE_VIEWS.md)
  independently proves complete transport-line backing in the same GEM.
  Gallium's logical buffer size also bounds every view, independently of GEM
  transport padding.
* Direct grids retain all three uint32 axes and use the discovered local
  axis/product limits, including the native 128-item capacity. Fixed block
  properties must match. Zero grid axes submit no work. `work_dim == 0`
  accepts Mesa's unspecified dimension metadata; explicit dimensions require
  unused axes padded with one.
* A lazily allocated, separate compute context protects graphics shader and
  resource slots. Buffer bindings own pipe references. Successful launches
  wait for the real syncobj and completed status before reusing kernargs.
  A backend failure destroys the private context. Existing kernel context
  references preserve an accepted job's VM and bindings through cancellation
  and retirement, even if teardown's bounded wait expires. Healthy reuse
  allocates a fresh context and private buffers.

NIR/native input, floating point, control flow, shared/local/private/input
memory requests, extra memory qualifiers, indirect grids, grid bases and
partial final blocks fail closed. This increment does not expose uniform,
texture, image, counter or shared resources through Gallium.

## Local validation

With the pinned actual Mesa and libdrm headers available, run on Linux:

```sh
OPENGPU_MESA_DIR=/path/to/mesa-22.3.6 \
OPENGPU_DRM_INCLUDE=/path/to/libdrm/include \
  python3 scripts/test_gallium_compute.py
CC='cc -fsanitize=address,undefined -fno-omit-frame-pointer' \
OPENGPU_MESA_DIR=/path/to/mesa-22.3.6 \
OPENGPU_DRM_INCLUDE=/path/to/libdrm/include \
  python3 scripts/test_gallium_compute.py
bash scripts/test_scripts.sh
```

Local GCC `-O2 -Werror` and ASan/UBSan runs passed 51 compute submissions and
54 waits each; Clang strict syntax checks passed for all four production
units. The fixture links the actual Mesa parser,
registered callbacks, private pipe, library ioctl implementation and unchanged
kernel shader validator. Only OS/GEM/sync primitives are modeled. An independent
native-word CPU decoder checks every result and untouched guard for block
shapes 128×1×1, 8×4×4, 33×1×1, 1×128×1 and 5×3×7 over a 2×2×2 grid.
Changed shader constants change the actual compiled program and results.

Coverage includes four 128 MiB views and full uint32 grids as bounded metadata
cases, malformed/unsupported TGSI, exact view bounds, held references,
cross-slot ownership transfer, fixed-block mismatch, disabled discovery,
14 allocation/map/bind/sync/submit/wait/status failure and recovery types in
both legacy and byte-view modes, and graphics shader/clear → compute → clear
context isolation. Final teardown
requires no live buffer, context, job, mapping, syncobj or exported sync fd.

This is CPU compiler/submission evidence, not execution of these callbacks
on RTL or a Linux GPU guest. GPU execution of compiler-generated programs,
broader shader/API coverage, resource coexistence and ordering through those
APIs, and full H7/physical qualification remain open. The fixture starts no
GPU, guest, frame or nightly job; `test_driver.py` includes it when the actual
Mesa headers are available on Linux.

## Explicit compiler-to-CU qualification

An opt-in preparation mode exports four additional **actual** callback/lib
submissions after the unchanged kernel validator accepts their shader BOs:

```sh
OPENGPU_MESA_DIR=/path/to/mesa-22.3.6 \
OPENGPU_DRM_INCLUDE=/path/to/libdrm/include \
  python3 scripts/test_gallium_compute.py --artifacts /tmp/gallium-cu-inputs
```

The output directory must be new. Each capture contains the actual padded
shader words, TGSI tokens, kernarg bytes and ioctl grid/local/view metadata;
`manifest.properties` pins every file. Default invocation still performs
51 compute submissions/54 waits. Export mode adds four submissions/four waits
and independently checks both healthy shaders with the CPU word decoder. Its
modeled OS fault status is not evidence of a GPU fault.

`QualifyGalliumComputeStorage` is an explicit local `@DoNotDiscover`
qualification, excluded from routine PR/nightly GPU cases. It uses one real
default 16-lane/8-warp CU, native SRAM, FPU and default caches, real instruction
fetch/initialization, checked storage and Sv32, with sparse lower RAM. It
consumes the captured words and metadata without replacing the exported grid.
Two healthy 2×1×1 grids with 8×4×4 local size, separated by exact three-byte
bounds and denied-PTE tasks, retain four independent 128MiB views (except the
intentional three-byte negative view). Four actual kernel admissions dispatch
six workgroups/48 warps. Every healthy result, write ACK and untouched guard is
checked; fault retirement requires eight unique physical warp traps, held
completion/drain and subsequent fresh-root reuse. Instruction/vector SATP
remain stable throughout each active task.

After CPU preparation and within an explicitly allocated native-model window:

```sh
artifact_sha=$(shasum -a 256 /tmp/gallium-cu-inputs/manifest.properties | cut -d' ' -f1)
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -J-XX:+ExitOnOutOfMemoryError \
  -Dopengpu.galliumComputeStorageQualification=1 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  "Test / runMain opengpu.core.QualifyGalliumComputeStorage --artifacts /tmp/gallium-cu-inputs --manifest-sha256 $artifact_sha --reports /tmp/gallium-cu-reports"
```

The hard limit is 131072 cycles per parent/524288 total, with one model and no
automatic retry. This entry has not yet been qualified on RTL. Even a pass
would establish direct-CU execution of compiler output, not execution of the
Gallium/Linux ioctl chain, L2/CPU coherence, cross-CU ordering, API resource
coexistence, full GLES3.1, physical qualification or H7 completion. The sparse
backing proves full descriptor authority and touched bytes, without traversing
the complete 512MiB range.
