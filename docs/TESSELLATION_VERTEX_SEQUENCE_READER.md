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

## Focused results and limits

Eight current prerequisite files and seven current helper/reader/test files
passed separate Scala 2.13.18 / Chisel-plugin compilations, in about 7 and 5
seconds. The latter group freshly compiled the shared native descriptor helper
ahead of the read-only current dependency classes. That helper was the only
authorized change among their original 79 source inputs; the remaining 78
sources, all 525 dependency classes, frozen classpath, compiler inputs, four
ABI catalogs and private compilation outputs matched before/after execution.
This is an incremental component check, not a full current-source build.

Six actual component RTL cases passed in about 64 seconds. Three exercise the
new domain readers with independent literal little-endian packets; three
instantiate both original and uniform-capable old readers for each domain
and verify actual rejection of new packets. The field catalog is independently
compared with the three published domain schemas, the GUR3 extension and the
two new source-reference fields. Functional packets are not generated from
the reader's map.

Coverage includes exact header/domain/version isolation, reserved/high bits,
old/new transport exclusion, GUR3 scope, exact source bounds, coupled capacity,
task-ID overflow, distinct expected ordinals, full nonzero 128-bit masks
(including lanes 127/126/64), held cancellation, late faults, zero error
payloads and healthy reuse. A metadata field crossing a cache-line boundary
is rejected during elaboration. Independent source-copy negative controls
removing the header gate and source bounds fail their respective explicit
illegal-packet status assertions; neither relies on timeout.

Each separate compiler/simulation group had a 180-second limit, one CPU,
128MiB Java heap, 128MiB metaspace, 32MiB code cache and one unoptimized
Verilator job, with simulation cache disabled. Every owned compiler and Runner
group was confirmed terminated.

These readers do not read GVS/GND, inspect RX instructions or GUR contents,
check physical aliases, launch a kernel, generate a patch, or connect a GNQ
job to the typed parent. The verified native profile, protected builder,
original-draw mapping, GVS/GND cross-read chain, actual shared-CU execution and
complete owner/product qualification remain separate unfinished work. No
factory, ioctl, discovery bit or public API is enabled.

Focused reproduction in a permitted resource window:

```sh
sbt 'testOnly opengpu.graphics.TessellationVertexSequenceReaderSpec'
```

For the bounded local route, compile the eight prerequisite sequence/layout
files first, then the shared native descriptor helper, five new main files and
the Spec into separate private class directories. Run ScalaTest with
`-s opengpu.graphics.TessellationVertexSequenceReaderSpec`; `-z` selects one
domain/case at a time. Keep new classes before current prerequisite classes
and frozen dependencies. Do not enable or dispatch Scala regression.
