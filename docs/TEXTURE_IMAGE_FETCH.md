# Texture image fetch and typed decoding

The later [image-binding integration](performance/mobile-texture-image-binding-main-2026-10-20.json)
includes the prompt GXL1 descriptor-fault issue correction and 29 controls on
exact 662-file source. Its [current raw/typed synthesis screens](performance/mobile-texture-image-binding-synthesis-2026-10-20.json)
pass at +123.434/+10.117 ps under the same 1 GHz constraints. The 19-control,
655-file records and earlier screens below describe the preceding checkpoint.

Status: integrated into local main with [exact source verification](performance/mobile-texture-image-fetch-main-2026-10-20.json).
[19 controls pass](performance/mobile-texture-image-fetch-pipelined-2026-10-20.json)
on unchanged 655-file source, together with six ABI checks, combined C11 headers,
CI selection and both raw/typed RTL emissions. Both standalone synthesis screens
pass. Native integration remains open. This component does not establish completion of H4/H6
or GLES texture support.

`TextureImageDataFetchUnit` connects the GPU-memory GXL1 level loader and
checked span owner to uncached texture-data reads. Descriptor transactions use
IDs 0/1 and data transactions use IDs 2/3. A checked span of 1 to 16 bytes is
gathered from one or two aligned 64-byte lines; output bits beyond the span are
zero. Expected block layout and byte size must match the verified descriptor
before any data read is issued. The raw path accepts packed texels and 4x4
blocks; compressed block decoding is separate work.

Bus fills can extend beyond the logical allocation within the participating
pages. The checked span is the logical texel/block access. A parent must retain
the allocation and VM context, translate both participating pages and enforce
read permissions until the held result is consumed. Cancellation and faults
must drain offered and accepted requests before releasing that ownership.
Descriptor invalidation does not provide atomicity for concurrent CPU writes.

`TextureImageTypedFetchUnit` captures one of the existing 36 `TypedColorFormat`
storage IDs and uses shared decoding arithmetic after fetching the matching packed
byte layout. These IDs are internal storage encodings. The new format ABI
emitter describes their IDs and byte sizes; it does not advertise native
sampling support. Integer outputs retain their typed values.
GXL1 describes the level's byte layout; it does not contain a format ID. This
component checks byte size and block layout against the captured format. A
parent image binding must supply and retain the authoritative format identity.

The six new controls cover 36 formats at all 64 line offsets (2,304 queries,
150 crossing spans), 132 raw target/block queries, rejected formats/layouts,
descriptor and data faults, 100 cancellation points, held offers/results and
real Sv32 composition. Cross-page data resides in noncontiguous physical pages;
read denial on either descriptor or second data page produces a virtual fault
without issuing the denied physical read. Eleven descriptor/address controls and
two decoder controls also pass, including all 65,536 binary16 patterns.

A prompt first-line fault initially created a new second-line offer on the same
clock edge. The negative test and source are retained in the linked result.
Issue logic now checks the arriving fault before constructing an offer, while
retaining and draining requests that were already offered. The unchanged prompt
fault assertion passes in the completed qualification.

Native image/sampler bindings, mip completeness, LOD selection, coordinate
processing, filtering, compressed-block decoding and shader integration remain
open. PPA evaluation at this stage uses synthesis results only.

The initial [standalone synthesis screen](performance/mobile-texture-image-fetch-synthesis-before-2026-10-20.json)
passes mapping for both profiles at TC/SLVT, 1000 ps and 25 percent I/O budget
without retiming. Raw fetch passes setup at +53.933 ps. Typed fetch misses setup
at -3.880 ps; its critical path runs from the decoder component selector to the
decoded component result. This miss motivated the fetch-specific registered
selection boundary below. No place/route is required.

The revised fetch-specific decoder registers extracted component bits, width,
encoding and default-channel metadata before numeric conversion. It reuses
`TypedColorDecode.unorm`, `TypedColorDecode.half` and the same sRGB table. Each
component takes two cycles. The existing `TypedColorDecoder` and all 649
qualified dependencies remain unchanged; raw-profile active RTL is byte-for-byte
identical. All 19 controls pass again on the revised 655-file frozen source.

The [revised synthesis screens](performance/mobile-texture-image-fetch-synthesis-2026-10-20.json)
both pass complete mapping and reported setup including I/O: raw +53.933 ps,
typed +41.228 ps, at the same TC/SLVT, 1000 ps, 25 percent I/O budget and no
retiming. These are standalone component results; no physical chip area, power,
native execution rate or texture-cluster throughput is established.
