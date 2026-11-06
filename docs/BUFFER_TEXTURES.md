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

Repeat with `--sanitize` for ASan/UBSan. The existing storage, uniform,
control-flow and buffer-view CPU regressions remain required. No new routine
GPU case or CI lane is added. General resource indexing, buffer image atomics,
graphics lowering, full visibility and composed native execution remain open;
compute and texture-buffer API capabilities stay off.
