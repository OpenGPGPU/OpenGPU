# GPU-memory texture level records and checked spans

Status: components integrated into local main; [11 controls pass](performance/mobile-texture-level-resource-2026-10-20.json)
on unchanged 649-file source, together with five ABI checks, C11 headers, CI
selection and two full emissions. Native sampler integration remains open.

`TextureImageResourceAddressUnit` loads a resolved mip layout from GPU memory,
validates its complete record, then uses the qualified `TextureImageAddressUnit`
to compute a checked byte span. A zero-coordinate query can resolve a level for
binding; the returned view can then feed a separate streaming address pipeline.
This admission owner is serialized and does not claim cluster filtering rates.

The GXL1 record has 32 little-endian words (128 bytes). Its explicit layout is
emitted by `EmitTextureImageLevelAbi` into
[the C header](../driver/opengpu_texture_image_level_abi.h) and
[the JSON layout](abi/texture-image-level-v1.json). Narrow fields have zero
padding; words 25 through 31 are reserved zero. Both 33-bit halves remain in
the same 64-byte line, so either tagged reply may arrive first.

| Word | Contents |
|---:|---|
| 0 / 1 / 2 | Magic `0x47584c01`, byte size 128, version 1 |
| 3 / 4 / 5 | Context, pass and generation identity |
| 6 | Record handle |
| 7 / 8 | Image target and selected mip level |
| 9 / 10–11 / 12 | Allocation base, 33-bit exclusive limit and selected level base |
| 13 / 14 / 15 / 16 | Width, height, depth and array-layer count |
| 17–18 / 19–20 / 21–22 | Row, volume-slice and array-layer/cube-face byte pitches |
| 23 / 24 | 4x4-block layout flag and bytes per packed texel/block |

A lookup snapshots a versioned table with identity, base and exclusive limit,
a 32-bit record handle, expected mip level, caller tag and integer coordinates.
Tables start on a 64-byte boundary and contain whole 128-byte records. The
address `table.base + handle * 128` is computed at wide precision and checked
before either read is offered. Tables and records may end exactly at 4 GiB.

Two tagged, uncached 64-byte reads populate a private snapshot. The complete
record must match the lookup's identity, handle and mip. Reserved words,
noncanonical padding and header errors fail before address work. The address
pipeline then validates target shape, constructor limits, disjoint pitches,
full level allocation and coordinates. Only success publishes the view and
span; errors preserve request identity but return a zero view and span.

| Status | Meaning |
|---:|---|
| 0 | Verified view and checked address |
| 1 | Invalid table envelope or view shape/encoding |
| 2 | Allocation or pitch bounds failure |
| 3 | Descriptor-memory fault |
| 4 | Cancellation before another primary error |
| 5 | Invalid texel/layer/face coordinate |
| 7 | Record header, identity, handle, level, reserved-word or padding error |

A memory fault retains the first faulting descriptor line's address and a
separate validity flag: address zero can be a valid fault address. Every offered
read remains immutable through backpressure and cancellation. All accepted
replies and address work drain before completion. The held result remains
immutable through later cancellation or invalidation, and `busy` remains true
until that result is consumed. A parent must retain the table/VM lease through
this boundary.

One exact-key view cache includes table version, all three identity words,
table base/limit, handle and expected mip. Query coordinates and caller tags are
not descriptor identity. Invalidation revokes cached reuse immediately; an
invalidation during a fill prevents that fill from repopulating the cache.
Only a successful, fully checked request can fill it. Published records must
remain immutable while owned; invalidation does not make concurrent writes to
an in-flight record atomic. Owners must invalidate on rebinding, including
same-address/same-generation reuse, and separately enforce memory visibility.

This is a level-layout ABI. It does not authenticate allocations, translate
addresses, select an image binding, validate a complete mip chain, assign a
texture format or decode/filter texels. Texture-data reads are not issued here.
The parent must provide translation/permissions, GPU publication, format and
sampler state, indexed per-stage bindings and native fault/VM integration.
Existing native sampler records, GTX1, shader instructions and capabilities
are unchanged. H4/H6 and general GLES texture support remain open. PPA screening
is synthesis only.

The loader enables the optional four-stage address profile. The extra elastic
sum/bounds register preserves one address per cycle and changes only its latency.
The default three-stage profile preserves its ports, registers and arithmetic
after five internal net renamings. [Synthesis screening](performance/mobile-texture-level-resource-synthesis-2026-10-20.json)
at TC/SLVT, 1000 ps, 25 percent I/O budget and no retiming reports +53.957 ps
worst setup slack for the loader and +70.945 ps for the default address unit.
Both mapping gates pass; the initial -88.594 ps composed result is retained.
No place/route, power or native-host rate result is implied.
