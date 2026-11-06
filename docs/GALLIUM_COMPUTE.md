# Bounded Gallium compute submission

The out-of-tree Mesa 22.3.6 driver registers `create_compute_state`,
`bind_compute_state`, `delete_compute_state`, `set_shader_buffers(COMPUTE)`
and `launch_grid`. Supported TGSI is compiled to native instructions and
submitted through the production private pipe and library to the existing
Linux `COMPUTE_STORAGE` or `COMPUTE_UNIFORM_RESOURCES` ioctl. Shader admission
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
* Integer `UIF`/`ELSE`/`ENDIF` lower to canonical forward native SIMT scopes.
  Conditions use per-lane full-width values; the hardware warp mask is separate
  from packed v0 scratch predicates. Four nested scopes consume at most eight
  physical stack entries. TEMP definitions and `ADDR[0].x` lifetime intersect
  across both arms, retaining all twenty native TEMP components. Warp-shared
  x10 must be redefined in each arm and after convergence. Resource capture
  credits cannot cross these boundaries. Admission permits only the bounded
  full-lane integer/resource dialect before and within these scopes; earlier
  short-VL or lane-zero-only definitions cannot authorize a full-warp branch.
  VL/CSR changes, barriers, scalar CFG mixing and early native `CEASE` inside
  a scope are rejected. A GLSL early return is usable when Mesa lowers it to
  this finite structured form. Loops, break and continue remain unsupported.
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
* With `OPENGPU_DRIVER_FEATURE_COMPUTE_UNIFORMS`, compute constant buffer 0
  holds up to 1024 default components; buffers 1–12 hold twelve 16 KiB UBO views.
  TGSI `CONST[slot][vec4].swizzle` reads use the existing checked uniform
  instruction. The CB dimension remains static; element indirection supports
  one defined `ADDR[0].x`, written by unsigned `UARL`. Its complete uint32 value
  is checked before vec4 scaling/base addition. Invalid lanes request the
  aligned out-of-view offset 16384, instead of wrapping into a valid word.
  Each dispatch validates its required byte extents and requests a
  fresh GUM1 immutable snapshot. GEM-backed buffers retain their original
  handles/ranges so the kernel waits producers before copying. User defaults
  are copied into a fresh owned GEM at bind, including replacement after a
  failed job. Exact logical and GEM bounds are separate; neither is rounded.
  Unused bound constants do not change storage-only dispatches. Without the
  feature bit the compute constant limits remain zero and CONST is rejected.
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

NIR/native input, floating point, unstructured control flow and loops, shared/local/private/input
memory requests, extra memory qualifiers, indirect CB dimensions or other
address registers, indirect grids, grid bases and partial final blocks fail closed. Texture,
image, counter and shared resources remain unavailable through these callbacks.

## Forward if/else CPU acceptance

The explicit CPU fixture links the real ESSL310 parser, NIR/TGSI passes,
Gallium callbacks, private pipe and library. The original kernel word validator
checks every submitted program. Only OS/GEM/fence primitives are modeled; an
independent mask/stack decoder checks arithmetic results against source formulas.
It does not establish hardware reconvergence, job fault order or drain.

```sh
# Use the matching configured native Mesa objects and the exact checked
# nir_lower_io.c in a private Mesa worktree; shared Mesa/build stays read-only.
OPENGPU_MESA_DIR=/path/to/mesa-22.3.6 \
OPENGPU_MESA_BUILD=/path/to/matching-mesa-build \
OPENGPU_DRM_INCLUDE=/path/to/drm-headers \
python3 scripts/test_gallium_compute_control_flow.py \
  --build .artifacts/compute-cf-cpu \
  --mesa-nir-lower-io /path/to/private-mesa/src/compiler/nir/nir_lower_io.c
# Repeat in a new output directory with --sanitize for ASan/UBSan.
python3 scripts/test_driver.py
```

GCC `-O2 -Werror` and ASan/UBSan pass the original 51-submit/54-wait corpus,
divergent stores, if without else, four-level nesting, Mesa-lowered early
return, path definitions, all twenty TEMP components and rejection of a
twenty-first component. Dynamic default reads and `UCMP` inside an arm change
v0 without changing the outer lane mask; invalid indices in inactive lanes
cause no CPU dereference. These CPU fault probes do not establish device trap
or cancellation order; the separate CU qualification below supplies native
evidence. Damaged TGSI labels and missing
structure fail before output publication. Fixed/runtime loops, break and a
fifth nested scope fail closed through the real frontend/callback path.

Two 439-word programs retain 1024 default components, twelve independent 16KiB
UBOs and four independent 128MiB SSBO views, with two workgroups and 128/125
local items. Per-word output and unchanged guards pass after immutable snapshot
publication and four injected failure/recovery paths. Smaller branch-shape
probes bind their actual 64KiB prefixes. These bounds do not imply exhaustive
512MiB data traversal. Forty-one actual TGSI/native/GUM1/CB output files match
byte-for-byte between GCC and sanitizer runs.

All legacy driver tests and 48 canonical SIMT validator cases pass. Prepared
Linux7.2 AArch64 GCC15.2 module builds use the same current driver inputs with
only the validator changed. Both build successfully with `-j1`; the validator
frame is 2016 bytes before and 2064 bytes after (`-fstack-usage`). The existing
four snapshots are reused without another scope/state array. On the current
driver, `opengpu_submit_test` is 304 bytes in both builds. Reproduce against a
configured kernel using a private copy of `driver/`:

```sh
gmake -C /path/to/prepared-arm64-kernel M=/path/to/private-driver \
  ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- \
  KCFLAGS=-fstack-usage -j1 gpu_drv.ko
```

## Explicit forward if/else CU qualification

Five actual ESSL310/Mesa/Gallium/private-pipe/kernel-validator exports passed
`QualifyGalliumComputeControlFlow` on one default 16-lane/8-warp CU with native
SRAM, FPU, default caches, real checked uniform/storage services and Sv32.
The real kernel controller accepts five parents and dispatches seven groups;
the fixture observes 56 warp starts and checks 762 healthy workitems. It does
not decompose or rewrite the exported grids or replace their instructions.

| Parent | Words | Local / grid | Writes and ACKs | Uniform data reads | Warp traps | Cycles |
| --- | ---: | --- | ---: | ---: | ---: | ---: |
| full128 | 439 | 8×4×4 / 2×1×1 | 1024 | 416 | 0 | 63738 |
| nested | 194 | 8×4×4 / 1×1×1 | 128 | 0 | 0 | 8473 |
| masked | 222 | 8×4×4 / 1×1×1 | 256 | 256 | 0 | 23804 |
| selected-fault | 222 | 8×4×4 / 1×1×1 | 64 | 0 | 8 | 6938 |
| fresh125 | 439 | 5×5×5 / 2×1×1 | 1000 | 416 | 0 | 63153 |

The full cases retain thirteen independent CB publications: 1024 default
components and twelve 16KiB blocks, plus four independent 128MiB storage views.
They sample both uniform endpoints and exercise all four storage descriptors;
the final uniform allocation ends exactly at 4GiB. The smaller shapes retain
their actual 64KiB views and consume only their emitted descriptor subset.
Sparse backing and guards cover the accessed bytes without traversing the
untouched 512MiB interior. Every result is checked against an independent source
formula; all immutable code, records, page tables, input and guard bytes remain
unchanged. The 128/125 shaders retain their different local-ID arithmetic.

Four nested scopes exercise the default eight-entry stack capacity. The masked
case checks an outer divergent mask while dynamic CONST and `UCMP` alter v0.
Its real vec4 CONST load makes four component instructions: 64 selected lanes
produce 256 raw loads, each with a fresh checked root and binding. The fault
case requires eight unique accepted warp traps at the first selected-invalid
uniform instruction, mask `2`, cause `5`, tval `0`, eight root/view checks and
zero uniform data reads. Exact preceding even-lane stores survive; a fresh
context, roots and generation then execute the 125-item task with thirteen
active lanes in its final warp.

All five cases hold completion valid and bits for thirteen cycles, block the
next admission, retain leases and active I/V SATP, and drain offered/accepted
memory before reuse. Requests see backpressure; reads and write ACKs are delayed
and reordered. Actual response-ready hold cycles were 0/0/2/2/1; these are
observations rather than a forced coverage promise. The run totals 166106
cycles under fixed per-parent cutoffs 524288/131072/196608/131072/524288 and a
1507328-cycle total cutoff. These are bounded fixture cycles, not a product
throughput or frame-rate measurement.

One fresh model was built. Its first run stopped on an incorrect test demand
for kernarg reads: all five actual programs contain zero scalar load words and
use initialized builtins instead. The corrected test requires zero extra reads.
A bounded natural-cache HIT then ran the five cases on the identical binary,
with generation forbidden, and the JVM exited zero. Its outer recorder retained
a failure verdict because it compared transient staging-SV collections. A
separate replay of the actual file list and complete literal-include closure
verified all 359 consumed SV files and both C++ drivers byte-for-byte; the
original failure records were preserved. No RTL, input, delay or correctness
oracle was changed to obtain these results.

Reproduce outside regular PR/nightly lanes, using new output directories and
the matching Mesa configuration from the CPU instructions above:

```sh
OPENGPU_MESA_DIR=/path/to/mesa-22.3.6 \
OPENGPU_MESA_BUILD=/path/to/matching-mesa-build \
OPENGPU_DRM_INCLUDE=/path/to/drm-headers \
python3 scripts/test_gallium_compute_control_flow.py \
  --build .artifacts/cf-cpu --artifacts .artifacts/cf-inputs \
  --mesa-nir-lower-io /path/to/private-mesa/src/compiler/nir/nir_lower_io.c
cf_manifest=$(shasum -a256 .artifacts/cf-inputs/manifest.properties | cut -d' ' -f1)
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -J-XX:+ExitOnOutOfMemoryError \
  -Dopengpu.galliumComputeControlFlowQualification=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simReleaseHeap=1 -Dopengpu.simSeparateBuild=1 \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "QualifyGalliumComputeControlFlow.scala").contains(f.getName))' \
  "Test / runMain opengpu.core.QualifyGalliumComputeControlFlow --artifacts .artifacts/cf-inputs --manifest-sha256 $cf_manifest --reports .artifacts/cf-reports"
```

Retain original process ownership and enforce a 1800-second outer limit and
7GiB sampled aggregate task RSS stop, including surviving descendants. Require
all five case markers, XML success, exact words/guards/ACKs/traps, unchanged
inputs and complete producer exit. No frame, guest or routine GPU CI case is
added. Final current-main GCC/ASan exports match all 73 hardware input files;
both ARM64 modules and the full driver CPU tests pass. The two upstream Main
changes affect discovery/ABI emission outside this CU's dependency graph; this
is a static source/class bridge, not another hardware run.

This is direct-CU evidence. Linux execution of the new GLSL/uniform/control-flow
chain, loops/break, complete compiler and GL/GLES APIs, whole-system ordering,
four-CU coexistence, physical acceptance and full H7 remain open. Capability
declarations stay disabled.

## Explicit GLSL constant-buffer CPU acceptance

With matching native Mesa 22.3.6 configured static objects, this opt-in test
links the actual GLSL parser/linker, `glsl_to_nir`, NIR uniform/buffer passes,
`nir_to_tgsi`, `st_bind_cs_ubos` and `st_bind_cs_ssbos` to the current driver.
It privately compiles the small official standalone scaffold and required
utility sources; it does not rebuild or modify the shared Mesa build. The
compiler-only context accepts actual `#version 310 es` source without changing
the product's CAP_COMPUTE, ESSL or LOAD_CONSTBUF settings. It is not a GL context.

Production `stage_mesa_opengpu.sh` applies the pinned Mesa 22.3.6
`nir_lower_io.c` patch before staging the driver. It checks the complete upstream
preimage or exact patched postimage, is idempotent, and refuses unknown local
changes. The normal `build_mesa_opengpu.sh` uses this path. The CPU fixture
privately compiles the same patched file before the existing real `libnir`
archive; it refuses an unpatched/unknown source. To leave shared Mesa untouched,
apply it to a separate Mesa 22.3.6 worktree and pass that one source file:

```sh
python3 scripts/apply_mesa_uniform_index_patch.py --mesa /path/to/private-mesa-22.3.6
OPENGPU_MESA_DIR=/path/to/mesa-22.3.6 \
OPENGPU_MESA_BUILD=/path/to/matching-mesa-build \
OPENGPU_DRM_INCLUDE=/path/to/libdrm/include \
  python3 scripts/test_gallium_compute_uniforms.py --build /tmp/new-gum-cpu \
    --mesa-nir-lower-io /path/to/private-mesa-22.3.6/src/compiler/nir/nir_lower_io.c
OPENGPU_MESA_DIR=/path/to/mesa-22.3.6 \
OPENGPU_MESA_BUILD=/path/to/matching-mesa-build \
OPENGPU_DRM_INCLUDE=/path/to/libdrm/include \
  python3 scripts/test_gallium_compute_uniforms.py --sanitize --build /tmp/new-gum-sanitized \
    --mesa-nir-lower-io /path/to/private-mesa-22.3.6/src/compiler/nir/nir_lower_io.c
python3 scripts/test_mesa_uniform_index_patch.py --mesa /path/to/mesa-22.3.6 \
  --out /tmp/new-private-stage-test
```

GCC `-O2 -Werror` and ASan/UBSan passed with one CPU/768MiB. Both full-capacity
ESSL shaders produce 309 real TGSI tokens and 381 native words. Thirteen
distinct constant GEMs provide all 1024 defaults and twelve full 16 KiB views
alongside four independent 128 MiB storage descriptors. Independent slot/word
data and an ordered arithmetic formula check the sampled first/last words,
128- and125-item groups, all four output views and untouched guards. A smaller
87-token/124-word shader checks all four constant swizzles. Actual Mesa maps
global UBO binding 35 to its program-local CB and reorders SSBO slots; the test
uses those functions instead of assuming identity mappings.

Every accepted shader GEM passes the unchanged real kernel
`opengpu_compute_shader_validate_words_uniform_resources` before the independent
CPU word decoder runs. Twenty-seven uniform submissions take 158 source snapshots.
The fixture checks per-dispatch copying, source mutation after the modeled
snapshot, modeled producer publication before copying, GEM-backed defaults,
both ownership modes, replacement/unbind/teardown and allocation/map/submit/
wait/status failures followed by healthy reuse. Actual ESSL rejects 1028 default
components, thirteen blocks and a 16400-byte block. Malformed dimensions,
undefined/unsupported address forms and expansion beyond 512 native words fail
closed in the driver; ESSL310 itself rejects a nonconstant uniform-block-array index.

Eight real ESSL programs (187–219 native words) check dynamic defaults/UBO
elements, signed indices, nested arrays, a nonzero member base, and intentional
explicit masks in both default and named blocks. Two fresh epochs check all
four full-size storage descriptors, results/guards and source mutation after
each modeled snapshot. Eighty-four additional CPU word-decoder probes cover
first/last elements and mixed valid/invalid lanes, including `2^28`, `2^30` and
`0xffffffff`; 64 fail as expected and 20 intentionally masked cases remain
valid. `UARL` captures survive TEMP overwrites and are replaced by later `UARL`;
nine malformed/unsupported address cases leave compiler output unchanged.

The Mesa patch compares each sized typed-array index at its original NIR width
before offset narrowing, then selects an out-of-view offset after the relevant
base/stride arithmetic. This fixes the observed original lowering where
`2^28 * 16` became zero before TGSI/native admission. Default uniform and
32-bit index/offset UBO loads are covered; pointer/unsized forms and dynamic CB
selection are outside this supported shader subset. Legal shader arithmetic
and explicit index masks retain their uint32 semantics.
Relinking the same CPU fixture with the original `libnir` object reproduced
that invalid-index alias and failed the fault assertion; the patched path passes.

ESSL310 leaves dynamic array out-of-bounds accesses undefined; robust buffer
confinement does not itself promise this project's stronger no-wrap view fault.
CAP_COMPUTE and API robustness remain disabled.
([ESSL310 §4.1.9](https://registry.khronos.org/OpenGL/specs/es/3.1/GLSL_ES_Specification_3.10.pdf),
[KHR robust buffer behavior](https://registry.khronos.org/OpenGL/extensions/KHR/KHR_robust_buffer_access_behavior.txt))

The output directory contains the actual source, NIR/reflection, TGSI, native
words, GUM1 requests, snapshot payloads, compiler commands/dependencies and link
map. No generated output is committed. OS/GEM/fence and the kernel snapshot
boundary are modeled in this CPU fixture. Existing Mesa archives are not
sanitizer-instrumented; leak checking for compiler process globals is disabled,
while explicit teardown requires every modeled resource/job/ref to be released.
The default 51-submission/54-wait corpus remains unchanged. This adds no regular
GPU/CI case and establishes no new running Linux or hardware result for GLSL
uniforms. CPU fault probes establish mathematical failure without invalid
dereferences; their all-lane precheck/next-warp progression does not model
hardware fault order, cancellation or drain. Source inspection of the existing
vector scoreboard/RF issue and uniform operand capture supports the load's
destination/selector alias; this is not a new native execution result.
Public GL/GLES compute admission, general indexing/control flow,
remaining resource/API coexistence and ordering, four-CU and physical/H7
acceptance remain open.

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

These CPU fixtures establish compiler/submission behavior; the explicit Linux
qualification below separately exercises the running callbacks and ioctl chain.
Broader shader/API coverage, resource coexistence and ordering through those
APIs, and full H7/physical qualification remain open. The CPU fixture starts no
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

This qualifier establishes direct-CU execution of compiler output. It does not
exercise the running Gallium/Linux ioctl chain or L2/CPU visibility; those have
the separate bounded witness below. Cross-CU/cross-domain ordering, four-CU
whole-product execution, API resource coexistence, full GLES3.1, physical
qualification and H7 completion remain open. The sparse backing proves full
descriptor authority and touched bytes without traversing the complete
512MiB range.

## Explicit headless Linux qualification

`userspace/examples/gallium_compute.c` uses the real screen and compute
callbacks, private pipe and library against a DRM device. Its five
compute parents are producer/consumer at local8×4×4/grid2×1×1, an exact
three-byte input fault, then fresh producer/consumer contexts at
local5×5×5/grid2×1×1. Four distinct 128MiB GEMs retain full view authority.
The final item selects the actual last word with `UCMP`; other items use the
prefix. CPU initialization/readback touches only an 8192-byte prefix and
64-byte tail per GEM. Full allocation/security zeroing still covers 512MiB.
The producer completes before the consumer, with no intervening CPU source
write. This targets synchronous original-GEM visibility, not a forced pending
reservation wait. `PIPE_CAP_COMPUTE` remains zero.

On Linux/AArch64, with an existing configured Mesa 22.3.6 AArch64 build:

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

The actual static executable also passed its DRM path on Linux 7.2, using the
current production module and one fresh `EmitNativeLinuxResourceGuest` model.
The model has two default 16-lane/8-warp CUs, 128-item capacity, native SRAM,
FPU, default caches/L2, 32-byte lower AXI and all compute resource services.
This integer workload exercises four SSBOs through the real Mesa callbacks,
private pipe, library ioctl, kernel validator, MMIO submission, Sv32 and
checked storage. It does not substitute a word interpreter or fake OS in the
guest.

| Parent | Native words | Local size | Grid | Observed result |
| --- | ---: | --- | --- | --- |
| Producer, seed `0x12345` | 161 | 8×4×4 | 2×1×1 | 256 results and guards pass |
| Consumer, original GEMs | 210 | 8×4×4 | 2×1×1 | 256 results and guards pass |
| Exact three-byte input | 210 | 8×4×4 | 1×1×1 | public `EIO`, new fault identity, guards intact |
| Fresh producer, seed `0xffffffff` | 160 | 5×5×5 | 2×1×1 | 250 results and guards pass |
| Fresh consumer, original GEMs | 208 | 5×5×5 | 2×1×1 | 250 results and guards pass |

Each readback checks all 8256 bounded prefix/tail words against an independent
C formula, including unchanged guards. The last item accesses offset 134217724
in every full-size view. Five compute submissions account for nine metadata
workgroups/72 metadata physical warps, 1012 healthy producer/consumer item
executions and 506 distinct result items. The public Linux interface does not
observe individual warp traps or scheduling. The observer verifies the actual
GCR1 two-CU/native-capacity profile and lifecycle peak of two active CUs; this
peak is not attributed to a particular resource kernel. Both epochs publish
33024 CPU bytes in 516 complete maintenance-line envelopes, rather than
traversing 512MiB of GPU maintenance. Full GEM allocation/security zeroing is
retained.

The single guest exited zero after normal powerdown in about 200 seconds,
with peak owned RSS 0.94 GB and unchanged input identities. This is qualification
wall time, not hardware execution time or an FPS measurement. The fresh native
build used one compiler worker and explicit 3GiB/two-CPU Java arguments, with
peak aggregate owned RSS 5.37 GB. The final serial/host checks include exact
fault status/sequence/command identity, fresh contexts after failure, all word
oracles and normal shutdown. No retry, extra guest or frame was required.

To reproduce outside routine CI, use a configured Linux 7.2/AArch64 kernel and
matching compiler, real Mesa archives, ARTI with the 256-bit memory bridge,
existing QEMU link objects/firmware, and the pinned SRAM submodule. Use new,
private module/model/rootfs output directories. Build the production module:

```sh
make -f "$kernel_source/Makefile" O="$private_prepared_objtree" \
  M="$absolute_driver_copy" MO="$new_module_out" ARCH=arm64 \
  CROSS_COMPILE="$cross_prefix" \
  'KCFLAGS=-DOPENGPU_DRAW_WAIT_MS=900000 -DOPENGPU_FENCE_SLICE_MS=4 -DOPENGPU_DEFAULT_WIDTH=16 -DOPENGPU_DEFAULT_HEIGHT=16' \
  -j1 gpu_drv.ko
"${cross_prefix}gcc" -static -O2 -Wall -Wextra -Werror \
  -DARTI_TEST_HOLD_SECONDS=1 driver/tests/arti-linux-init.c -o "$rootfs/init"
cp "$new_module_out/gpu_drv.ko" "$rootfs/arti_driver.ko"
cp /tmp/new-gallium-runtime/gallium_compute "$rootfs/opengpu_drm_test"
```

Create `proc`, `sys`, `dev` and `tmp` directories in that rootfs. Copy the
matching `backlight`, `drm`, `drm_kms_helper`, `drm_dma_helper`, `gpu-sched`,
`drm_exec` and `drm_client_lib` modules as `arti_dep_NAME.ko`; list their absolute
rootfs names, in that order, one per line in `arti_driver_deps`. Package and
build the explicit model:

```sh
(cd "$rootfs" && find . -print0 | cpio --null -o --format=newc) | gzip -n9 > "$initramfs"
PYTHONPATH="$arti_root/src" OPENGPU_NATIVE_LINUX_RESOURCE_QUALIFICATION=1 \
  python3 scripts/build_arti_native_compute_model.py --run \
    --qemu-build "$qemu_build" --arti "$arti_root" \
    --sram "$sram_root" --firmware "$qemu_firmware" --dtc "$dtc" \
    --output "$new_model_out" --make "$make_bin" \
    --verilator-root "$verilator_root"
```

Within an explicit guest window, retain the original process handle, enforce
a 7200-second outer timeout and 8GiB aggregate task RSS cap, and run exactly once:

```sh
env -i PATH=/usr/bin:/bin:/usr/sbin:/sbin LANG=C LC_ALL=C TZ=UTC \
  ARTI_NATIVE_REQUIRE_TWO_CUS=1 "$new_model_out/qemu-system-aarch64" \
  -machine virt -accel tcg -cpu cortex-a53 -m2048M -L "$qemu_firmware" \
  -display none -monitor none -serial "file:$serial_log" \
  -kernel "$kernel_image" -initrd "$initramfs" -dtb "$new_model_out/guest.dtb" \
  -append 'console=ttyAMA0 cma=768M@0x60000000'
```

Require the ordered producer/consumer, exact-fault and fresh-reuse `PASS`
markers, final `GALLIUM_LINUX_RUNTIME` counts, normal Linux powerdown, observer
`actualProfile=true peakActiveCUs=2 status=PASS`, exit 0, unchanged input hashes
and no remaining owned process. The module's 900000ms fence timeout is fixture
configuration; it does not change production defaults. Keep normal
`PIPE_CAP_COMPUTE=0` and resource limits.

This proves bounded synchronous visibility of original GEM storage through
running Linux and actual hardware. It does not force a pending reservation
wait or establish arbitrary cached cross-domain barriers. Uniform, texture,
image, counter and shared-resource coexistence through Gallium, broader
compiler/GL/GLES APIs, four-CU product execution, physical qualification and
full H7 remain open. No regular PR/nightly GPU case is added.
