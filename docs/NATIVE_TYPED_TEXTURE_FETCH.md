# Native shader resource bindings and typed component fetch

Status: qualified native hardware path with 114 distinct targeted controls,
including typed and legacy native execution, metadata/decoder/image coverage,
1/4/16/32-lane component execution, nine ABI checks and combined C11 headers.
The [source record](performance/mobile-native-typed-texture-fetch-2026-10-20.json)
retains component/shared-CU/native AXI results and the original fixture failures.
All four [standalone synthesis setup screens](performance/mobile-native-typed-texture-synthesis-2026-10-20.json)
pass at standard 1000 ps, 25% I/O, TC/SLVT and no retiming. GTX1 now advertises
hardware typed component fetch with feature bit512. Existing driver shader
admission and LLVM mnemonics/codegen still need separate integration. General
sampling/filtering, LOD application, compressed formats and depth comparison remain open.
The later [54-format catalog](TEXTURE_IMAGE_FORMATS.md) extends uncompressed image
decode and adds GTF1 runtime discovery; the114-control results below describe the
original36-format snapshot.

Native vertex and fragment shader owners now route typed texel component fetch
through independent per-stage GPU resource roots. Integer coordinates retain
all 32 bits, including invalid negative encodings, until the existing checked
image address pipeline rejects out-of-range values. Typed decoding reuses GXI1,
GXS1, GXL1 and the independent image format decoder; shader arithmetic stays on the CUs.
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


The sibling LLVM `opengpu` branch was inspected: its existing texture and quad
encodings remain byte-compatible, and the new funct7/funct6 value2 was unused.
Hardware fixtures issue encoded words using the generated ABI constants; this
does not claim compiler intrinsic or driver allow-list support for the new path.


## Final qualification scope

The final 694-file source passes 114 distinct targeted controls. Native typed
execution covers 44 shared-CU stage draws plus 12 AXI frames/36 primitive draws,
with both shader stages, scalar/vector components, explicit binding slots31/17,
integer/raw values, cube/volume resources, fault/cancellation recovery and exact
MRT/depth/resolve publication. Legacy regression adds84 stage draws and14 native
AXI frames. A denied sampler page produces no physical read. Held completions
continue to pin VM ownership. Metadata readers reject malformed root references
before child launch; all560/570/76 older native field mappings retain offsets.

All nine generated ABI checks, combined headers and CI selection pass. The
synthesis screens use default16-lane components and standard1000ps,25% I/O,
TC/SLVT,no retiming. Core/I/O slack is endpoint+28.238/+395.772ps,
execution+19.027/+378.444ps,native wrapper+51.162/+173.914ps and
credit adapter+634.832/+447.491ps. Mapping is complete for all four. Only the
GFS header capability constants and additional direct width tests changed after
synthesis; every production manifest entry and emitted RTL file remains exact.
These results do not establish a native host clock,frame rate,route or power.
