# Typed image source submission and live-owner contract (P08)

The complete-host CPU boundary now forwards the original `COMPUTE_IMAGES` and
`COMPUTE_IMAGE_RESOURCES` requests to their production Linux handlers. It retains
the accepted image views beside their original GEM bindings, snapshots the real
GIU1 records and keeps the original backing and descriptor owners through
retirement and observed cleanup. The actual Mesa producer's ioctl boundary uses
these entry points; texture/image combined submissions keep a separate contract.

The capture guard grants image writes only when the original view is writable.
For buffer images it includes complete exposed texels, excluding leftover bytes
in the declared byte window. For 2D, array, cube and 3D views it includes each
texel row in the selected layer or all exposed layers, excluding row/slice/layer
padding and unselected layers. Packed rows and planes coalesce arithmetically.
Overlapping resource views retain the existing interval-union rule.

The OGCMP wire supports 4096 writable intervals. A fragmented view exceeding
that representation is rejected before a hardware kernel is offered and remains
cancellable. This does not change production admission, RTL image dimensions,
resource capacities or the 2-CU/640×480/60-FPS product target.

## Gallium typed storage and load/store lowering

The next source phase adds private, image-only linear allocations for the
thirteen native image formats and 2D, 2D-array, cube and 3D targets. Checked
mip layouts retain original storage formats, 64-byte row alignment and explicit
plane pitches. The existing 128-MiB backing-window bound applies to the complete
allocation. Transfers map the selected mip/plane and retain the resource through
unmap; launch views keep the original BO, offsets, permissions and exposed layer
range. A private compiler target sidecar distinguishes a one-plane layered
array/volume from a 2D view of one selected plane. The public compute-program
layout and legacy emitter entry points remain unchanged.

Native image load/store lowering carries target, format and XYZ operands. The
Mesa frontend now recovers API image units through the actual program's
`ImageUnits` table after sampler lowering, preserving sparse bindings such as
read-only unit 3 and write-only unit 1. Buffer sampler admission also checks the
existing target-5 format policy rather than accepting every mapped format ID.

ASan/UBSan CPU checks pass 65 target/format TGSI load/store programs, including
literal coordinate/control checks and mismatched-target rejection; 52 actual
Gallium mip/plane transfer and reference-lifetime cases; and the existing buffer
view tests. The resource-shape fixture passes 52 typed layouts plus the legacy
allocation paths, partial array views and single-plane array/volume views. A
separate real Mesa ESSL→NIR→TGSI build compiles all 65 target/format programs and
passes reflection and the production native-word validator. Existing shared
own-stripe, atomic, address/SIMT, 16-KiB and symbolic-register checks also pass.
These checks execute no image shader on a GPU or DUT.

This phase accepts only `PIPE_BIND_SHADER_IMAGE` for the new nonbuffer layouts.
Combined sampler/render bindings, imported/exported typed storage and nonbuffer
`imageSize` remain unsupported by this path. Existing render
layouts and driver/RTL capacities are unchanged. Real source-bound image
execution and its boundary/fault/cancel/reset oracles remain P08 work.

## Integer image-atomic compiler admission

The compiler lowers exchange, add, XOR, OR, AND, signed/unsigned min/max and
compare-and-swap for R32I/R32UI images on buffer, 2D, array, cube and 3D targets.
It uses the existing native function 23 and image-parameter tuples; no hardware
encoding or supported capacity changes. XYZ coordinates are captured before
staging values, CAS keeps the original compare/replacement order, and the
returned old value uses the normal scalar destination path. Writable image
declarations, matching target/format and discovered image-atomic transport
support are required. Unsupported memory qualifiers and control-flow atomics
are rejected.

An image-unit atomic mask joins the private target sidecar, outside the stable
compute-program layout. Compute-state creation checks the discovered native
capability before accepting an atomic program. Successful straight-line and
control-flow emission initializes this metadata; rejected emission leaves the
caller outputs unchanged. Legacy emitter entry points retain identical program
bytes.

ASan/UBSan checks pass 80 original ESSL programs through the actual Mesa
ESSL→NIR→TGSI compiler, reflection and production native-word admission. Another
160 literal TGSI cases cover targets, signedness, all eight operations and
coordinate/destination aliasing, including XYZ/control/value/CAS operands,
negative metadata and feature gates. The 65 load/store, 52 mapping/lifetime,
control-flow metadata and shared-memory regression checks pass, as do the
60 focused Python tests. These are CPU compiler/callback checks; no image atomic
shader executes on a DUT and no execution qualification is created.

Image-atomic programs now use the existing signed-five-bit `VMV.V.I` encoding
for small constants. A combined original 2D RMW program previously needed
534 native words and failed the fixed 512-word admission bound. Compact
constants let the combined target matrix compile within that bound, including
the 509-word 3D program. The public program layout, register limits and native
encoding are unchanged; programs without image atomics retain their previous
word stream. Operand checks include 15/16 and -16/-17 literal boundaries.

## R32F exchange execution and compiler admission

The image atomic access unit accepts R32F only for exchange, using the existing
32-bit shared-L2 swap operation and returning the unmodified old word. The
format qualifier, read/write permissions, address checks and offered-request
drain remain required. No floating-point arithmetic or native encoding is added.
R32F exchange is required by
[OES_shader_image_atomic](https://registry.khronos.org/OpenGL/extensions/OES/OES_shader_image_atomic.txt);
its implementation alone does not establish extension conformance or close P08.

GCR1 bit 21 and driver feature bit 43 report this service separately from
integer image atomics. The private compiler sidecar records each R32F exchange
unit, and compute-state creation requires both discovered image-atomic and
float-exchange support. Float immediates are opaque words and are admitted only
in programs containing an R32F image exchange. Other float atomic operations
remain rejected. The stable compute-program layout is unchanged. Existing
96-, 104- and sample 112-word owner contracts and their discovery flags stay frozen.
The separate `two-cu-vga60-owner-v2-image-float-stage` factory reports `0x3fffff`
with 112 words. All other discovery words, native/compute services and physical
capacities match the complete sample owner.

The focused RTL checks pass. They cover signed-zero,
subnormal, infinity and NaN-payload words, permissions, unsupported operations,
cancellation, held requests, old-value routing and a competing atomic through
the actual shared L2, including untouched line bytes. Discovery tests check
the explicit flag and preserve the existing owner quantities and contracts.
The real driver feature-query fixture passes 128 capability combinations.
The generated GCR1 header and ABI JSON pass five tests in four ABI suites.
ASan/UBSan CPU checks pass 85 original Mesa programs (80 integer and five R32F
exchange), 160 integer and 20 float TGSI target/alias cases, four immediate
boundaries, 65 load/store programs and 52 mapping/lifetime cases. These compiler
checks execute no shader on a DUT.

Fresh full-capacity R32F factory/model qualification, original source-bound
atomic results, boundary, permission, fault/cancel/reset/drain and healthy-reuse
acceptance remain required. Reproduce the focused RTL checks with Java 21,
the normal FIRRTL/Verilator toolchain and assertions enabled:

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 \
  -Dopengpu.simJobs=4 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.ShaderImageAtomicAccessSpec opengpu.graphics.ShaderImageAtomicL2Spec opengpu.system.ImageAtomicDiscoveryAxiSpec opengpu.system.NativeAlignmentHostProfileSpec opengpu.system.NativeOwnerHostProfileSpec'
python3 -B scripts/test_compute_feature_query.py
sbt 'testOnly opengpu.graphics.ShaderImageCommandAbiSpec opengpu.graphics.StorageCommandAbiSpec opengpu.graphics.ComputeUniformCommandAbiSpec opengpu.graphics.NativeAdvancedBlendAbiSpec'
```

The Mesa and callback reproduction commands below include the float cases.

## Complete float-factory source and CPU binding

Seven Scala profile checks and both actual Scala descriptions pass the strict
Python manifest guard. The float factory preserves two CUs, sixteen lanes,
eight warps, 16-KiB shared memory per CU, 128-KiB L2, four native engines,
65536 paged entries, 512-KiB payload storage and the 2048-square render capacity;
the product frame stays 640×480. Its complete model must be freshly generated
with assertions. See [the executor guide](COMPLETE_OWNER_COMPUTE_EXECUTOR.md)
for preparation, native compilation and the three original MMIO probes.

Each executor has a fixed float-stage wrapper. Qualifications, probes and
READY validation bind the selected profile explicitly; the default sample
profile cannot authorize float-stage data. Native GSS discovery accepts only
the two exact sample flag patterns. Unknown flags, changed quantities,
cross-factory captures/receipts and rehashed model/wrapper substitutions remain
rejected. All 87 focused Python/CPU checks pass, including five canonical C++
factory variants and 208 typed-view transport cases on each 112-word profile.

All 100 original Mesa image recipes and eleven counter recipes pass fresh
ASan/UBSan CPU captures under the explicitly CPU-only float-stage profile,
the source-selected canonical C++ parser, strict qualified READY and rejection
of initial uploads as GPU results. The image checks include 11729 rejections,
including a wrong qualified owner for every case. All 8102 external and 169
producer/checker inputs remain unchanged. Ended captures, builds and containers
are removed. These CPU checks execute no shader on a DUT. The separate actual
atomic execution result below does not waive boundary/fault/cancel/reset/drain
acceptance.

The complete float factory passes fresh full-capacity RTL emission with inline
assertions, hierarchical Verilator generation and native compilation with eight
jobs under the 24-GiB build guard. All four original source-selected executors
compile and link. Actual read-only MMIO probes pass for native sample, ordinary
sample graphics and compute: each reads all 112 discovery words, with five,
five and ten auxiliary registers respectively, one reset and drained idle state.
Source/model/tool/object identities pass again after rebasing onto the merged
backend change. These are discovery probes; the actual atomic result below and
the remaining P08 lifecycle gates have separate execution requirements.

For the CPU capture reproduction below, substitute `IMAGE_FLOAT_PROFILE`,
`IMAGE_FLOAT_STAGE` and `IMAGE_FLOAT_WORDS` from `owner_resource_contract` in
the labeled CPU profile fixture, compile the canonical parser with
`-DOPENGPU_OWNER_IMAGE_FLOAT_STAGE=1`, and select the expected owner explicitly:

```sh
python3 -B scripts/test_compute_image_source.py --captures "$CPU_CAPTURE/sessions" \
  --owner-profile two-cu-vga60-owner-v2-image-float-stage
```

## Original integer-atomic source and independent result contract

Ten original ESSL recipes cover buffer, 2D, array, cube and 3D with R32I/R32UI.
Each invocation owns a separate texel and performs exchange, add, XOR, OR,
AND, signed/unsigned min/max, another exchange and matching/mismatching CAS.
Sparse SSBO binding 2 records all ten returned old values. An image memory
barrier precedes the final image load and copy to the separate destination.

These recipes reuse the original offset/mip/pitch/layer allocations. API image
unit 3 is read-write and unit 1 stays write-only. CPU READY checks reconstruct
both exact writable views, excluding padding, unselected planes and partial
buffer texels. The original load/store cases keep their read-only source grant.
The independent oracle computes integer wrap, signed order and both CAS paths,
checks all 80 old values and eight final texels in each source/destination, then
checks every other physical byte. It does not interpret native instructions or
synthesize returned GPU memory. The CPU producer also verifies all ten native
atomic operations and their image-unit metadata before the real launch call.

The float-stage CPU matrix above covers these ten integer recipes alongside
the original load/store recipes and five R32F recipes. The separate complete-host
run below establishes these atomic recipes' shader execution. Same-texel
contention, other view combinations and lifecycle acceptance retain their own
requirements. Qualifications must be freshly bound to changed compiler,
producer and checker sources before execution; P08 stays open.

## Original R32F exchange sequences and bounded register reuse

Five original ESSL recipes cover buffer, 2D, array, cube and 3D images. Each
invocation performs five consecutive float exchanges, records every old raw
word through `floatBitsToUint`, then uses an image barrier and copies the final
texel into a separate write-only image. Operands cover positive/negative zero,
finite encodings and both infinities without floating arithmetic. The independent
oracle checks forty old words, all eight final texels in each image and every
other physical byte. Only a source-qualified float-stage executor can authorize
these cases; the original sample-stage identity remains insufficient.

The array recipe exposed cumulative TEMP allocation beyond the twenty physical
register slots despite a smaller live set. Straight-line float-exchange programs
now release components after their last conservative source use, after the
whole instruction consumes its inputs. Programs without float exchange retain
their existing allocation. The physical register capacity and 512-word shader
limit are unchanged. A regression consumes 24 components while preserving live
XYZ/value operands through an aliased exchange destination. It passes ASan/UBSan
alongside 160 integer and twenty raw float target/alias programs, four immediate
boundaries, 65 load/store programs and 52 mappings. All 85 original single-atomic
Mesa programs also pass. Twenty-seven focused image/controller/oracle/qualification
checks pass with the coordinate-boundary additions below.

## Actual complete float-host atomic execution (P08 partial)

All fifteen original atomic recipes pass actual execution through Mesa,
production ioctls and the complete 112-word float-stage Verilator DUT: ten
R32I/R32UI cases and five R32F cases across buffer, 2D, array, cube and 3D.
The execution source tree exactly matches merged PR #870
(`d496a675522ac62098386f0e5b355104be195154`), including the bounded TEMP reuse;
the complete model uses the full-capacity assertion-enabled float factory
delivered in PR #869. No source or model change occurs during this matrix.

Every integer case checks eighty returned old words and both images' final
texels. Every R32F case checks forty returned raw old words and both images'
final texels. The independent oracle also checks all physical bytes outside
the exact writable views. Original Linux IRQ/drain/POP, held/retired receipts,
actual PTE revocation, TLB/context cleanup ACK and final source checks pass in
every case. Each case uses its own fresh DUT reset and original uploaded RAM;
this run establishes neither cross-command coherence nor same-DUT reuse.

The fifteen coordinate-boundary recipes and actual image permission/source
fault, cancellation/reset/drain and healthy reuse remain pending. This result
does not close P08, establish complete resource combinations, or qualify
product clock/FPS. The formal accepted count remains 7/40.

After preparing and freshly source-qualifying the complete float-stage compute
executor as described in the executor guide, reproduce this matrix with the
original pinned producer image. Substitute the selected owner build directory;
changed sources require fresh executor qualification:

```sh
owner_dir="$PWD/.artifacts/p08-float-owner"
for target in buffer 2d array cube 3d; do
  for format in r32ui r32i r32f; do
    env -u SDKROOT -u CPATH PYTHONDONTWRITEBYTECODE=1 \
      /usr/bin/python3 -B scripts/run_owner_bounded.py \
      --memory-mib 4096 --timeout-seconds 900 -- \
      /usr/bin/python3 -B scripts/qualify_compute_owner.py \
      --executor "$owner_dir/executors/compute/executor" \
      --executor-qualification "$owner_dir/executors/compute/qualification.json" \
      --executor-checker "$PWD/scripts/prepare_owner_executor.py" \
      --executor-checker-python /usr/bin/python3 \
      --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
      --case "image-atomic-$target-$format" --timeout 600 || exit 1
  done
done
```

Each qualifier removes its private producer build, captures, container and
returned memory when verification ends. Keep the shared complete model only
while another explicitly scoped qualification needs it.

## Original coordinate-boundary recipes

Fifteen additional recipes cover buffer, 2D, array, cube and 3D images: five
R32UI load/store programs and ten R32UI/R32F exchange programs. Each invocation
accesses an upper coordinate and that coordinate minus one. Across eight
invocations only the first and last exposed texels are valid, once each;
fourteen coordinates include negative values and exact dimension/layer limits.
Valid accesses never alias across lanes or workgroups. The original 85 recipe
identities and ESSL bytes remain unchanged.

The independent oracle expects four zero channels for invalid loads and zero
old words for invalid atomics, with no invalid store/atomic writes. It checks
both valid endpoints, exact exchange words, copied destination texels and all
other physical bytes. This distinguishes valid R32UI's default alpha of one
from an invalid load's all-zero result. Initial-memory unit checks cover the
complete expected ranges and holes without constructing returned GPU memory.

The recipes retain sparse API bindings, original mip/pitch/layer grants and
the buffer view's three incomplete trailing bytes. Both volume views expose
the same two selected mip planes. Only R32F exchange cases require the distinct
qualified float factory. Register and shader-word limits are unchanged. The
100-image CPU matrix above includes all fifteen recipes; it establishes
original compilation, production admission and strict input/oracle binding,
with no DUT shader execution. Their live execution and the separate
fault/cancel/reset/drain/reuse gates remain required.

```sh
PYTHONPATH=scripts python3 -B -m unittest test_compute_image_source \
  test_qualify_compute_owner test_compute_owner_controller test_compute_owner_oracle
PYTHONPATH=scripts python3 -B -c \
  'from compute_image_cases import IMAGE_BOUNDARY_CASES; print(*IMAGE_BOUNDARY_CASES, sep="\n")'
```

Use each listed case with the guarded live command below and a freshly bound
float-factory compute executor. These are bounded texel accesses, not rendered
frames; supported hardware dimensions and the 640×480 product target remain
unchanged.

## Failed execution receipts and original Linux cleanup

The private CPU boundary has separate `compute_host_drain_failed`,
`compute_host_observe_failed_retired` and `compute_host_finish_failed_observed`
entry points. They accept only an exact opcode-zero execution-failed completion
with its original command ID, zero kernel byte telemetry and held IRQ. The
original Linux IRQ/drain path must produce an EIO hardware fence and a matching
valid fault snapshot containing only the completion-error flag. The scheduler
fence also retains EIO. Success entry points reject failed receipts, and failure
entry points reject success, altered IDs/opcodes/status/telemetry and undrained
retirement.

After independent bytes/guards authorize cleanup, failure cleanup explicitly
consumes the real context quiesce's one-time EIO report before revoking mappings.
It drops only the context's fence reference. Hardware, scheduler and reservation
fences retain their error; a cleared error is rejected. Original MMU unmap,
shootdown and cleanup ACK requirements remain mandatory. Bad read/write cleanup
ACKs still quarantine reachable job/image/context allocations.

Separate `OGCFH001`/`OGCFR001` wire helpers carry failed held/retired observations.
They cannot be passed through the ordinary success schema. All five original
factory variants pass guarded ASan/UBSan CPU driver/MMU/IRQ/cleanup and canonical
capture parsing. Each of the two complete 112-word profiles passes 208 ordinary
and 208 failed image transport cases, covering thirteen formats and eight view
layouts in read/write modes. Fourteen protocol/controller/oracle checks pass,
including exact failed wire words and cross-path rejection. Temporary outputs
are removed at terminal completion.

```sh
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_compute_host.py \
  --image-float-stage --canonical-root "$PWD"
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_compute_host.py \
  --sample-stage --canonical-root "$PWD"
PYTHONPATH=scripts python3 -B -m unittest test_compute_owner_protocol \
  test_compute_owner_controller test_compute_owner_oracle
```

These are CPU transport and schema checks. The ordinary compute executor and
Mesa producer still use success receipts; a source-qualified native failure
mode, physical fault injection, live failure handoff and same-DUT healthy reuse
remain required. Cancel/reset execution also remains pending. Freshly bind any
execution qualification to the changed boundary/protocol sources; this source
phase does not close P08.

## Original load/store source and independent result contract

The live producer now accepts 70 original ESSL recipes: thirteen formats across
buffer/2D/array/cube/3D, two single-plane layered array/volume cases and three
unlayered 2D views of selected array/cube/volume planes. Each launches two groups
of four invocations. Read-only API unit 3 and write-only unit 1 retain their
actual Mesa reflection order; sparse SSBO binding 2 observes all four loaded
components. The shader copies the loaded texel to a different image coordinate.
CPU uploads only initialize original input and guard allocations.

The original load/store-only source snapshot passes all seventy cases through
the complete 112-word sample DUT. Actual returned bytes, all physical guards,
held/retired source checks and observed context/PTE cleanup pass independently
for each case. This run precedes integer atomics and the float-stage factory;
it does not establish their shader execution or lifecycle acceptance.

Nonbuffer resources use mip 1 of an odd-width allocation, explicit row/plane
pitches and selected/layered ranges. Buffer views start at byte 48 and include
three incomplete trailing bytes. Input patterns distinguish adjacent rows and
planes, including normalized formats. Accepted production ioctl views, original
GEM identities and the actual private GIU1/GSM1 snapshots supply READY metadata.
The common host producer always links its real Mesa atomic-buffer object, since
its runtime-selected counter branch remains reachable for noncounter cases.

Before execution, the independent reader requires the exact full112 discovery,
one final kernel after publication invalidates, original sparse bindings and
format/mip/pitch/layer/access fields, literal GIU1/GSM1 records, original Sv32
backings/permissions, a separate empty inactive context and exact writable
intervals. Read-only images, row/plane padding, unselected layers and incomplete
buffer texels receive no write grant. The controller binds the specific image
case and freezes all three READY inputs through result checks and cleanup.
The qualifier uses the original compiler-input/MMD/link guard, a read-only
512-MiB Linux container with one CPU and the exact final112 executor profile.

The returned-byte oracle derives the eight loaded RGBA values from original
source texels and format rules, checks each destination texel and all untouched
physical bytes, and rejects initial uploads as results. Half/float bits, signed
extension, scalar default components, normalized endpoints and SNORM -128→-1
loading/-127 storage are explicit. It neither executes native words nor
synthesizes returned GPU memory.

The original 70 load/store recipes are included in the CPU matrix above. The
rejection checks include view/descriptor/permission/backing mutations, widened
write intervals, read-only grants, inactive-context authority and mismatched
qualified cases. All source patterns distinguish adjacent row/plane reads.
Each original-source matrix uses a fresh 22-TU/22-MMD build. CPU captures create
no execution qualification.

## CPU transport result and limits

The full112 CPU profile passes 208 source-view cases with ASan/UBSan and the
independent canonical C++ capture parser: thirteen image formats, eight
buffer/2D/array/cube/3D layouts, and read-only/read-write permissions. The matrix
uses all four image units and dedicated backing slots, nonzero levels/offsets,
split cache-line buffer views, nonzero pitches and selected/layered bindings.
An independent byte map checks the entire 8192-byte backing for each view.
SNORM buffer images use a valid by-size view over RGBA8 backing; unsupported
SNORM texture-buffer backing remains rejected.

Invalid source fields fail production admission. An unresolved backing writer
fence prevents execution without offering a kernel. Packed and over-budget
layouts check bounded planning and cancellation. Four bad cleanup-ACK cases
retain the reachable original image and private descriptor after public-handle
closure. The existing storage/uniform/counter transport and cleanup checks pass.

These are CPU OS/MMIO transport fixtures, including CPU completion/cleanup peers.
They execute no shader and create no DUT qualification. The separate seventy
load/store and fifteen atomic complete-host runs above establish only their
stated source snapshots and scopes. P08 remains incomplete: actual boundaries,
permission/source faults, cancellation/reset/drain and healthy reuse remain open. Complete resource
combinations, cross-command coherence and product clock/FPS keep their own gates.
Existing executor qualifications cannot be relabeled for the changed source.

Reproduce the scoped CPU checks; their temporary builds and captures are removed
when each command ends:

```sh
env -u SDKROOT -u CPATH OPENGPU_TEST_SANITIZERS=1 PYTHONDONTWRITEBYTECODE=1 \
  python3 -B scripts/test_compute_host.py --sample-stage --canonical-root .
env -u SDKROOT -u CPATH OPENGPU_TEST_SANITIZERS=1 PYTHONDONTWRITEBYTECODE=1 \
  python3 -B scripts/test_compute_host.py --canonical-root .
python3 -B scripts/test_compute_counter_source.py
python3 -B scripts/test_prepare_owner_executor.py
python3 -B scripts/test_qualify_compute_owner.py
python3 -B scripts/test_compute_owner_oracle.py
python3 -B scripts/test_compute_owner_controller.py
python3 -B scripts/test_prepare_owner_verilator_executor.py
```

For the Mesa regression, use the labeled CPU profile, checked NTT and immutable
Linux container setup in [the compute capture reproduction](COMPLETE_OWNER_COMPUTE_EXECUTOR.md).
Inside that container, select the original eleven-case matrix explicitly:

```sh
python3 -B scripts/test_gallium_compute_host.py --sanitize \
  --build "$CPU_CAPTURE/build" --session "$CPU_CAPTURE/sessions" \
  --profile-wire "$CPU_CAPTURE/profile.bin" \
  --mesa-shared-ntt "$CPU_CAPTURE/checked-ntt/src/gallium/auxiliary/nir/nir_to_tgsi.c" \
  --case counter-increment --capture-only --capture-counter-matrix
```

The compiler input guard is `compute_owner_inputs.py --bound-inputs INPUTS.json`,
using the current `snapshot` and source hashes rather than an executor
qualification. Run the canonical parser and strict counter READY checker for
each `sessions/COUNTER_CASES` capture. CPU capture records cannot qualify a DUT.

For the original typed-image CPU matrix (70 load/store and ten integer-atomic
recipes), select a new build/session and replace
the case selection with the following flags on the same guarded command:

```sh
--case image-buffer-rgba32f --capture-only --capture-image-matrix
```

Then check every capture using the canonical C++ parser and run:

```sh
python3 -B scripts/test_compute_image_source.py --captures "$CPU_CAPTURE/sessions"
python3 -B scripts/test_compute_image_source.py
```

For later actual execution, freshly qualify the software executor against these
producer/checker sources, then use `qualify_compute_owner.py --case CASE` for
each name in `compute_image_cases.IMAGE_CASES`. The existing executor/Mesa/tool
arguments and complete-host cleanup protocol are unchanged. Matrix capture flags
require `--capture-only`; they cannot launch live qualification. This document
records no typed-image DUT result.

For the typed compiler and callbacks, use the same pinned Mesa/DRM container
environment and new private output directories. Remove those directories after
their checks have ended:

```sh
python3 -B scripts/test_gallium_typed_images.py --sanitize \
  --build "$CPU_CAPTURE/typed-compiler"
python3 -B scripts/test_gallium_image_atomics.py --sanitize \
  --build "$CPU_CAPTURE/image-atomic-compiler"
python3 -B scripts/test_gallium_buffer_views.py --sanitize \
  --build "$CPU_CAPTURE/typed-callbacks"
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_gallium_shared_own_stripe.py
python3 -B scripts/test_gallium_resource_shapes.py
```
