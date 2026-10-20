# Native shader resource bindings and typed component fetch

Status: development path. Four direct endpoint controls pass; actual shared-CU,
native descriptor, cancellation, translation and synthesis qualification are in
progress. Existing native texture capability bits and driver shader admission
remain unchanged. General texture filtering, normalized floating coordinates,
implicit/explicit LOD application and compressed/depth sampling remain open.

Native vertex and fragment shader owners now route typed texel component fetch
through independent per-stage GPU resource roots. Integer coordinates retain
all 32 bits, including invalid negative encodings, until the existing checked
image address pipeline rejects out-of-range values. Typed decoding reuses GXI1,
GXS1, GXL1 and the 36 packed format decoder; shader arithmetic stays on the CUs.
The fixed-function path selects and decodes a texel, then returns one raw typed
32-bit component. Four ordinary instructions can fill four independent scalar
or vector destination registers without introducing a register-tuple scoreboard.

## Instructions and coordinate packets

`tex.fetch.c rd, packet, unit` uses custom-0, funct7=2 and funct3=c for components
0..3. Scalar rs1 is the coordinate-packet pointer and rs2 the full binding index.
`vtex.fetch.c vd, packets, units` uses custom-1, funct6=2 and funct3=c. Bit25 keeps
RVV vm semantics. Active lanes supply their own full pointers and binding indices;
inactive lanes preserve old destination values. Any active-lane error suppresses
the entire destination write and reports a texture fault through the native owner.
All previous instruction encodings and their meaning remain unchanged.

GXQ1 is a 64-byte immutable coordinate packet: magic/size/version at words0..2,
work context/pass/generation at3..5, then full x/y/z/layer/face/level at6..11.
Words12..15 are reserved zero. Packets are64-byte aligned and may end exactly at
4 GiB. Face is checked before narrowing into the three-bit address-unit port.
The shader publishes packet writes before issuing fetch and retains the packet
until instruction completion. Hardware does not infer a fence from a pointer.

## Stage resource roots

A native shader draw carries a two-word reference: version0 requires address0
and disables typed fetch; version1 selects a128-byte GXR1 root, including legal
address0. Root end and alignment are checked before reads. References occupy
previously reserved words: GTD1 vertex608..609 and fragment624..625; GND1
vertex496..497; GFS1 fragment126..127. Every older word offset and record size
is preserved. A draw snapshots its reference before shader dispatch.

GXR1 owns work identity, stage(0 vertex,1 fragment), binding count1..32, and
separate GXB1, GXI1 and GXS1 table ranges. Limits are33-bit exclusive byte bounds,
including exact4 GiB ends. GXB1 records are64 bytes and indexed by the full
instruction binding index. Each owns identity, stage, unit, image handle,
sampler handle, shader numeric type and texture target. Canonical padding,
reserved words, identity, stage and all table extents are checked before child
admission. The selected image target must agree before data reads.

The authoritative GXS1 filter determines image mip completeness. Requests have
no caller-controlled format or weak-completeness flag. Selected-level metadata
then feeds the existing typed fetch and decoder. This operation fetches a texel;
it does not apply wrap, filtering or sampler LOD clamps to integer coordinates.
The admission contract reports incomplete/incompatible resources as hardware
faults; a future API layer remains responsible for API-specific result semantics.

## Ownership and memory routing

The endpoint uses logical ID0 for root/binding/packet reads, IDs4..7 for typed
fetch and IDs8..12 for image/sampler admission. Reserved IDs are backpressured.
A two-slot adapter maps these IDs onto the existing native stage memory port
without widening any enclosing transaction-ID field. A slot is reserved on
logical request acceptance and released only when its mapped reply is accepted.
Offered requests and completed results retain their payloads through stalls.

One native texture instruction owns the endpoint through scalar/vector retirement.
Legacy sampling retains its word-to-line bridge; typed fetch uses the mapped
line path. Both share the same two external slots at different instruction
lifetimes. The shader stage retains task, VM and payload ownership through
fault/cancel drain. Parents pin published roots, binding/image/sampler/level
tables and address-space state until final draw completion. Invalidating an
active instruction suppresses child cache refills. Metadata is currently read
serially; no new cache-hit or frame-throughput claim follows from connectivity.
