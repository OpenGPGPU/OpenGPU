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
  `USHR`, `USEQ`, `USNE`, `USLT`, `USGE`, three-source `UCMP` and checked
  buffer `LOAD`/`STORE`, with static buffer indices 0–3. Comparisons produce
  a full `0xffffffff` or zero per component. `UCMP` selects its second source
  for any nonzero first source and its third source otherwise. Native packed
  predicates are materialized before becoming TGSI values.
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
* CPU `PIPE_BUFFER` maps require level zero and a positive one-dimensional
  byte box within both the logical buffer and actual GEM allocation. Write
  unmap invalidates only the mapped interval's complete 64-byte line envelope
  and waits for its completion. GEM padding does not enlarge a shader view.
  Read-only maps submit no invalidation; textures retain their existing
  addressing and whole-GEM publication path.

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
OPENGPU_MESA_DIR=/path/to/mesa-22.3.6 \
OPENGPU_DRM_INCLUDE=/path/to/libdrm/include \
  python3 scripts/test_driver.py
bash scripts/test_scripts.sh
```

Local GCC `-O2 -Werror` and ASan/UBSan runs passed 51 compute submissions and
54 waits each. The fixture links the actual Mesa parser,
registered callbacks, private pipe, library ioctl implementation and unchanged
kernel shader validator. Only OS/GEM/sync primitives are modeled. An independent
native-word CPU decoder checks every result and untouched guard for block
shapes 128×1×1, 8×4×4, 33×1×1, 1×128×1 and 5×3×7 over a 2×2×2 grid.
Changed shader constants change the actual compiled program and results.
An additional 20 compiler/validator/word-decoder cases cover 4860 work-items
across five boundary seeds and four local shapes, including 125-item and
105-item groups. They check comparison masks, arbitrary nonzero conditions,
three-source destination aliases and every guard without adding submissions.
Eight further shaders directly store all four comparison results: 4096 words
must equal exactly zero or `0xffffffff`, before any `UCMP` or bit extraction.
Ten predicate-specific malformed/unsupported cases retain the unchanged
caller output, register limit and 512-word sandbox on failure.

Coverage includes four 128 MiB views and full uint32 grids as bounded metadata
cases, malformed/unsupported TGSI, exact view bounds, held references,
cross-slot ownership transfer, fixed-block mismatch, disabled discovery,
14 allocation/map/bind/sync/submit/wait/status failure and recovery types in
both legacy and byte-view modes, and graphics shader/clear → compute → clear
context isolation. Final teardown
requires no live buffer, context, job, mapping, syncobj or exported sync fd.

The same CPU fixture separately checks eight buffer publication ranges,
including unaligned and page-crossing writes and a logical partial final
line in a larger padded GEM. A sparse 128MiB mapping tests its last byte and
full-range metadata while touching only two endpoint bytes. Nineteen invalid
map cases issue no ioctl. Read-only and texture isolation, six invalidate/
fence failure and recovery paths, retained job ownership after a wait timeout,
and preserved error reporting pass GCC and ASan/UBSan. These add 22 invalidate
attempts, 21 accepted jobs and 21 waits independently of the original 51
compute submissions/54 waits. This verifies the requested maintenance ranges
through the real callbacks, pipe and library; it does not measure hardware
maintenance latency or prove running Linux/GPU cache visibility.

This is CPU compiler/submission evidence, not execution of these callbacks
on RTL or a Linux GPU guest. Execution of the running Gallium/Linux chain,
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
Two healthy 2×1×1 grids with 8×4×4 and 5×5×5 local sizes, separated by exact
three-byte bounds and denied-PTE tasks, retain four independent 128MiB views (except the
intentional three-byte negative view). Four actual kernel admissions dispatch
six workgroups/48 warps. Every healthy result, write ACK and untouched guard is
checked; fault retirement requires eight unique physical warp traps, held
completion/drain and subsequent fresh-root reuse. Instruction/vector SATP
remain stable throughout each active task.
The actual exported shaders include all four comparisons and `UCMP`, with
mixed lane predicates and overlapping source/destination swizzles. The fresh
125-item group still uses eight physical warps; its last warp has thirteen
active lanes. Expected traffic comes from the captured grid/local sizes:
256 reads/768 writes for A, 250 reads/750 writes for B.

After CPU preparation and within an explicitly allocated native-model window:

```sh
artifact_sha=$(shasum -a 256 /tmp/gallium-cu-inputs/manifest.properties | cut -d' ' -f1)
GPU_SIM_DEBUG=1 sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -J-XX:+ExitOnOutOfMemoryError \
  -Dopengpu.galliumComputeStorageQualification=1 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simCcache=off \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'set Test / unmanagedSources := Seq(baseDirectory.value / "src/test/scala/opengpu/testutil/GpuSim.scala", baseDirectory.value / "src/test/scala/opengpu/core/QualifyGalliumComputeStorage.scala")' \
  "Test / runMain opengpu.core.QualifyGalliumComputeStorage --artifacts /tmp/gallium-cu-inputs --manifest-sha256 $artifact_sha --reports /tmp/gallium-cu-reports"
```

The hard limit is 131072 cycles per parent/524288 total, with one model and no
automatic retry. All four predicate tasks passed on one fresh native model,
totaling 89320 cycles and 506 healthy work-items:

| Parent | Native words | Cycles | Data reads | Writes / ACKs | Unique warp traps |
| --- | ---: | ---: | ---: | ---: | ---: |
| Healthy A | 210 | 42935 | 256 | 768 / 768 | 0 |
| Exact three-byte bounds | 210 | 2365 | 0 | 0 / 0 | 8 |
| Denied Sv32 PTE | 210 | 2451 | 0 | 0 / 0 | 8 |
| Fresh healthy B | 208 | 41569 | 250 | 750 / 750 | 0 |

The second seed legally lowers to two fewer instructions; both actual outputs
also passed the independent CPU decoder. Requests encountered backpressure;
replies were delayed and selected out of order. Response-ready stalls were
0, 1, 0 and 1 cycles in table order; extended response-backpressure
qualification remains open. Traps were held for nine
cycles, public completion for thirteen, and every accepted write was ACKed
before completion. The lower port retains 64-byte lines and four-bit IDs:
base traffic allocates IDs 0–3 dynamically; storage descriptor/data/PTW uses
8/9/10. The native FPU backend is present; this workload exercises integer
instructions.

This establishes direct-CU execution of compiler output. Execution of the
running Gallium/Linux ioctl chain, L2/CPU coherence, cross-CU ordering,
four-CU whole-product execution, API resource coexistence, full GLES3.1,
physical qualification and H7 completion remain open. The sparse backing
proves full descriptor authority and touched bytes without traversing the
complete 512MiB range.

## Headless Linux runtime preparation

`userspace/examples/gallium_compute.c` uses the real screen and compute
callbacks, private pipe and library against a DRM device. Its intended five
compute parents are producer/consumer at local8×4×4/grid2×1×1, an exact
three-byte input fault, then fresh producer/consumer contexts at
local5×5×5/grid2×1×1. Four distinct 128MiB GEMs retain full view authority.
The final item selects the actual last word with `UCMP`; other items use the
prefix. CPU initialization/readback touches only an 8192-byte prefix and
64-byte tail per GEM. Full allocation/security zeroing still covers 512MiB.
The producer completes before the consumer, with no intervening CPU source
write. This targets synchronous original-GEM visibility, not a forced pending
reservation wait. `PIPE_CAP_COMPUTE` remains zero.

On Linux/AArch64, with an existing configured Mesa22.3.6 AArch64 build:

```sh
python3 scripts/build_gallium_compute_runtime.py \
  --mesa-source /path/to/mesa-22.3.6 --mesa-build /path/to/mesa-build \
  --out /tmp/new-gallium-runtime --run-preflight --sanitize-preflight
```

The isolated builder compiles the eight current application/production units
and reads real Mesa auxiliary/util/format/C11 archives. It does not stage,
reconfigure or rebuild the shared Mesa tree. The final static AArch64 ELF
linked seven required real Mesa helpers, with no shared-library dependency,
uploader/OS/TGSI stubs or CPU word decoder. The ordinary link trace selected
29 actual Mesa objects; their original Ninja dependencies included generated
sources/headers and were current. No GLSL/NIR compiler archive was needed.

The actual ELF's device-free preflight passed the unchanged kernel validator
for four programs of 161/210/160/208 words, plus short-kernarg and illegal-word
rejection. A separate ASan/UBSan CPU decoder/emitter executable checked all
1012 producer/consumer item executions and every prefix/tail guard, including
the true final word and thirteen active lanes in the last 125-item warp.
Local compilation/linking used one CPU and 768MiB. The original default
51-submission/54-wait fixture is unchanged.

This is stub-free linkage and CPU preparation only. The executable has not
run its DRM path on a Linux guest/GPU. Real completion/fault/reuse, public
ioctl-to-hardware execution and cache visibility require a separately approved
host model/guest qualification. No module, JVM, emission, RTL model, guest,
frame or nightly job was started for this preparation. Broader compiler/API,
resource coexistence/ordering, four-CU product, physical and full H7 gates
remain open.
