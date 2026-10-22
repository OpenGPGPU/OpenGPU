# GPU-memory indirect drawing

Native typed and binned submission can fetch draw arguments from GPU memory
before starting vertex shaders or primitive assembly. Software supplies the
existing topology, index format, resources and shader state; hardware replaces
the vertex/index count, instance count, first element and signed base vertex.
The native draw's static counts may be zero. Counts retain all 32 bits and are
not clamped to a test workload or framebuffer size.

The packed records follow the argument layouts in GLES 3.1 section 10.5
([specification](https://registry.khronos.org/OpenGL/specs/es/3.1/es_spec_3.1.pdf)).
This hardware interface does not establish API conformance: VAO/buffer binding,
active unpaused transform-feedback restrictions, compiler eligibility and
cross-resource visibility remain software and integration requirements.

| Mode | Packed little-endian 32-bit words |
|---|---|
| 0: direct | No parameter reads; the entire indirect view must be zero |
| 1: arrays | count, instanceCount, first, reserved |
| 2: elements | count, instanceCount, firstIndex, signed baseVertex, reserved |

Arrays require index format zero; elements require a supported nonzero index
format. Indirect draws set baseInstance to zero. Element indices retain the
existing restart-before-baseVertex rules. Reserved parameter words must be
zero in this interface; nonzero values safely fail the draw. Empty draws still
validate and fetch the complete parameter record, then perform no shader work.

## Native encoding and discovery

Offsets count 32-bit words. Limits are exclusive 33-bit values in two words.
All earlier fields and the GTD1/GND1 sizes remain unchanged. Generated C/JSON
interfaces define the exact names and widths.

| Field | GTD1 | GND1 |
|---|---|---|
| mode, 2 bits; upper padding zero | 76 | 499 |
| parameter virtual base | 88 | 500 |
| logical limit | 89–90 | 501–502 |
| allocation virtual base | 91 | 503 |
| allocation limit | 92–93 | 504–505 |

Probe GVI1 word 2 bits 31:24 before using these fields: bit 0 of that byte
advertises arrays and bit 1 advertises elements. Current native profiles return
mask 3. Older hosts return zero in this byte. Input-version, format-width and
cache-capacity fields keep their values; the default native word is 0x03100502.
Direct descriptors with zero extension fields remain compatible.

The parameter base must be four-byte aligned. Its complete 16- or 20-byte
record must fit inside the logical view, within the 4 GiB exclusive limit.
The allocation must cover every touched 64-byte line and have aligned bounds.
The reader issues one or two cached reads through VECTOR_SATP, using vertex
transaction zero exclusively until real responses drain. No new transaction
namespace or CPU argument expansion is needed. The completion's internal
`indirectLines` counter counts accepted parameter reads; native MMIO completion
record shapes are unchanged.

The geometry wrapper snapshots the draw, payload context and feedback scope
before reading parameters. Both shaders and assembly receive the same resolved
arguments. Parameters cannot overlap the shader's private scratch prefix.
Binned admission additionally checks bin arena/pool/directory and render
scratch/source/resolve roles, even when static counts are zero. These range
checks do not prove disjoint physical mappings of arbitrary virtual aliases;
resource ownership must provide that guarantee.

## Publication, faults and cancellation

The parameter buffer and shader resources must remain immutable until draw
completion. Native opcode 7 retains zero dependency/event flags. A GPU producer
can signal an event, followed by an opcode-5 cache-publication command waiting
on that event for the physical parameter lines, followed by the native render.
The existing graphics dependency-idle gate orders the publication before render;
the CPU does not read arguments back or copy their counts into the descriptor.
General cross-client barrier qualification remains open.

Parameter shape/reserved failures report input status 1; a short logical span
reports 2; a read fault reports 3 with the aligned faulting virtual line; cancel
reports 4 with address zero. Parameter failures launch no child shader and
retain the draw identity. A held completion remains stable. Cancellation keeps
already offered reads, drains accepted responses, and prevents unoffered later
reads. Reset, VM pins and payload ownership retain the enclosing host protocol.
The optional post-transform cache keeps its existing explicit purity promise
and clears tags for each resolved draw.

## Verification and remaining gates

Nineteen directed checks cover packed offsets/full-width arithmetic, bounds,
held offers/results, faults/cancel and healthy recovery; legacy and indirect
actual two-CU geometry; metadata readers and GVI discovery; four translated
native typed/binned local/global image cases; transform-feedback regressions;
and the AXI fixture's drained-response MMIO hook. Native cases use actual CU
stores, queued event/cache publication, zero static counts, signed base vertex,
exact MRT/depth/resolve and guard bytes. Typed local mode enables 16-entry
output reuse and repeats indexed triangles. Faulted parameter translation and
reset with a real pending response both recover to healthy draws.

All three generated ABI checks and strict C11 header validation pass. Existing
1,138 scalar definitions retain their offsets, widths and signedness; ten
indirect fields are added. Raw logs and manifests remain local in `.artifacts/`.

The standalone reader passes synthesis setup at 1000 ps, TC/SLVT, 25% I/O,
standard ABC, no retiming or false paths: core +346.032 ps, I/O +324.578 ps,
zero skipped operators. This qualifies the reader only. Enclosing geometry,
vertex stage/host timing, workload throughput, API/compiler validation and the
remaining H0–H8 requirements are still open.

Reproduce focused checks and the reader screen with:

```sh
sbt -Dopengpu.simJobs=4 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.IndirectDrawReaderSpec' \
  'testOnly opengpu.graphics.VertexGeometryStageSpec -- -z "indirect"' \
  'testOnly opengpu.graphics.NativeTypedGraphicsHostAxiSpec opengpu.graphics.NativeBinnedGraphicsHostAxiSpec -- -z "consume GPU-published indirect"'
sbt 'runMain opengpu.elaboration.EmitNativeTypedGraphicsAbi --check' \
  'runMain opengpu.elaboration.EmitNativeBinnedGraphicsAbi --check' \
  'runMain opengpu.elaboration.EmitNativeBinnedHostAbi --check' \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-indirect-draw .artifacts/indirect-reader-rtl'
python3 scripts/qualify_ppa.py .artifacts/indirect-reader-rtl IndirectDrawReader \
  --stage synthesis --period-ps 1000 --io-delay 0.25 \
  --output .artifacts/indirect-reader-synthesis
```
