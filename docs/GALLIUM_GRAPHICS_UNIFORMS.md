# Linked graphics uniform frontend

This component connects actual linked Mesa ESSL vertex/fragment programs to
native code GEMs, real Gallium uniform bindings and canonical native source
graphs. The guarded submission transport and protected kernel producer pass CPU
integration; complete host execution and live-DUT retirement remain unqualified.
Device API capabilities remain unchanged.

## Compiler and source views

The explicit fixture links real ESSL 3.10 VS and FS programs, each with twelve
16 KiB UBOs and 1,024 default-uniform components. VS uses global bindings 0–11;
FS uses 24–35. Real Mesa NIR/NTT lowering supplies TGSI. Real
`st_bind_vs_ubos` and `st_bind_fs_ubos` map the linked program's block order to
stage constant slots 1–12. Linker block order is not global binding order.
Defaults occupy slot zero and are copied before Mesa reuses its parameter data.
The fixture's compiler limits are separate from the current device's reported
graphics limits.

Production Gallium constant callbacks retain thirteen source views per stage
when native transport discovery succeeds. Default user data receives a new GEM
at each bind; external buffers retain their own exact view/reference. The source
exporter uses this real callback state. Legacy constant handling continues, and
absent native transport does not enable the additional state path. This is
source ownership; accepted-job immutability still requires the protected kernel
snapshot producer.

The native helper supports straight-line raw MOV and unsigned word arithmetic
(add, multiply, bitwise operations and shifts), checked scalar GUR1/GUB1 loads,
position passthrough, flat varyings and raw color outputs. It rejects other
operations, indirect addressing, undefined components and malformed packets.
All source channels are read before an aliased destination is committed. The
512-word native ceiling remains enforced; failed emission leaves its output
untouched. Native SoA addresses retain bounded kernarg/local-base provenance,
including offsets beyond one signed ADDI immediate.
Interface reflection derives attribute, varying and color slot counts from
the actual TGSI declarations. It does not assume the fixture's one-attribute,
one-varying, one-color layout. Capacity remains an explicit input that a
production caller must obtain from verified native layout discovery.

The borrowed-view planner verifies live source-screen membership, original GEM
allocation bounds, alignment, active-view completeness and required byte
ranges. Exact equal GEM/offset/size views can share one of 24 aggregate slots
across stages; differing ranges remain distinct. Defaults are independent.
Unused logical views acquire no slot. Planning alone takes no job ownership,
waits on no producer and publishes no GPU-visible record. Production submission
must independently verify sources, wait on producers, snapshot data and retain
the accepted job's immutable resources through completion and reply drain.
Original GEM source offsets and sizes need four-byte alignment. GUC's
256-byte UBO alignment applies to the copied private destination; existing
64-byte source windows remain valid.

The library also prepares the stable 216-byte per-stage native UAPI records,
using source GEM handles/offsets/sizes and zeroing every inactive view. This
requires the native driver feature and strict `GET_PARAM_NATIVE_LAYOUT`
decoding, with no legacy/compute fallback or truncated physical capacity.
Unknown/reserved fields, unsupported ABI masks and capacities beyond the
current validator's eight-bit batch contract are rejected. The qualification
models these ioctl replies by default. Explicit active mode instead consumes
the immutable actual-DUT probe before screen creation; this wiring alone does
not establish actual hardware execution or enable production capability reporting.

The production runtime-query helper additionally reads all eight frozen GNC
words, rejects a nonzero upper 32 bits in any reply, applies the shared strict
frame-capability validator and cross-checks all four interface slot counts
against `GET_PARAM_NATIVE_LAYOUT`. Missing native feature support, a failed
query or inconsistent discovery leaves the caller's output unchanged. Physical
item capacity comes from GET8; GNC local-payload capacity is measured in bytes.
They remain separate quantities. Reduced interface sizes and payload-free
virtual GNC configurations do not require a fixed product profile.

A separate bounded native program object compiles the actual TGSI using that
runtime capacity and uploads VS/FS into independent source code GEMs. It checks
runtime interface limits and requires every FS varying component to be defined
by VS. Source export combines those exact code views with current real Gallium
uniform bindings; rebinding changes the source records while retaining program
code. Partial code allocation failures release all created resources. These
objects remain separate from legacy shader-state and `draw_vbo` callbacks.
Both stages are emitted with the same varying prefix; differing initial
reflection sizes trigger re-emission rather than a metadata-only adjustment.
Export does not publish a GNJ or prove accepted-job lifetime: the protected
kernel producer still must validate, wait, copy into private RX/RO segments and
hold the owner until completion and response drain.

The ordinary native metadata encoder prepares fixed-capacity GNJ/GND/GFS
records and original-GEM draw plans, with all device addresses left zero.
Its bounded path covers unindexed draws, full-frame viewport, flat varyings
and disabled blend/late tests. It retains all sixteen native attribute/binding
slots and uses the actual compiler layouts. Source windows, component alignment,
packed-format widths, unindexed/instanced endpoints and scratch extent are
checked before output changes. Context identity comes from the accepted
Gallium kernel context. GLSL color-output types are reflected from real NIR
before NTT loses type information. The protected kernel must reconstruct and
validate the graph before execution; encoding does not submit a job.

The explicit native submission frontend assembles the versioned 128-byte ioctl
from these records, exact array byte counts, 792-byte draw plans and 32-byte
frame resource plans. It rechecks frozen discovery, uses the real kernel
context identity and requires a caller-owned completion syncobj. A failed ioctl
leaves the returned fence sequence unchanged. Current CPU qualification checks
this transport boundary through an inspection callback, including failure and
retry. The explicit actual-handler integration below additionally executes the
protected kernel producer with modeled OS primitives.

## Native shader admission

The separate native validator derives writable output slices from the actual
stage ABI and layout. Inputs, flags, built-ins and tail staging are read-only;
FS depth and output-control slices remain writable. The profile preserves
sixteen attributes, sixteen vec4 varyings and four colors, including existing
point-size/point-coordinate offsets. Geometry input version 5 needs its explicit
stage guard. Checked uniform instructions require a verified owned GUR/data
contract. Other resource domains and workgroup barriers remain closed in this
profile. Existing compute and legacy validator entry points retain their
previous contracts.

## CPU validation and open scope

ASan/UBSan passes the actual linked programs: VS 308 words, FS 301 words, each
with 26 checked uniform loads. An independent native-word interpreter consumes
the real source-GEM view data and checks every position/varying/color/control
word and untouched guard for 1, 3, 17 and 128 invocations. Additional native
lowering cases cover component aliases, partial writes, swizzles, all admitted
integer ALU operations, malformed inputs and instruction-capacity rejection.
CPU execution also checks highest attribute 15, varying 15 and color 3,
including the high four bits of the 64-component varying mask, and admits
zero-attribute/zero-varying runtime layouts. Reflection/emission failure changes
neither published layout nor program.
State-tracker checks cover binding 35, rebind, unused global binding 12,
unbound active views, allocation-tail clamping and reference recovery. Planner
checks include all 24 slots, shared cross-stage views, distinct subranges,
foreign-screen/image resources, exact allocation endpoints and failure without
partial output. These use modeled OS/GEM primitives and do not execute a GPU.
Native-source UAPI checks cover feature absence, malformed/failed layout
queries, exact stage records, inactive zero records and unchanged outputs on
rejection.
The real Gallium callbacks also feed the native-word oracle through their
exported source records. Checks include transferred-reference rejection/rebind,
binding 35's shuffled logical slot, copied default data after caller reuse,
fresh default GEM replacement and exact teardown reference recovery.
Original source data mixes binding and word index without XOR cancellation.
Qualification asserts a nonzero combined VS/FS color, sensitivity to each of
52 original endpoints and a swapped binding, and distinct binding/default
rebind results. A zero clear or an omitted shader cannot match that result.
Additional checks read the exact uploaded code GEMs, reject missing VS varying
definitions, recover partial code allocation and execute a re-emitted FS with a
larger common varying prefix. Published ABI JSON independently decodes actual
GNJ/GND/GFS encoder output, including identity, runtime stage layout, fixed wire
capacities, zero addresses and every reserved word/bit. Negative metadata cases
leave output unchanged and allow a healthy retry.
The composed 4×4 RGBA32UI frame uses actual GEM windows and the production GNF
encoder, including primitive/directory/pool storage and four fragment scratch
views. These are source plans, with no private VA reconstruction or GPU run.
A separate TGSI boundary case passes highest attribute/binding 15, varying 15
and color 3 through the program object and native draw encoder. The exact
uploaded words pass native admission and the independent 17-invocation CPU
oracle across all 128 item/guard positions, including the 64-bit mask's high
components and the larger SoA prefixes. This supplements the actual linked
one-attribute ESSL fixture; it is not complete API or RTL qualification.

`scripts/native_graphics_uniform_oracle.py` independently reads OGMEM001 and
computes the original GLSL hash from global bindings, without consulting native
remapping, instructions or private snapshot bytes. It checks every RGBA32UI
pixel plus original-color-GEM prefix/suffix/row padding and, with `--previous`,
prior-record output allocations. Its parser/negative/guard self-test passes;
actual protected-owner host output remains unqualified.
The temporary `--source` JSON has `version:1`, `width`, `height`, `color` and
two `stages`. Each stage supplies `defaults` and twelve `blocks`; every source
contains `handle`, `allocation_bytes`, `offset`, `bytes`, `first` and `last`,
and each block adds its original global `binding`. Sources are observed after
their producer completes and before the corresponding real snapshot copy.
The color record contains the original GEM `handle`, actual allocation-lookup
`physical_base`, `allocation_bytes`, `offset`, `bytes` and `row_pitch`. Optional
`preserve` entries give prior GEM handles and physical allocations. No user VA
or reconstructed private allocation may substitute for the actual lookup.

The active-owner qualification transport has a separate CPU protocol gate.
Its strict little-endian codec carries all 96 actual discovery words plus five
actual registers (ID, capabilities, bin capability pointer, live quota and
shader configuration). It preserves every raw GNX word and 64-bit telemetry,
rejects malformed/duplicate discovery inputs and keeps output unchanged on
rejection. Held observations and per-record execution responses cannot authorize
owner release. Only the separate final response follows both independent image
and guard checks. Five Python/C protocol tests pass with ASan/UBSan; these tests
do not establish the complete active driver-to-DUT handshake or device execution.
The codec also carries the actual legacy status read by the IRQ handler; held
records contain 84 bytes and retired records 100 bytes, excluding their common
12-byte file header. No held observation can substitute for an execution response.

Native validator C coverage checks every input/output/tail slice across full
and empty runtime layouts, VS versions 1/2, FS versions 1–5, capability guards,
canonical fences and the unchanged 512-word ceiling. Existing compute, image,
atomic, SIMT and output-usage validator regressions pass.
The complete `scripts/test_driver.py` regression also passes.

The native program snapshot component also passes ASan/UBSan with the actual
private owner and MMU implementation. It reads original code GEM windows after
the caller's producer wait, validates the executable suffix at the requested
entry, and copies the complete original view into a private RX segment. The
actual Mesa 308-word VS and 301-word FS, and the full 16-slot native programs,
retain exact bytes after their original GEM is overwritten. Tests check the
actual RX/no-write PTE, illegal offsets and entry branches into an unverified
prefix, read-only inputs/tails, missing owned uniforms, insufficient staging,
copy/allocation/append failures and healthy owner reuse. This is CPU component
qualification; complete device execution still needs the integration below.

The input snapshot component passes ASan/UBSan against the actual owner/MMU.
It registers generated resource relocations inside a matching contiguous draw
array, preserving other draws, padding and all non-address metadata. Typed-wire
coverage includes all 16 bindings and 25 input formats, input versions 1/2,
VS versions 1/2, U8/U16/U32 indices with restart, both indirect modes, constants
and empty work. Tests check actual RO/NX input and RW/NX scratch PTEs, the full
SoA prefix, original-allocation bounds through the exclusive physical 4-GiB
endpoint, scratch/input physical aliases, every allocation failure before
successful registration, held VA reservations and retirement/reuse. Borrowed
original GEM mappings do not freeze CPU contents; producer fences and reference
lifetimes remain the production submitter's responsibility. These typed-wire
component tests do not establish Mesa-to-driver-to-GPU input execution.

An explicit actual-handler CPU integration passes ASan/UBSan using the linked
308-word VS and 301-word FS. Gallium/lib sends its unchanged native request to
the production Linux ioctl, scheduler, private owner and MMU bodies. Original
GEM backing comes directly from the same allocator used by that OS fixture;
there is no imported copy, guessed physical address or reconstructed page table.
Two submissions have distinct mutable frame/scratch GEMs and retain shared
read-only code. Binding 35 and the VS default block are rebound between the
snapshots. An observer inside the real post-producer copy records all 26 exact
original views per submission in original GLSL binding order. The independent
source hash requires distinct nonzero expected colors, and original allocation
lookup supplies each color GEM's physical window. File references may close
while job references keep the actual storage alive through separate retirement.

The prepared-owner capture mode passes GCC with ASan/UBSan using an immutable
committed native implementation. It uses the actual hardware MMIO functions
and exports the real union memory before enqueue. Its independent
canonical parser checks OGCAP001/OGMEM001, actual Sv32 mappings, metadata RO/NX
and code RX/no-write permissions, and all uploaded VS/FS bytes. It sends no
held/retired receipt and never authorizes owner release. The capture test copies
a committed kernel revision into its temporary directory before compiling, so
parallel source rebases cannot alter the build. Its profile is explicitly CPU
test data; it cannot qualify the final device discovery or execute GPU code.

This integration models Linux OS/fence primitives and the final hardware kick;
it does not execute GPU instructions or establish complete device concurrency.
Its simulated retirement is only a CPU qualification mode. Final execution must
keep both real owners, mappings and references alive until the complete two-CU
host finishes its series, the independent image/guard oracle passes and actual
completion/drain observations permit retirement. Final PTE unmapping and TLB
shootdown still need replay and observation on that same live DUT before owner
release; CPU cleanup is not that proof. Production device discovery,
typed attachment execution and this same complete host still need integration
and execution qualification. Complete API/compiler support and the 36-UBO/48-texture
cross-stage aggregate resource contract remain open. These bounded programs do
not establish arbitrary GL shader instruction capacity or complete H7.

## Reproduction

Use matching Mesa 22.3.6 source/build objects and the checked uniform-index
lowering source. Keep private outputs in this repository's `.artifacts/`.
Set `TMPDIR` to a fresh short GPU-internal directory; on macOS keep its complete
path at most 52 bytes for sbt socket compatibility, and use the same JVM temp
directory for any later hardware qualification.

```sh
export OPENGPU_MESA_DIR=/path/to/mesa-22.3.6
export OPENGPU_MESA_BUILD=/path/to/matching-mesa-build
export OPENGPU_DRM_INCLUDE=/path/to/drm-headers
python3 scripts/test_gallium_graphics_uniforms.py --build .artifacts/graphics-uniform-active --sanitize
python3 scripts/test_driver.py
python3 scripts/test_test_selection.py
python3 scripts/native_graphics_uniform_oracle.py --self-test
OPENGPU_TEST_SANITIZERS=1 python3 scripts/test_native_graphics_owner_bridge.py
```

With the actual private-owner implementation present in the same source tree,
run `python3 scripts/test_native_program_snapshot.py --sanitize`. While the
actual graphics fixture's private build is active, add
`--programs .artifacts/graphics-uniform-active/glsl` to consume its four original
code-GEM inputs. An independent integration worktree may be specified with
`--snapshot-root`; it supplies the actual owner/MMU sources and their existing
kernel primitive fixture, without changing those files. Missing prerequisites
or requested actual program inputs fail explicitly. Remove the private build
and code inputs immediately after this validation finishes.
Run `python3 scripts/test_native_input_snapshot.py --sanitize` for the input
component; it accepts the same explicit `--snapshot-root` prerequisite.

For the actual-handler CPU bridge, add `--native-kernel-root` to the graphics
qualification command, pointing to a source tree with the production native
submitter and `driver/tests/opengpu_native_submit_fixture.c`. This explicitly
compiles those actual driver bodies with their modeled OS primitives, requires
two completed source-observer records and checks both metadata encodings and
independent source hashes. Missing prerequisites or the expected completion
marker fail. It requires no GPU and must not be reported as device execution.

The explicit prepared-owner capture CPU gate requires a committed native kernel
implementation, the canonical owner-parser revision, and an ARM64 GCC/G++ image
matching the existing Mesa objects:

```sh
python3 scripts/test_gallium_native_capture.py --native-kernel-root /path/to/native-source --native-kernel-revision COMMITTED_SHA --owner-revision OWNER_PARSER_SHA --image ARM64_TOOL_IMAGE --sanitize
```

It checks the first real capture while both owners remain alive, then stops only
the isolated CPU OS model without supplying any approving completion receipt.
Its private build, captures and source copy are removed immediately.

For future actual owner execution, use the initial union memory and each real
executor output, with source inputs captured at that record's snapshot:

```sh
python3 scripts/native_graphics_uniform_oracle.py --initial .artifacts/active/union.memory --final .artifacts/active/record-0.memory --source .artifacts/active/source-0.json
python3 scripts/native_graphics_uniform_oracle.py --initial .artifacts/active/union.memory --final .artifacts/active/record-1.memory --previous .artifacts/active/record-0.memory --source .artifacts/active/source-1.json
```

Delete private builds, captures, caches, logs and the temporary directory after
their validation use ends. See [native uniform bindings](NATIVE_UNIFORM_BINDINGS.md)
for the hardware resource contract and [host ownership](NATIVE_UNIFORM_HOST_OWNERSHIP.md)
for the separate existing component proofs.
