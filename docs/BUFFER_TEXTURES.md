# Native buffer texture views

Target 5 adds buffer-backed views to the native typed texture and GIU1 image
owners. It implements integer fetch, scalar texture size, image load/store
addressing and R32 image atomic addressing. GLES capability advertisement stays
disabled. Linux copied texture/image views carry the native count and range;
versioned discovery gates their admission. The bounded Gallium compute path
below lowers buffer operations and submits retained views, with CPU composition
evidence. Composed native execution, complete API/compiler coverage and general
visibility integration remain required for E32-04o.

The format policy follows [GLES 3.2 §8.9/table 8.18](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf)
and [EXT_texture_buffer](https://registry.khronos.org/OpenGL/extensions/EXT/EXT_texture_buffer.txt).
The sampler view admits exactly 30 existing storage-format IDs. GIU1 retains its
separate 13 image formats, access permissions and R32I/R32UI atomic restriction;
sampler eligibility does not grant image writes.

## Address and record contract

The existing 128-byte GXI1/GXL1 and 256-byte GIU1 layouts keep their sizes and all
old offsets. New fields occupy previously reserved words:

| Record | 32-bit texel count | 33-bit logical byte range |
|---|---|---|
| GXI1 | word 28 | words 29–30 |
| GXL1 | word 25 | words 26–27 |
| GIU1 view | word 38 | words 39–40 |

Targets 0–4 require these fields to be zero. Their dimensions, capacities and
format IDs are unchanged. Existing execution restrictions on cube arrays are
also unchanged.

Buffer views use level/baseLevel/maxLevel 0, dimensions/layers 1, zero pitches
and uncompressed storage. `base` identifies the range start; the independently
checked allocation bounds identify retained backing. The count is
`min(floor(bufferBytes / bytesPerTexel), maxBufferTexels)`. The default limit is
2^27 texels and configurations must support at least 65536; the 14-bit image
width is not a buffer limit. The entire declared byte range must fit backing,
including unused trailing bytes or bytes beyond a clamped count. Products and
sums retain full width before accepting the exclusive 2^32 endpoint. Native
byte alignment is 1; no GL binding-alignment capability is advertised.

Integer fetch uses full 32-bit x and requires y/z/layer/face and offsets 0.
Normalized sampling is rejected. Texture size returns the 32-bit count in
component 0 and zero in other components, without reading sampler or texel data.
An admitted buffer GXB bypasses GXS entirely; GXI must independently confirm
that target. Missing, malformed or faulting sampler records cannot affect a
buffer fetch. Nonbuffer bindings in a mixed table retain their sampler,
identity, mip-completeness and fault checks.

The C encoders, copied snapshot validators, table/bound-view builders and owned
tessellation metadata builder support these fields. Buffer-only roots can omit
the sampler table; mixed roots retain the ordinary sampler span. Owned admission
checks the whole buffer range against mapped retained backing, read dependencies
and existing alias rules. C scheduler/VM validation and component execution are
separate evidence, not a composed buffer-texture host or GLES API qualification.

## Linux copied views

The existing texture and image submission ioctls carry target 5 without changing
their numbers, structure sizes or offsets. For this target only, `width` is
aliased as `buffer_texels`, and each level's/image's 64-bit `row_pitch` is
aliased as `buffer_bytes`. Other targets retain their original dimension and
pitch meanings. A buffer texture has exactly one level, numbered 0; its root
and level counts must both match the count derived from the supplied range.

Preparation checks the full binding envelope and offset/range before deriving
the count, including incomplete trailing texels and bytes beyond the count
limit. It normalizes the native image dimensions to 1 and native pitches to 0.
`userspace/opengpu_buffer_texture.h` provides copied-request constructors with
the same range and distinct sampler/image format checks; it does not bind,
retain or submit a BO, or establish hardware support.

The existing resource bind path maps the whole binding, including its last
partial page, and waits for the previous context fence before replacing it.
Submission retains the existing BO references and read/write reservation usage.
Read-only textures and writable image views retain the established alias rules.
The current texture namespace has a 4 MiB window per binding; this is a Linux
mapping limit, not a reduction of the native 2^27-texel count capacity.

These copied views alone do not establish general visibility or composed
execution. GLES buffer capabilities remain disabled.

## Native transport discovery

GCR1 `FLAGS` adds two independent version flags without changing its layout:

| GCR1 bit | Native contract | Linux driver feature bit |
|---|---|---|
| 13 | Target 5 sampler records and fetch/size | 28, `COMPUTE_BUFFER_TEXTURE` |
| 14 | Target 5 GIU records and function 27 image size | 29, `COMPUTE_BUFFER_IMAGES` |

The hardware flags follow the actual compute texture/image command-service
options passed by `GpuHostSystemAxi` to `GpuHostAxi`. The kernel additionally
requires the corresponding original command flag, discovered command transport,
unified commands and protected VM state. ABI 1 never probes GCR1. ABI 2 records
without the new flags retain existing image/texture behavior but cannot admit
target 5. An inconsistent combination cannot expose the new driver feature.

Every copied-view submission uses the same checks before preparing a bound
buffer view. The immutable shader snapshot uses a discovered-resource validator
entry that rejects reachable function 27 without the image version flag,
including a path past another path's early termination. Existing trusted native
validator entry points keep their original contracts; they do not discover a
device. The image owner still requires target 5 for the query, so this flag does
not add image-size operations for other targets. No GL/GLES capability or
cross-domain visibility guarantee follows from these transport flags.

## Buffer image-size instruction

Internal CUSTOM1 function 27, funct3 0 queries a buffer image's 32-bit count.
The vector `vs1` operand supplies each lane's unit index; `vs2` supplies the
existing target/kind/optional-format control. Both operands are direct inputs:
this instruction neither consumes load/store parameter tuples nor grants a
captured component-load result. Functions 12/13 remain quad derivatives and
all earlier texture/image/storage/counter/patch/atomic encodings are unchanged.
`driver/opengpu_image_size_instruction.h` encodes the new instruction without
asserting its availability on a device.

The image owner reads the actual GIU1 snapshot and retains its ordinary
identity, generation, stage, format and full byte-range checks. Query permission
accepts either a readable or writable view, including a write-only image without
a shader format qualifier. A supplied qualifier must still match. The checked
address result returns the count directly; the texel memory owner is never
offered a query. Inactive lanes retain their previous destination values, and
descriptor faults/cancellation follow the existing drain/retirement path.
Atomic consumers force ordinary read/write validation and cannot select query
permissions. Only buffer target 5 supports this new query; it does not add
image-size operations for targets 0–4 or enable any API capability.

## Validation

All four directed component cases, the host script suite and the existing
canonical ABI check pass. The affected C fixtures also pass AddressSanitizer
and UndefinedBehaviorSanitizer. A prepared arm64 Linux 7.2 module build passes
for the Linux copied-view path without a kernel rebuild.

The small component cases cover address arithmetic and 4GiB boundaries; actual
fetch/size results for all 30 read formats with scalar packet/captured vector
operations; split-line image load/store with guards; and R32UI checked atomic
requests. They exercise 65536-texel boundaries without traversing a large image,
retain mixed lane masks, reject malformed metadata before data I/O and recover
with healthy values. C tests cover the distinct 13-format image policy, NULL or
invalid buffer samplers, sparse/mixed tables and missing final-page recovery.
The Linux C ioctl harness checks all 30 texture formats, a sparse unit 31 mixed
with an ordinary sampler, copied image fields, exact derived counts, full byte
ranges and failed-submission recovery. Independent layout assertions preserve
the old ioctl encodings and 64/1128/80-byte structures. The actual binding helper
and MMU map/unmap algorithm cover a 65-page buffer binding with a trailing byte,
allocation failure and healthy reuse. The ioctl harness models GEM/VM services;
it is not GPU execution or proof that its mock `vm.enabled` walks page tables.

The version checks pass with Clang and Linux GCC, including each absent
transport/command/version/VM requirement, failed admission without allocation
leaks and healthy reuse. The C validator compares all 1024 resource/permission
combinations with the prior nine-way Linux profile selection on 12 instruction
programs. Existing behavior is retained when the new version is enabled;
disabling it only closes reachable image-size instructions. The three existing
command ABI suites check generated C/JSON consistency and both independently
enabled and disabled service combinations. This increment uses CPU checks and
a prepared arm64 module build, without a new GPU simulation.

Run these bounded C checks directly with `python3 scripts/test_driver.py`.
The new copied-view and binding/MMU tests also pass ASan/UBSan.

Four additional directed cases pass for buffer image size: the query owner,
both decoder availability profiles and the existing atomic-owner regression.
They check write-only/read-only views, inactive lanes, tuple-credit isolation,
full-range and descriptor faults, cancellation drain and fresh-generation reuse
without texel I/O. The atomic regression retains all three permission failures
with both ordinary and query-marked incoming requests.

Reproduce C/script checks with `bash scripts/test_scripts.sh`. Run the following
existing suites with the quoted unique case filters, one at a time:

```text
TextureImageAddressUnitSpec: address buffer ranges beyond image dimensions with full count products and exclusive 4GiB ends
TextureSampleExecuteUnitSpec: fetch and query all buffer formats beyond image extents while rejecting filtered access and invalid ranges
ShaderImageResourceAccessSpec: load and store a buffer texel above the image width limit and retain faults through healthy reuse
ShaderImageAtomicResourceAccessSpec: drain descriptor and atomic owners with permission checks and fault precedence
ShaderImageExecuteUnitSpec: query immutable buffer image sizes without data traffic or captured tuple credits
```

Use the following command, substituting one suite and case row:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -J-XX:+ExitOnOutOfMemoryError \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 -Dopengpu.simCcache=off \
  'Test/testOnly opengpu.graphics.SUITE -- -z "CASE"'
```

These are address/resource/texture-service components; no CU, whole-host,
rendering, timing or full-capacity qualification is claimed.

Run `opengpu.core.frontend.decode.ImageExecutionAvailabilityDecodeSpec` and
`opengpu.graphics.ShaderImageAtomicAccessSpec` as complete suites with the same
SBT options and no case filter. All checks use existing CI suites and lanes.

Canonical checks run with
`sbt 'Test/testOnly opengpu.graphics.ShaderImageAbiSpec'`. For an existing
prepared kernel and an independent copy of the driver sources, the module
command is `gmake -C "$KERNEL_BUILD" M="$MODULE_STAGE" ARCH=arm64
CROSS_COMPILE=aarch64-linux-gnu- -j1 gpu_drv.ko`.

Check transport discovery with
`sbt 'Test/testOnly opengpu.graphics.ShaderImageCommandAbiSpec opengpu.graphics.StorageCommandAbiSpec opengpu.graphics.ComputeUniformCommandAbiSpec'`.

## Gallium view state

The compute callbacks accept checked `PIPE_BUFFER` sampler/image views when the
corresponding versioned driver feature is present. Sampler views retain exactly
the 30 formats above, including their swizzles. Image views use the separate 13
formats; an RGBA8_SNORM image uses a same-size RGBA8 byte container with the
existing reinterpretation flag, without adding a sampler format. Range checks
use the original logical BO size, including the entire trailing byte range;
allocation padding cannot enlarge a view. No mip or sampler state is introduced.

All 32 sampler slots and four image slots retain their view/resource references.
Incoming references are acquired before replacing old slots, so a cross-slot
reorder cannot destroy its only remaining source. Transferred ownership,
trailing unbinds, invalid-state recovery and context destruction release the
corresponding references. The legacy fragment sampler rejects buffer views.

GL/GLES and compute capability queries stay unchanged. The four SSBO storage
windows are distinct from the additional image backing namespace below. View
callbacks alone do not establish shader execution or general visibility.

The actual callbacks, request constructors and modeled OS/GEM boundary pass a
Linux CPU fixture and ASan/UBSan/LeakSanitizer, alongside the existing storage
callback regression. With the pinned Mesa 22.3.6 headers/parser and DRM headers,
run `python3 scripts/test_gallium_buffer_views.py --build <fresh-directory>`;
repeat with `--sanitize` for the sanitizer checks. Set `OPENGPU_MESA_DIR` and
`OPENGPU_DRM_INCLUDE` when those shared dependencies are outside `depends/`.
This explicit fixture adds no routine GPU case or CI lane.

## Independent Linux image backings

Driver feature `COMPUTE_IMAGE_BACKINGS` (bit 30) permits `COMPUTE_STORAGE`
resource slots 39–42 only as image backings, beside all four SSBOs and 32
textures. It requires storage and image transports, unified commands, protected
VM state and an enabled context VM. Older flags cannot authorize these slots.
The four hardware SSBO bindings, all old slot aliases and addresses, and the
native GCR/descriptor ABIs stay unchanged. Slots 13–16 remain paired aliases of
35–38 and still reject simultaneous binding of both members.

Each new window retains 128 MiB plus an enclosing 4 KiB page, including the
complete BO range and any trailing bytes, without clamping to a texel count:

| Slot | Window base | Exclusive envelope end |
| --- | --- | --- |
| 39 | `0x87ffd000` | `0x8fffe000` |
| 40 | `0x8fffe000` | `0x97fff000` |
| 41 | `0x97fff000` | `0xa0000000` |
| 42 | `0xc7ffb000` | `0xcfffc000` |

Full SSBO/image mappings and the existing texture/execution peers use 313 L1
tables; the private VM allocates up to 384 on demand. The probe's temporary VM
uses heap storage. With the same ARM64 compiler and `-fstack-usage`, its measured
stack frame falls from 5696 to 304 bytes; no other existing frame grows.

`bash scripts/test_scripts.sh` covers the actual bind/prepare/scheduler bodies,
all full-range PTEs, allocation rollback, unmap and healthy reuse. Directed
Linux ASan/UBSan/LeakSanitizer checks also pass. The new combined C case retains
41 distinct reservation dependencies for four full SSBOs, four independent
buffer images and 32 textures, then tests fault, cancellation and retirement.
It selects the existing CEASE entry, uses no explicit input sync object and
does not execute shader reads/writes or hardware. Original GUM1 producer/sync
cases remain. These CPU checks add no GPU model or CI lane.
For directed sanitizer runs, set
`CC='cc -fsanitize=address,undefined -fno-omit-frame-pointer'` and
`ASAN_OPTIONS=detect_leaks=1:abort_on_error=1` when invoking
`scripts/test_storage_binding.py` or `scripts/test_compute_uniform_flow.py`.

## Gallium buffer compute lowering

The opt-in compute compiler accepts statically indexed TGSI buffer TXF/TXF_LZ,
TXQ, image LOAD/STORE and image RESQ. It lowers fetch/size through native
functions 3/5/6, image load/store through 8–11 and image size through 27. Staged
results preserve source/destination aliases. Size queries do not consume a
load/store tuple. Image declarations retain the separate 13-format policy;
unformatted image operations, coherent qualifiers and resource operations in
SIMT arms remain rejected. Signed, unsigned and float-bit payload types retain
their declaration types; this does not add general floating-point arithmetic.

Submission assembles all 32 textures, four SSBOs, four independent images and
13 constant slots through the existing copied-request interfaces. Features
28/29/30 and the original combined transports gate the required paths before
mapping changes. Whole logical BOs retain the 4 MiB texture and 128 MiB
SSBO/image Linux windows; a small view never conceals an oversized backing.
Partial binding or submission failure discards the private compute namespace,
without replaying a shader.

Within one serialized compute context, every accepted writable SSBO/image BO
is retained until publication, including ordinary SSBO-only producers whose
views are later replaced. The next dispatch or memory barrier issues fenced
physical invalidates for at most eight distinct GEM backings. Their data PTEs
are UNCACHED; invalidate does not write back dirty data. The kernel invalidate
job waits the BO reservation's prior writer. A failed accepted wait keeps its
original sync object, ranges and BO references. An unsignaled original fence
prevents namespace deletion and new dispatch; explicit recovery observes that
fence, completes publication and deletes the poisoned namespace before a later
request may create a fresh one. Namespace deletion alone is not retirement.
Final context destruction transfers unresolved work to a screen-owned list.
Later compute create, bind, launch and barrier calls must first observe the
original fence and complete publication; they cannot replay the producer.
At final screen teardown an explicit successful namespace deletion lets
userspace drop its ownership, while kernel job references keep VM/GEM backing
alive until free-job cleanup. This does not establish retirement or visibility.
If namespace deletion fails, a process-reachable terminal owner retains the
screen, FD and resources; later screen/compute entries retry safe recovery.
Closing the owned duplicate FD alone is never treated as retirement. These
owners follow the existing externally serialized winsys contract, without a
background worker or a general concurrent-context guarantee. Cross-screen FD
sharing, graphics coherence and the full GLES barrier contract remain open.

Large physical invalidates now scan the L2's resident tags instead of walking
every 64-byte address. At the claim edge the coordinator stops new demand and
atomic ingress, drains accepted queues, fills, stores and replies, then scans
each bank's full physical tags. Matching entries are dropped and their exact
L1 holders must acknowledge the original single-line probes before completion.
Every way receives a fresh synchronous tag read, including after a delayed ACK.
The scan generates no lower-memory data traffic or writeback. Completion can
remain backpressured while normal traffic resumes; waiting clients get a turn
before a consecutive range claims. Small spans retain the line protocol, with
unchanged zero/overflow status, 33-bit exclusive end and rounded byte count.
The integrated engines select the scan at `l2Sets * l2Ways` intersected lines.

Sixteen standalone component cases pass across two engine and two tiny cache
configurations. A 16-slot, two-bank behavioral-SRAM cache completes both a
128 MiB range and a full-address-space range within a 256-step bound after
claim, including driven holder delays; these are bounded component results, not
product-cycle measurements. Claim/drain coverage includes real out-of-order
lower replies, merged holders, held client/store/atomic replies, an earlier L1
probe, completion backpressure and fresh refills. Actual large-resource Linux
runtime, product timing and full GLES/H8 qualification remain open; no BO/view
or product cache capacity is reduced. With the compiled Test runtime classpath,
reproduce the range cases without compiling other Test sources:

```sh
java -Xmx1G -XX:ActiveProcessorCount=1 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -cp "$TEST_RUNTIME_CLASSPATH" org.scalatest.tools.Runner \
  -s opengpu.core.memory.LineInvalidateEngineSpec \
  -s opengpu.core.memory.SharedL2CacheSpec -z range
```

The `shared-l2-cache` physical profile retains the full 128 KiB cache
(512 sets, four ways, two banks), eight store entries, 16 outstanding
transactions and four CU interfaces with four transaction IDs each. Its native
SLVT/TC synthesis setup gate passes at 1000 ps with 25% input/output budgets:
core slack is +95.880 ps and I/O slack is +74.294 ps. All 64 data and eight tag
SRAM macros are mapped, with no skipped operators and complete constraint
coverage. The profile includes the range coordinator, normal and atomic paths,
and L1 probe/ACK ports; it contains no actual compute units. This does not
qualify routed timing, a full host or the Linux/GLES workload. Reproduce with:

```sh
sbt -J-Xmx2G -J-XX:ActiveProcessorCount=1 \
  'runMain opengpu.elaboration.EmitPpaRtl shared-l2-cache generated/l2-range/rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  generated/l2-range/rtl SharedL2Cache --stage synthesis \
  --synthesis-engine yosys --yosys-preserve-hierarchy \
  --clock clock --period-ps 1000 --io-delay .25 --timeout 7200 \
  --output generated/l2-range/synthesis
```

The Linux CPU fixture uses real Mesa 22.3.6 ESSL/NIR/TGSI, the production
compiler/callbacks/winsys, and extracted kernel ioctl/prepare/snapshot/scheduler
bodies. An independent word/record interpreter checks R32UI data, sizes and
guards, full 32/4/4 mappings, immutable GUR/GUB snapshots, sparse high bindings,
failure recovery, final-owner transfer and producer-to-consumer publication.
The context-lifetime fixture also compiles the actual namespace-delete and
context kref bodies: a successful delete after a bounded wait timeout retains
VM/bindings until the last job drops its reference. OS/GEM/MMU/MMIO replies
are modeled: these results are not a real page walk, GPU or GLES qualification.
Signed/float shaders additionally pass lowering and discovered word admission;
the existing component tests remain the evidence for all 30 sampler formats.

With the shared Mesa source/static build, DRM headers and the checked private
`nir_lower_io.c` described in [the uniform compiler checks](COMPUTE_UNIFORM_COMMANDS.md), run:

```sh
python3 scripts/test_gallium_buffer_flow.py --build <fresh-directory> \
  --mesa-nir-lower-io <checked-source> --artifacts <fresh-export-directory>
```

Add `--sanitize` for ASan/UBSan. The same built binary can check the final
ownership paths without initializing Mesa's compiler globals:

```sh
ASAN_OPTIONS=detect_leaks=1:abort_on_error=1 <fresh-directory>/cs_buffer_flow --lifecycle-only
python3 scripts/test_context_lifetime.py
```

The existing storage, uniform, control-flow and buffer-view CPU regressions
remain required. No new routine
GPU case or CI lane is added. General resource indexing, buffer image atomics,
graphics lowering, full visibility and composed native execution remain open;
compute and texture-buffer API capabilities stay off.

## Explicit Linux buffer program

`userspace/examples/gallium_buffer_glsl.c` is a headless qualification app;
GL/GLES capabilities remain off. Its ESSL310 sources pass through the real
Mesa22.3.6 frontend and bounded TGSI emitter. The app retains 32 independent
4MiB texture BOs, four 128MiB SSBO BOs, four independent 128MiB image BOs and
12 public 16KiB UBOs, plus an owned 4KiB default upload. Sampler and SSBO views
cover their complete BOs, including the temporary 4MiB texture31 SSBO alias.
Epoch-two image0 uses a checked 64-byte offset and BO-size-minus-64 subview;
its 128MiB allocation stays intact. A 128MiB BO cannot be shortened into the
existing 4MiB sampler window.

The ordinary SSBO producer publishes before buffer consumers. Three sampler
programs cover actual native slots 0..10, 11..21 and 22..31 while retaining the
512-word instruction limit. The image program uses all four LD/ST/size owners.
The 13-CB producer is a separate dispatch. A full-default combined program was
rejected by the actual Mesa1024-component uniform limit before TGSI emission;
its exact source is retained in `glsl_buffer/unsupported/`. Simultaneously active
13CB plus all buffer operations in one dispatch remains unproved. The second
epoch temporarily produces into the
complete 4MiB texture31 BO through an SSBO slot, then restores all four full
128MiB SSBO views; another producer BO becomes image0 through a checked changed
view. One replacement image is allocated while its old 128MiB owner stays alive.
A bounds-fault program and fresh-context path retain exact public fault identity
and refuse recovery if an accepted producer fence/publication remains unresolved.
Owned sampler-view references are dropped before their creating context is freed.

Each active program binds its own real frontend-local constant-buffer mapping.
The CPU preflight checks actual reflection, emitted words and every word in each
8192-byte prefix and 64-byte tail, including guards and the retained old image.
Its separate word interpreter is never linked into the app. The focused ASan/
LeakSanitizer test uses the app's actual destruction body and real view callbacks
with a modeled OS/GEM boundary. This is compiler/CPU evidence: DRM dispatch,
GPU trap/drain, visibility, actual PFN/PTE mappings and guest execution are unrun.
The six admitted programs contain 405, 501, 501, 501, 470 and 131 native words;
all pass the unchanged 512-word limit and independent decoding. Eleven serial
CPU segments check 82,560 or 84,624 prefix/tail/guard words each. Instrumented
app/frontend preflight passes; the focused lifecycle check passes two contexts
and 64 owned view releases with leak detection enabled. Its archived old-body
negative control reproduces the context use-after-free. The runtime builder
copies the ELF interpreter with executable permissions.
The app uses representative R32UI operations; it does not qualify general GLSL,
all 30 sampler formats, all image formats or image atomics. E32-04o stays Partial.

A future guest needs a new checked 3GiB DTB, RAM `[0x40000000,0x100000000)` and
at least the planned 1536MiB CMA region `[0x80000000,0xe0000000)`. Baseline data
are 1152MiB; one retained replacement raises the peak to 1280MiB **before**
UBOs, snapshots, private contexts/page tables and driver allocations. The CPU
VA calculation uses 297 L1 tables per fully mapped, page-aligned VM, below the
384-table limit. Asynchronous VM/job frees cannot be assumed immediate. These
are planning bounds, not successful allocations or physical-map qualification.
A future observer must join all public BO PFNs to actual PTEs and bus accesses;
public UBO-source PFNs differ from job-owned copied GUM1 snapshot PFNs.

Build into a new private directory using the exact checked-uniform Mesa source:

```sh
python3 scripts/build_gallium_compute_runtime.py --glsl-buffer \
  --mesa-source "$MESA" --mesa-build "$MESA_BUILD" \
  --mesa-nir-lower-io "$CHECKED_NIR_LOWER_IO" --drm-include "$DRM_INCLUDE" \
  --out "$NEW_OUTPUT" --cc gcc --cxx g++ --run-preflight --sanitize-preflight
```

This opt-in leaves the default TGSI and `--glsl-control-flow` modes unchanged.
Run in a matching AArch64 Linux compiler environment; the builder does not boot
a guest or open a DRM device. Existing CLI gates run with
`python3 scripts/test_arti_small_smoke_args.py`. The separate existing callback/
request/kernel-body regression uses the matching `OPENGPU_MESA_DIR`,
`OPENGPU_MESA_BUILD` and `OPENGPU_DRM_INCLUDE`:

```sh
python3 scripts/test_gallium_buffer_flow.py --build "$NEW_CPU_OUTPUT" \
  --mesa-nir-lower-io "$CHECKED_NIR_LOWER_IO" --sanitize
```
