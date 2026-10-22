# Native typed graphics submission ABI, version 1

The optional `GpuHostSystemAxi.typedGraphics` profile submits a resident tile
image pass and ordered vertex/fragment draws from GPU memory. Vertex fetch,
shaders, clipping, viewport, point/line/triangle rasterization, sample depth,
typed late tests, blending, source stores and resolves use the general graphics
pipeline and the system's shared CUs and payload pool.

This is a separate native profile from the older GBJ1 binned renderer. Discover
the profile before constructing commands. The current typed job covers one
declared tile; general geometry binning and scheduling across tile engines are
still separate integration requirements.

## Discovery and command envelope

The read-only word at MMIO `0x68` points to the eight-word capability record at
`0x160`. A typed profile returns `GTC1`, `0x47544301`, in capability word 0.

| Capability word | Meaning |
|---|---|
| 0 | `0x47544301` |
| 1 | Record length, 8 words |
| 2 | GTJ1 job magic, `0x47544a01` |
| 3 | Job bytes in bits 15:0; pass bytes in bits 31:16 |
| 4 | Draw bytes in bits 15:0; attribute capacity in bits 23:16; binding capacity in bits 31:24 |
| 5 | Varying-slot capacity in bits 7:0; color locations in bits 15:8; sample capacity in bits 23:16 |
| 6 | Resident attachment bytes in bits 23:0; raster subpixel bits in bits 31:24 |
| 7 | Bit 0: local payload service; bit 1: trusted physical image views; bit 2: shader ABI v2; CU count in bits 15:8 |

The local-payload capability allows eligible prefixes to reside in the common
pool. Oversize prefixes or pool pressure can select the existing controlled
global spill path. It does not promise that every draw is resident.

Submit unified opcode 7 with these existing command registers:

| Register | Value |
|---|---|
| `COMMAND_ID` | Software command tag |
| `OPCODE` | 7 |
| `SOURCE` | 64-byte-aligned virtual address of the GTJ1 header |
| `DESTINATION` | Low 32 bits of the header's exclusive limit |
| `PATTERN` | Bit 0 is limit bit 32; bits 31:1 are zero |
| `BYTES` | 512 |
| Dependency/event flags | Zero |

The complete header must fit within the submitted bound and below the 4 GiB
exclusive limit. An invalid envelope completes without issuing a descriptor
read. The existing opcode-7 host control retains DMA serialization, compute
overlap in the pinned VM, cancellation, reset and completion buffering.

## Scalar encoding and generated interfaces

All offsets below count little-endian 32-bit words. A scalar occupies one word;
33-bit limits and 64-bit masks occupy consecutive low/high words. Narrow unsigned
fields, including booleans, have zero upper padding. Narrow signed fields
sign-extend to 32 bits; sampler bias is a signed five-bit value. FP32 values are
raw IEEE-754 binary32 encodings. Reserved words and reserved padding bits are
zero. All records have 64-byte-aligned bases.

[The generated C header](../driver/opengpu_native_typed_abi.h) supplies named
word offsets and bit widths for every field. The
[JSON map](abi/native-typed-graphics-v1.json) records the same scalar fields.
These layouts are explicit and independent
of Chisel Bundle packing, lane count and reduced hardware capacities. Active
fields beyond an advertised capacity are rejected before draw execution.

Regenerate or check the interface with:

```sh
sbt 'runMain opengpu.elaboration.EmitNativeTypedGraphicsAbi'
sbt 'runMain opengpu.elaboration.EmitNativeTypedGraphicsAbi --check'
cc -std=c11 -Wall -Werror -x c -fsyntax-only driver/opengpu_native_typed_abi.h
```

Adding an internal descriptor field cannot silently omit it from the native
layout: elaboration checks that every scalar is mapped exactly once. ABI changes
must retain the version-1 layout or introduce a new version.

## GTJ1 job header

The job header is 128 words, 512 bytes. Words 14 through 127 are reserved.

| Word | Field |
|---|---|
| 0 | Job magic, `0x47544a01` |
| 1 | Record bytes, 512 |
| 2 | Layout version, 1 |
| 3, 4, 5 | Logical context, pass and generation identity |
| 6 | Pass-record virtual base |
| 7, 8 | Pass-record exclusive limit, 33 bits |
| 9 | Draw-array virtual base |
| 10, 11 | Draw-array exclusive limit, 33 bits |
| 12 | Draw count |
| 13 | Draw stride in bytes |

The pass record must fit in its declared envelope. For nonzero draw count, the
stride is at least 2,560 bytes and a multiple of 64. Checked wide arithmetic
requires the complete final draw record to fit in the draw-array limit; padding
between records is not fetched. Count zero executes a clear/load/store/resolve
pass without shader draws. The reader supports all three record types ending
exactly at the 4 GiB exclusive limit.

## GTI1 image-pass record

The pass record is 320 words, 1,280 bytes. Words 0, 1 and 2 contain magic
`0x47544901`, byte count 1,280 and layout version 1.

| Word or range | Fields in order |
|---|---|
| 16, 17, 18 | Image, pixel and color descriptor versions; each is 1 |
| 19, 20, 21 | Identity; must match the job |
| 22..28 | Signed origin X/Y, tile log2, width, height, sample mode, reserved DS bytes |
| 32..36 | Depth format, offset, load operation, FP32 clear depth, clear stencil |
| 40..44 | Separate-stencil view in the same layout |
| `64 + 16*c`, `c=0..3` | Enabled, format, offset, channel mask, load operation, four FP32 clear components |
| `128 + 16*i`, `i=0..5` | Source image view |
| `224 + 16*i`, `i=0..5` | Resolve enabled; destination image view starts in the following word |

Image view indices 0..3 select colors, 4 selects primary depth/stencil and 5
selects separate stencil. Each image view uses these twelve words:

| Relative word | Field |
|---|---|
| 0 | Physical base |
| 1, 2 | Logical exclusive limit, 33 bits |
| 3 | Physical allocation base |
| 4, 5 | Allocation exclusive limit, 33 bits |
| 6, 7, 8 | Image width, height and row pitch |
| 9, 10, 11 | Readable, writable and source-store flag |

Allocation bounds cover complete bus lines. Width, height, pitch and logical
limit bound useful image bytes. These are trusted physical allocation records,
including their permission flags; they are not virtual resource-table entries.
The physical transfer requests bypass `VECTOR_SATP` and retain physical bus
faults. Source-store and resolve enable are independent; an enabled resolve
requires a writable, single-sample destination of the attachment's format.

Attachment layout, format, overlap, load/store, bounds and resolve checks are
the same checks used by `TypedTileImagePipeline`. The pass identity, formats,
sample mode and attachment state remain fixed across its draws.

## GTD1 draw record

The draw record is 640 words, 2,560 bytes. Words 0, 1 and 2 contain magic
`0x47544401`, byte count 2,560 and layout version 1. Unlisted words are reserved.
The C/JSON interfaces give every scalar offset within these sections.

| Section | Word range or stride |
|---|---|
| Draw version, shader-owner version, topology, 64-bit flat mask | 16..20 |
| Vertex shader ABI version, PC, attribute slots, varying slots, 64-bit varying mask | 32..37 |
| Vertex scratch view | 40..45 |
| Vertex sampler | 48..55 |
| Vertex-input version, identity, counts, indices, instances, restart | 64..75 |
| Indirect draw mode; parameter view | 76; 88..93 |
| Index-buffer view | 80..85 |
| Sixteen attribute descriptors | `96 + 16*a`, `a=0..15` |
| Sixteen buffer bindings | `352 + 8*b`, `b=0..15` |
| Viewport version, signed X/Y, extents, near/far FP32 depth | 480..486 |
| Raster version, extents, facing/cull, samples, sizes and scissor | 496..508 |
| Fragment shader ABI version, identity and PC | 512..516 |
| Fragment scratch view | 520..525 |
| Fragment varying/color slots, location mask, four color types, depth override and samples | 528..536 |
| Fragment sampler | 544..551 |
| Late-test version, depth enable/write/function, stencil enable | 560..564 |
| Front/back stencil state | 568..574 / 576..582 |
| Blend enable/version, factors, equations and constant RGBA | 592..603 |

Scratch, index and binding views start with base, 33-bit exclusive limit,
allocation base and 33-bit allocation limit. Bindings append stride and divisor.
An attribute has enabled, binding index, byte offset, component count, format
and four constant components. A sampler has format, virtual base, dimensions,
clamp, maximum/minimum levels and signed bias; use the exact named offsets in the header.

[Indirect drawing](INDIRECT_DRAW_SUBMISSION.md) is enabled only after probing
the GVI1 high-byte mode mask. Mode zero requires a zero parameter view and
preserves direct draws. The extension uses reserved words without moving any
existing field or changing the 640-word record size.

The native record layout version is independent of shader ABI versions 1 and 2.
The draw/shader-owner, input, viewport, raster and late-test descriptor versions
retain their existing version-1 contracts. Vertex and fragment shader versions
select their existing ABI1/ABI2 arrays and uniform prefixes. PCs use
`INSTRUCTION_SATP`; descriptor, geometry, scratch and sampler traffic uses
`VECTOR_SATP`. Native submission does not change CU instruction encoding.

## Ownership, completion and limits

Publish descriptors, programs and input data before submission using the
platform's existing publication/invalidation path. Pin their allocations,
physical backing views and VM state through the software-held completion.
The logical identity matches records within the job; it does not replace the
host's SATP-derived payload context or allocation ownership.

The reader validates complete record envelopes before issuing reads, permits
four independently tagged replies, rejects padding/encoding errors, and executes
one immutable draw snapshot at a time. Shader scratch prefixes must be disjoint
from the job, pass and draw-array command arenas. Existing stage checks also
exclude scratch/input and vertex/fragment scratch aliases.

Cancellation retains offered memory and child begin/draw/end controls through
acceptance, then drains the child. A child pass completion closes remaining
private draw/end offers. Reset also drains shader leases, descriptor reads and
physical acknowledgements before releasing the pinned VM. Only successful final
completion validates the complete backing image. A failed/cancelled pass may
have acknowledged a prefix of its stores.

The unified software completion retains command ID, opcode, status and accepted
draw-record read bytes. The internal native completion additionally records the
fault stage/address/draw, issued/completed draw counts and complete image result;
the current MMIO completion does not expose those additional detail fields.

Native general binning, full resource tables/allocation ownership, broader
texture/attribute semantics, guard-band/qualifier closure, general reuse qualification,
query/feedback and workload/rate qualification remain open. This ABI introduces
no PPA result; PPA evaluation remains synthesis only.

## Optional vertex output reuse

GTD1 word 610 is a one-bit `vertex.reuseOutputs` promise; upper padding bits
must be zero. Existing offsets and record sizes are unchanged. Zero preserves
ordinary shader execution. A set bit requires a nonzero GVI1 cache entry count
and a side-effect-free, batch-independent shader with immutable draw inputs and
resources. See the [reuse contract](VERTEX_POST_TRANSFORM_REUSE.md).
