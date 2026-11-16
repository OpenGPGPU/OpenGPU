# Exact owned-vertex GNT sequence capture

The new `TessellationNativeVertexSequenceReader`,
`TessellationNativeTriangleVertexSequenceReader` and
`TessellationNativeQuadVertexSequenceReader` capture only GNT7, GNT8 and GNT9,
respectively. Each requires the original 16-lane/eight-warp metadata capacity.
They do not replace or widen the GNT1–6 readers, requests or descriptors.

| Domain | Raw magic | Header word 2 | Inherited field map |
| --- | --- | --- | --- |
| Isoline | `0x474e5407` | 7 | GNT4 |
| Triangle | `0x474e5408` | 8 | GNT5 |
| Quad | `0x474e5409` | 9 | GNT6 |

All records remain 512 words/2048 bytes. The readers reuse the original
domain/GUR3 field maps and `NativeTypedGraphicsLayout.check/receive`, with one
exact magic/version pair and no alternate dialect. Raw headers are never
rewritten. The append-only contract is recorded in
[`tessellation-vertex-sequence-v1.json`](abi/tessellation-vertex-sequence-v1.json).
Words 509–511 append the 32-bit GVS base and exclusive 33-bit limit.
The logical GNT request must be exactly 2048 bytes; its allocation envelope may
be larger. The source reference must be an aligned, exact 512-byte span inside
the accepted private scope, including an exclusive endpoint at 2^32.

The new contract rejects old patch DMA words 17–23, vertex stride 491/492,
static TES count/array 237–481 or 238–482, and legacy uniform flag 482/483.
The GUR3 tail at 496–508 remains unchanged. Enabled roots must have their full
64-byte headers inside the private scope and their captured identities must
match the frame. Layout limits retain 32 vertices, 64 components, 120 per-patch
components and the coupled 2048-word bounds. TES export components remain
independent of the TCS output layout.

`TessellationVertexSequenceRequest` is a new internal bundle. Its accepted
owner supplies the private scope, frame identity and **both** expected
sequence and original-draw ordinals. The existing GNQ array index can supply
only the former. This block provides no mapping from that index to an original
draw, does not equate the two values and does not use the draw token as an
ordinal. The captured GVS request passes both expected values unchanged, along
with the actual GNT task-ID base, count and complete patch layout. Only the
accepted owner's command tag replaces the verified-zero raw command field.

One tag-zero read is outstanding. A held read and its actual reply survive
cancellation; a real late memory fault takes precedence. Success and error
reports are captured and stable under backpressure. Error reports expose no
decoded sequence or GVS request. The outer owner must pin immutable records,
programs, resources and VM state through the complete execution and drain.

The shared Field metadata now allows up to one complete 512-bit cache line,
retaining its positive-width and line-containment checks. This accommodates
the existing 128-bit control/evaluation helper masks without changing a field
offset, raw record, old reader or JVM constructor signature.

## Validation and limits

The six focused RTL cases use independent literal little-endian packets for
all three new readers and both old contracts per domain. They cover exact
headers, reserved bits, full 128-bit helper masks, scope and capacity bounds,
distinct sequence/draw ordinals, cancellation, late faults and healthy reuse.
The published field catalogs are checked independently from the functional
packets; a field crossing a cache-line boundary is rejected at elaboration.

[Fresh-source results, negative controls and reproduction](P39_CAPTURE_RANGE_SOURCE_DELIVERY.md)
record the current delivery checks without requiring historical class outputs.
These readers do not read GVS/GND, inspect RX instructions or GUR contents,
check physical aliases, launch kernels, generate patches, or connect a GNQ job
to the typed parent. The outer owner must still pin the original resources and
supply the checked original-draw mapping. No public capability is enabled.
