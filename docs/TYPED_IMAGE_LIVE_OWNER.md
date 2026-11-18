# Typed image source submission and live-owner contract (P08)

The P08 acceptance set passes: seventy original load/store recipes on the
complete sample host, fifteen atomic and fifteen coordinate-boundary recipes
on the complete float host, and the buffer R32F fault, permission-denial and
delayed-R/B cancellation pairs with actual cleanup and healthy reuse.
Each result below retains its own exact execution source and model scope.
[PR #880](https://github.com/OpenGPGPU/OpenGPU/pull/880) closes P08 after
protected merge, taking the alignment count to 8/40. Complete resource
combinations, cross-command coherence, full compiler/API closure and product
clock/FPS retain their separate acceptance gates.

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

Gallium provides private, image-only linear allocations for the
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
layouts and driver/RTL capacities are unchanged. The actual execution results
and separately qualified P08 lifecycle results are documented below.

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

Full-capacity R32F factory/model qualification, original source-bound atomic
results, boundary, permission, fault/cancel/reset/drain and healthy-reuse
results are documented separately below. Reproduce the focused RTL checks
with Java 21, the normal FIRRTL/Verilator toolchain and assertions enabled:

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
the P08 lifecycle results have separate execution qualifications.

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
producer and checker sources before execution.

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

## Actual complete float-host atomic execution

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

Coordinate-boundary, source-read fault, permission-denial and continuous
buffer recovery and cancellation/reset/drain results are scoped below. This
atomic matrix alone does not establish those lifecycle results, complete
resource combinations or product clock/FPS.

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
with no DUT shader execution. The separate fault/cancel/reset/drain/reuse gates
remain required.

All fifteen original coordinate-boundary recipes now pass actual execution
through Mesa, production Linux ioctls and the complete assertion-enabled float
DUT. The execution source tree exactly matches commit
`66848f6ba3b3a4e631d9c04ddb634867582be0cc`; source and model remain fixed during
the matrix. Every case passes the independent returned-value/final-image
oracle, including both valid endpoints, fourteen invalid accesses, zero invalid
load/atomic results and no invalid writes. All other physical bytes, held and
retired receipts, original IRQ/drain/POP, actual PTE/context cleanup and final
source checks pass under the 4-GiB/900-second per-case guard.

Each case uses a fresh execution DUT/reset and original RAM. These results do
not establish cross-command coherence, continuous recovery or product
clock/FPS. Every qualifier removes its private producer build, capture,
container and returned memory after verification.

```sh
PYTHONPATH=scripts python3 -B -m unittest test_compute_image_source \
  test_qualify_compute_owner test_compute_owner_controller test_compute_owner_oracle
PYTHONPATH=scripts python3 -B -c \
  'from compute_image_cases import IMAGE_BOUNDARY_CASES; print(*IMAGE_BOUNDARY_CASES, sep="\n")'
```

Use each listed case with the guarded live command in the atomic execution
section and a freshly bound float-factory compute executor. These are bounded
texel accesses, not rendered
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
Mesa producer use success receipts; the distinct source-fault execution path
below requires its own actual-DUT qualification. Continuous source-fault
recovery and cancellation/reset execution are qualified separately below.
Freshly bind execution qualifications to changed boundary/protocol sources.

## Native fault and retained-memory service primitives

The external AXI service can explicitly arm one aligned, fully backed physical
read interval. It returns SLVERR for reads in that interval while retaining the
original snapshotted data and RAM bytes. The plan cannot change until all
accepted read/write traffic drains. Disarming preserves cumulative physical
and injected fault counters; default service and writes are unchanged.

`Memory.append_disjoint` retains prior physical uploads and results when adding
another original owner's allocations. It rejects overlapping ranges, orphan
pages and sparse pages that shadow either owner's allocations, before changing
RAM. Failed validation leaves the original layout and bytes unchanged.

The same service can arm one address-bound R or B response hold while fully
drained. The aligned interval must be backed by original allocations. A hold
keeps its accepted transactions, read snapshots and error status; W data still
commits before its delayed B ACK. It hides selected VALID before exposure,
then releases only after observing a withheld, due response. It does not
require the master to assert READY before receiving VALID.
Release only opens the gate, so a VALID exposed under backpressure cannot be
withdrawn. Plan replacement/disarm requires complete R/B drain. Hold-cycle and
physical-error counters remain cumulative; default response timing, credits
and unselected traffic retain their existing behavior.

Twenty-three CPU AXI/RAM scenarios pass with ASan/UBSan, including stable
two-beat faults, explicit R/B release, W-before-AW, a full held read queue,
independent opposite-channel work, RV32 upper-bound addresses and healthy ID
reuse without clearing hold/fault history. Transactional rejected appends
preserve every original byte. All 34 native-product preparation/guard checks,
the five fixed compute drivers and native-owner transport guards pass; the
driver guards also use ASan/UBSan. These service tests execute no GPU shader; actual image fault/reuse
qualifications below retain their independent source binding. The actual
cancellation/reset/drain results also have their own qualification; they do
not follow from these service tests.

```sh
env -u SDKROOT -u CPATH PYTHONDONTWRITEBYTECODE=1 \
  python3 -B scripts/test_qualify_native_product_compiled.py
mkdir -p .artifacts
axi_dir="$(mktemp -d .artifacts/image-fault-axi.XXXXXX)"
trap 'rm -rf "$axi_dir"' EXIT
c++ -std=c++17 -O1 -Wall -Wextra -Werror -fsanitize=address,undefined \
  -fno-omit-frame-pointer scripts/test_native_frame_axi.cpp -o "$axi_dir/check"
"$axi_dir/check" "$axi_dir"
```

## Original Linux abort/reset CPU transport

The compute host boundary extracts the original `opengpu_hw_abort`, legacy
completion and unified reset-request functions directly from `opengpu_hw.c`,
with the original reset timeout definition from `opengpu_device.h`. A distinct
reset receipt carries no kernel completion or shader result. After a captured
kernel, real abort records `ABORTED|RESET_ISSUED` and signals its hardware fence
with `ECANCELED`; original GEM, descriptor and page-table ownership remains
pinned. Unowned completion DATA may remain stale and is never POPped.

At the original RESET write, the boundary exports the partial MMIO trace and
waits for an external `RESET_BUSY|VM_BUSY` observation, allowing only the
independent queue-capacity READY bit, before the original IRQ-enable tail
proceeds. Only a separate READY/reset-IRQ/external-drain ACK
authorizes original IRQ retirement. A subsequent observation of the retired
IRQ authorizes the scheduler's cancelled fence. Independent byte verification
must still authorize cleanup, whose original PTE/TLB/context ACK protocol is
unchanged. Rejected or absent observations retain the original allocations.
Actual Mesa producers contain neither CPU test peer.

ASan/UBSan CPU transport checks pass 208 cancelled image-view cases, alongside
the existing 208 success and 208 execution-failure cases and canonical capture
parser. Cancellation tests cover stale completion DATA, pending IRQ history,
wrong/early/duplicate ACKs, separate success/EIO/cancelled fences and lifetime
pins. Nineteen isolated malformed, stale, symlinked, missing, explicit-error or
timeout BUSY cases retain the original job/GEM/PTEs; four bad cleanup-ACK cases
also retain cancelled resources. Executor source guards bind the original
driver, extracted-function generator and recursive transport includes.
The complete C transport matrix also passes Linux GCC with strict warnings
and ASan/UBSan. Canonical capture parsing passes with Clang; an additional
container-mounted GCC parser run exceeded its 180-second bound and is not
counted as a parser pass.

These tests execute no shader. Native delayed-R/B replay, actual reset drain,
independent partial-cancel bytes and same-DUT healthy reuse have their separate
actual execution results below. Changed boundary sources require fresh
execution qualification before an actual-DUT claim.

```sh
env -u SDKROOT -u CPATH PYTHONDONTWRITEBYTECODE=1 OPENGPU_TEST_SANITIZERS=1 \
  python3 -B scripts/test_compute_host.py --image-float-stage --canonical-root .
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_prepare_owner_executor.py
CC=gcc CXX=g++ PYTHONDONTWRITEBYTECODE=1 OPENGPU_TEST_SANITIZERS=1 \
  python3 -B scripts/test_compute_host.py --image-float-stage
```

## Delayed R/B reset recovery protocol

`--image-reset-recovery read` or `write` selects two live original buffer R32F
producers: cancellation followed by a healthy job. Their original shaders,
image views and permissions are unchanged. The controller derives the held
response interval only from the original source GEM. The native executor
retains one complete float DUT, RAM, AXI agent and execution clock; the healthy
owner appends disjoint original allocations and reuses the original command ID.
The initial model reset and the one original unified-reset request are counted
separately. Physical/injected error and response-hold history remain cumulative.

Distinct held, BUSY, drained-ACK and retired-IRQ receipts replay the original
Linux abort/reset traces. The native parser requires the request ending at
RESET, the IRQ-enable tail and the original six-action no-POP IRQ retirement.
A SUBMIT while reset is BUSY must be rejected. Thirty-two extra clocks after
the drained ACK must preserve every RAM byte and external traffic count before
any new command. Independent cancellation bytes must pass before the controller
relays the ACK to the original IRQ handler; cleanup requires its own subsequent
authorization and actual PTE/context acknowledgements.
`STATUS.READY` reports staging-queue capacity and may remain set alongside
`RESET_BUSY|VM_BUSY`; it cannot authorize progress or an early drain ACK.

`GpuSystem` blocks new host dispatch and directly cancels CU image, storage,
uniform and related services, then waits for accepted work and shared L2 to
drain before resetting. An accepted atomic may update memory while its
cancelled vector commit is suppressed; the host fence remains `ECANCELED`.
Every cold source R response is held before exposure, so that cut permits only
each invocation's original source word or its first signed-zero exchange.
Output and destination bytes remain original. A B-held cut can occur after
earlier program steps; it permits only original complete exchange states and
independently expected old-value/destination words, requiring committed source
data. All code, arguments, descriptors, PTEs, padding and unused texels stay
guarded. Independent cleanup preserves every cancelled data byte and permits
only original valid PTE clears, with all private translations revoked. Every
old owner's cleaned byte stays guarded through the healthy round. ACK and
IRQ-retirement RAM/traffic checks remain strict and report their phase and
first offending byte on failure.

Fifty-one Python source/protocol/byte/lifetime checks and the five fixed C++ compute
drivers pass; the C++ guards use ASan/UBSan and verify reset JSON interoperability
and strict trace/plan rejection. The full float C matrix and canonical parser
also pass, including original cancelled IRQ traces with both pending and clean
IRQ histories. These remain CPU/source checks. Actual delayed-R/B reset/drain
and healthy-reuse results follow below.

```sh
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=scripts python3 -B -m unittest \
  test_compute_image_reset test_compute_owner_protocol test_compute_owner_controller \
  test_qualify_compute_owner test_compute_image_source
```

## Actual delayed R/B cancellation and healthy reuse

Both original buffer R32F pairs pass on the complete assertion-enabled float
DUT, with execution sources fixed at
`4a60abb30654a5b96aa4d5cc096e466f4626749e`. The RTL and full model remain
unchanged from the qualified float factory; source-only transport and checker
changes receive fresh executor qualification. The two directions use separate
initial DUTs. Within each pair, one DUT, RAM, AXI agent and cumulative clock
span cancellation and healthy execution, with command ID 4, one initial model
reset and one actual unified-reset request. No RAM replacement occurs.

| Delayed response | Held cycles at drain | Changed original source words | Cancellation clock | Pair elapsed | Peak aggregate RSS |
|---|---:|---:|---|---:|---:|
| R | 143 R / 0 B | 2 | 0..2453 | 319.040 s | 382746624 bytes |
| B | 0 R / 143 B | 2 | 0..2450 | 315.893 s | 372424704 bytes |

Both actual BUSY observations are 41 (`RESET_BUSY|VM_BUSY|READY`), with IRQ
delivery disabled and a rejected SUBMIT before release. The drained ACK is
status 1 with IRQ 3; original six-action IRQ retirement leaves IRQ 1 and the
pin deasserted. Both observe 32 further clocks with no late external traffic
or changed RAM. Physical read/write faults and injected read faults stay zero.
Independent per-cut bytes pass before ACK relay: R changes only two source
words to their first original exchange, leaving output/destination original;
B changes only permitted original exchange data. Every other original byte
remains guarded. The cancelled owner retains real Linux `ECANCELED` hardware
and scheduler fences through actual original PTE/context cleanup, whose strict
byte check permits only valid original PTE clears.

Each healthy owner then verifies forty raw old words, final source/destination
texels and every retained prior-owner byte. The clock continues from the
cancelled round; the B pair finishes at 12122. Both actual healthy cleanups,
independent cleanup-memory checks, final source/input checks and original
producer/executor exits pass. Each pair exits zero under the 4-GiB/900-second
guard, with proven process cleanup. Private producer builds, captures,
containers and returned RAM are removed; the ended software executor is also
removed after process, cross-worktree reference and symlink audits. The full
model is retained for the next indirect-compute qualification.

These are the selected original buffer R32F cancellation cuts. They do not
qualify every image recipe at every cut or establish general resource
competition, cross-domain coherence or product clock/FPS. The earlier matrices
keep their own exact source/model scopes; they are not relabeled as execution
of this source tree.

Prepare and freshly source-qualify the complete float executor as described in
the executor guide, then reproduce both directions serially:

```sh
owner_dir="$PWD/.artifacts/p08-float-owner"
for channel in read write; do
  env -u SDKROOT -u CPATH PYTHONDONTWRITEBYTECODE=1 \
    /usr/bin/python3 -B scripts/run_owner_bounded.py \
    --memory-mib 4096 --timeout-seconds 900 -- \
    /usr/bin/python3 -B scripts/qualify_compute_owner.py \
    --executor "$owner_dir/executors/compute/executor" \
    --executor-qualification "$owner_dir/executors/compute/qualification.json" \
    --executor-checker "$PWD/scripts/prepare_owner_executor.py" \
    --executor-checker-python /usr/bin/python3 \
    --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
    --case image-atomic-buffer-r32f --image-reset-recovery "$channel" \
    --timeout 600 || exit 1
done
```

## Original source-read failure execution path

The explicit qualifier option `--expect-image-source-fault` selects only the
original `image-atomic-buffer-r32f` recipe and the distinct qualified float
factory. CPU-capture and other recipe modes cannot select this path. After
strict original READY validation, the independent controller derives a physical
read-fault interval from the accepted source GEM allocation and freezes it
alongside the original memory, descriptor/recipe and MMIO capture.

The native executor requires actual injected AXI errors, exact failed opcode-0
completion, no physical writes and unchanged original RAM. It retains fault
counters through held/retired and real context cleanup. Distinct failed wire
messages reach the original Linux IRQ path; `SYNC_IOC_FILE_INFO` reads agreeing
actual hardware and scheduler fence status, including EIO. No fence error is
cleared to authorize cleanup. The independent all-byte failure oracle accepts
unchanged uploads only in this failed mode; ordinary healthy result checks
continue to reject those uploads.

Source/CPU checks pass 38 Python cases, all five fixed compute-driver variants,
the existing native-owner cleanup tests, and the float canonical Linux transport
with ASan/UBSan (208 healthy and 208 failed image cases plus bad-ACK retention).
These CPU checks do not establish actual-DUT execution by themselves.

The original buffer R32F recipe now passes actual source-qualified execution on
the complete assertion-enabled float DUT in both explicit source-fault and
ordinary healthy modes. The execution source tree matches commit
`66848f6ba3b3a4e631d9c04ddb634867582be0cc`. The fault run observes injected
physical read errors and failed kernel completion, carries actual EIO hardware
and scheduler fences through the original Linux IRQ path, verifies every
original physical byte unchanged, and completes actual PTE/context cleanup and
source postchecks. It exits zero in 153.115 seconds under a 4-GiB/900-second
guard. The ordinary healthy regression verifies forty old raw words, final
images, all other physical bytes and actual cleanup; it exits zero in 235.804
seconds under the same guard. Both remove their private producer builds,
captures, containers and returned RAM after verification.

Each of these standalone runs uses a separate fresh DUT reset and original
RAM. Continuous buffer recovery and coordinate-boundary results are scoped
separately in this document, as are permission denials and cancellation/reset/drain.
Cross-command coherence and complete resource combinations keep their own
acceptance gates.

After committing and freshly binding this source to the complete compute
executor, use the bounded qualifier command in the atomic execution section
with `--case image-atomic-buffer-r32f --expect-image-source-fault` for the actual
source-read fault. Reproduce the source/CPU checks with:

```sh
PYTHONPATH=scripts python3 -B -m unittest test_compute_image_source \
  test_compute_owner_controller test_compute_owner_protocol \
  test_qualify_compute_owner test_compute_owner_oracle
python3 -B scripts/test_compute_owner.py
python3 -B scripts/test_native_owner.py
env -u SDKROOT -u CPATH OPENGPU_TEST_SANITIZERS=1 PYTHONDONTWRITEBYTECODE=1 \
  python3 -B scripts/test_compute_host.py --image-float-stage --canonical-root "$PWD"
```

## Continuous source-fault recovery contract

The explicit `--image-source-recovery` option runs the same original buffer
R32F recipe twice: a source-read fault followed by a healthy job. One execution
DUT, initial reset, backing RAM, AXI agent and cycle counter span both jobs;
the command ID must match. Separate original Linux producer processes select
disjoint physical allocation bases through the real CPU MMU allocator. The
executor appends those original uploads without replacing the retained RAM.

The first job retains actual injected read-fault history and EIO fences through
its original cleanup. The second accepts that exact fault baseline and rejects
new errors. Resource probes after drain must preserve all cumulative external
traffic. Before authorizing each cleanup, an independent oracle checks actual
returned bytes; the healthy oracle includes every retained prior-owner byte.
After cleanup, another check permits only original valid PTE words to become
zero and requires every original private translation to be revoked. Both
source closures and original inputs remain checked across the pair.

All 44 Python protocol/oracle cases pass. All five fixed native variants and
the native-owner guards pass with ASan/UBSan, including rejection of mismatched
prior traffic and new external work during a reused resource probe. The
canonical float Linux transport also passes its 208 healthy and 208 failed
image cases and bad-ACK retention checks. These CPU checks do not establish
actual recovery.
Run the protocol suite above and reproduce the sanitizer guards with
`OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_compute_owner.py` and
`OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_owner.py`.
The actual original pair passes on the complete assertion-enabled float DUT
with the execution sources at `c22fb16da2723f22ded8ff8b9e518c10d4571c52`.
Both jobs retain command ID 4; the execution clock advances from 0 to 2337,
then from 2337 to 12009. The two actual injected read errors remain recorded
after healthy recovery, with no new physical errors or extra reset. The failed
job preserves original RAM and actual EIO fences through cleanup. The healthy
job verifies forty old raw words, final images and every retained prior-owner
byte. Both actual PTE/context cleanups, independent cleanup-memory checks and
final source postchecks pass.

The bounded pair exits zero in 290.734 seconds under a 4-GiB/900-second guard,
with peak aggregate resident memory 390234112 bytes and proven process cleanup.
Both private producer builds, captures, containers and returned RAM are removed
after verification. This establishes only the original buffer source-fault /
healthy pair; permission denials and cancellation/reset/drain are qualified
separately in this document.

After freshly binding the execution sources, reproduce the pair with the
bounded atomic qualifier command above, selecting
`--case image-atomic-buffer-r32f --image-source-recovery`.

## Image permission-negative DRM request contract

`--image-permission-recovery read` denies reads with a write-only source view;
`--image-permission-recovery write` denies writes with a read-only source view.
Each mode pairs the unchanged original buffer R32F ESSL program with a normal
read-write invocation on the same execution DUT, command ID and retained RAM.

Ordinary Gallium rejects shader/view permission mismatches before submission.
This dedicated negative therefore changes only the source-view access flags
in the original caller's DRM request copy, after normal Gallium admission and
before the production Linux image handler. Shader/reflection, GEM backing,
layout and all other request fields remain original. The Linux handler creates
the actual denied GIU1 descriptor; the independent READY check reconstructs
that accepted view under the explicit denied-access mode. Ordinary READY and
the opposite denial mode reject it. No physical error or PTE edit substitutes
for an image-permission denial.

The failed execution must produce an actual execution-failed completion and
original EIO fences with zero physical read/write errors, no physical writes
and every original RAM byte unchanged. Healthy reuse needs the exact retained
external-traffic baseline, including the zero-error history, and may change
only its own result views. Both original context cleanups and independent
PTE/all-byte checks remain required. The source-read fault mode continues to
require positive injected read-error counts.

All 47 Python protocol/oracle checks and all five fixed C++ variants pass,
with ASan/UBSan checks for failed/success receipt separation and exact
zero-error traffic retention. These CPU checks alone do not qualify a DUT.

Both original buffer R32F permission pairs pass actual execution on the
complete assertion-enabled float DUT with execution sources
`d64e54804cc6cfe8bc694f268ee8ead0205bc588`. Each denied request produces actual
failed completion and original EIO fences, zero physical errors/writes and
unchanged execution RAM. Its healthy original request verifies forty raw old
words, final images and all retained prior-owner bytes. Both actual context
cleanups, independent cleanup-memory checks and source postchecks pass.

Both directions retain command ID 4 and advance the same execution clock from
0 to 2283, then from 2283 to 11955, with no intermediate reset or RAM
replacement. Read-denied execution exits zero in 317.004 seconds with peak
aggregate resident memory 372408320 bytes; write-denied execution exits zero
in 299.875 seconds with peak 380616704 bytes. Each pair uses a 4-GiB/900-second
guard and proves process cleanup. Its private producer builds, captures,
containers and returned RAM are removed after verification. The two directions
use separate initial execution DUTs; they do not establish coherence across
the pairs or product clock/FPS. Cancellation/reset/drain has the separate
actual results above.

The existing source-read fault/recovery pair also passes on those same
execution sources: command ID 4 and clock intervals 0..2337 and 2337..12009,
with both actual injected read errors retained and no new physical errors in
healthy recovery. Returned bytes, all prior-owner guards, both original
context cleanups and source postchecks pass. It exits zero in 304.996 seconds
under the same 4-GiB/900-second guard, with peak aggregate resident memory
387940352 bytes and proven private process/data cleanup. Thus the permission
mode's zero-error acceptance does not weaken source-fault error requirements.

After freshly binding the execution sources, reproduce each pair with the
bounded atomic qualifier command above and `--case image-atomic-buffer-r32f`,
selecting `--image-permission-recovery read` or
`--image-permission-recovery write`.

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
stated source snapshots and scopes. Boundary and source-read fault results
also appear above, alongside the actual R/B cancellation/reset/drain and
healthy-reuse pairs. Continuous buffer source-fault and permission-denial
recovery are separately scoped above. Complete resource
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
require `--capture-only`; they cannot launch live qualification. CPU matrix
capture does not itself establish a typed-image DUT result.

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

## Same-texel atomic competition (P10)

All 55 original contending ESSL recipes pass Mesa/Gallium/production Linux
submission and actual execution on the complete assertion-enabled two-CU float
owner. Each recipe launches two four-invocation workgroups against one shared
original texel, with a separate destination texel for each invocation.

The fifty R32UI/R32I recipes cover buffer, 2D, array, cube and 3D views with
exchange/add, xor/or, and/min, max/CAS and CAS/CAS pairs. They cover all ten
integer operations, signed/unsigned ordering and invocation-specific CAS
winners. Each invocation retains its two source-ordered events. Five further
R32F recipes exchange distinct finite raw float encodings, one per invocation.

The independent oracle checks all **840 returned old words**, every final
shared texel and every invocation's destination store. The old values and
final word must admit a legal source-level serialization, including each
invocation's program order. Every other original physical byte remains
guarded. The READY contract also binds the actual Mesa words to the original
code snapshot and every staged private executable mapping, alongside the
exact original image views, descriptors, permissions and writable intervals.

All 55 held/retired receipts, IRQ/drain/POP and actual PTE/context/MMU cleanup
ACKs pass. Every native executor and original producer exits zero; final
compiler/library/source, model and executor checks pass. One guarded compiler
build serves the matrix. Each case has its own fresh DUT, original RAM upload
and reset; this is not continuous same-DUT or cross-command qualification.

The execution source is `2ade56eb7c7e3e4bcefc862547b63bfa693c7738`.
All 214 qualified software files match the PR #892 publication byte for byte.
All 1,960 original hardware preparation inputs remain exact in the execution
checkout. Publication differences are source-path relocation and reviewed
comments in `FragmentInterpolator.scala` and `RenderPipeline.scala`; their
hardware logic is unchanged. The original complete model and its bound native
objects are retained without rewriting model identities or reducing capacity.

The 95 focused CPU/oracle/controller/executor checks and all 55 real Mesa CPU
captures also pass. The latter independently check code/view/mapping identity
and reject untouched result uploads; the actual DUT run above supplies the
execution result. Reproduce against a matching complete qualified float owner,
freshly qualifying the software executor for these sources:

```sh
env -u SDKROOT -u CPATH PYTHONDONTWRITEBYTECODE=1 /usr/bin/python3 -B \
  scripts/prepare_owner_executor.py --backend verilator --stage build \
  --kind compute --owner-output "$OWNER_OUTPUT"
env -u SDKROOT -u CPATH PYTHONDONTWRITEBYTECODE=1 /usr/bin/python3 -B \
  scripts/qualify_compute_owner.py \
  --executor "$OWNER_OUTPUT/executors/compute/executor" \
  --executor-qualification "$OWNER_OUTPUT/executors/compute/qualification.json" \
  --executor-checker scripts/prepare_owner_executor.py \
  --executor-checker-python /usr/bin/python3 \
  --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
  --case image-atomics-contended --timeout 7200
```

The later mixed image/storage and raw RV32 global stages have their own
[results](MIXED_COMPUTE_ATOMICS.md) and
[results](GLOBAL_ATOMIC_TRANSLATION.md). Image committed-write errors and
cached-alias behavior remain P10 gates. Complete resource coexistence,
cross-domain coherence, a physical clock and FPS retain their separate gates.

## Committed image write errors and continuous reuse

The distinct `image-atomics-accepted-write-error` entry retains all 55 original
same-texel programs, targets, formats, operands and two four-invocation
workgroups. Each program has a committed first-write error followed by healthy
reuse: 110 phases require one complete assertion-enabled float DUT, retained
RAM, one initial reset and the original command ID. Fresh original GEMs use
disjoint physical namespaces; all previously cleaned RAM remains guarded.

The physical agent commits enabled W bytes before returning the delayed B
error. The image unit owns one lane's atomic request until its response; an
error ends that vector instruction before another failed-group lane commits.
The oracle enumerates the four legal first invocations of the failed group,
then checks the surviving group's source-ordered AMOs, exact old words, final
shared texel and each destination imageStore. Failed-group old slots and
destination texels remain unchanged. Native accounting requires nine target
attempts for each two-operation integer program and five for finite R32F
exchange, including writes for missed CAS operations.

The producer and input guard bind every original GLSL/code/view/mapping,
compiler/link dependency and all 110 ordered source/session/fault/allocator
arguments. Image write-failure receipts have a distinct option restricted to
these original programs. Each phase requires actual EIO or healthy success,
held/retired IRQ, PTE/context/MMU cleanup and external drain before reuse.

Seventy focused oracle/source/controller/qualifier checks and 29 native
transport/input-closure checks pass, alongside 21 legacy global/storage
recovery checks. CPU controls include all 10,560 failed-lane/successful-group
permutations, literal histories, source-order and failed-group negatives,
destination/old-slot/current/previous-RAM guards and cleanup refusal.
Actual complete-DUT qualification still must establish all 110 phases,
1,260 returned old words, real cleanup and final compiler/source/model checks.

```sh
env -u SDKROOT -u CPATH PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=scripts \
  /usr/bin/python3 -B -m unittest test_compute_image_accepted_recovery \
  test_qualify_compute_owner test_compute_counter_source test_compute_image_source
env -u SDKROOT -u CPATH PYTHONDONTWRITEBYTECODE=1 \
  /usr/bin/python3 -B scripts/prepare_owner_executor.py --backend verilator \
  --stage build --kind compute --owner-output "$OWNER_OUTPUT"
python3 -B scripts/qualify_compute_owner.py \
  --executor "$OWNER_OUTPUT/executors/compute/executor" \
  --executor-qualification "$OWNER_OUTPUT/executors/compute/qualification.json" \
  --executor-checker scripts/prepare_owner_executor.py \
  --executor-checker-python "$QUALIFICATION_PYTHON" \
  --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
  --case image-atomics-accepted-write-error --timeout 14400
```

Cached aliases and cross-command coherence retain separate complete-host
gates. P10 remains open and acceptance remains 9/40.
