# GPU sampler state and image completeness

Status: [qualified component](performance/mobile-image-sampler-binding-2026-10-20.json)
with ten new controls and ten existing image controls on unchanged production
dependencies. Native per-stage binding tables and shader typed texture transport
remain open. Existing `tex.sample` and `vtex.sample` retain
all encodings and packed RGBA8 behavior. No new native capability is advertised.

`TextureSamplerBindingUnit` loads an immutable GXS1 sampler from GPU memory.
`TextureImageSamplerBindingUnit` composes it with the existing GXI1 image owner.
Sampler and image handles are independent. Both tables must identify the same
context, pass and generation. A parent snapshots their table descriptors and
pins publication and address-space leases through the held final completion.
Invalidate both owners when rebinding, including reuse of identical addresses
and identity. Identity alone does not provide memory protection.

The composed request contains image/sampler tables and handles, a full 32-bit
selected level, expected shader numeric type and caller tag. It has no caller
`requireMipChain` bit. The captured minification filter determines whether the
image owner validates the complete required mip set. For a nonmip filter, the
selected level and base level must both be valid; a second base-level admission
is needed when they differ. The successful result retains the selected view.
No texel read or shader write is performed by this admission component.

This implements sampler-dependent completeness and filter restrictions from
[GLES 3.0.6 sections 3.8.13 and 3.8.13.1](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf).
The 36 existing packed color formats use their authoritative GXI1 numeric type.
Integer and raw FP32 storage allow only nearest magnification and either nearest
or nearest-mipmap-nearest minification under this core policy. Normalized,
sRGB and binary16 storage allow all six minification filters. A mismatched shader
numeric type fails admission instead of converting signed/unsigned/float values.
Depth formats are not yet implemented by GXI1; comparison samplers paired with
these color formats fail compatibility. Adding descriptor fields does not imply
that coordinate selection, filtering, depth comparison or LOD math is implemented.

## GXS1 memory layout

One record is 16 little-endian words (64 bytes). Table bases and exclusive limits
are 64-byte aligned; handles index whole records. The exclusive limit is 33 bits
and may equal 4 GiB. Full-width handle arithmetic is checked before narrowing.
Each field occupies a full word with canonical zero padding for narrow fields.
The generated C header and JSON schema map every RTL record field exactly once.

| Word | Contents |
|---:|---|
| 0 / 1 / 2 | Magic `0x47585301`, byte size 64, version 1 |
| 3 / 4 / 5 / 6 | Context, pass, generation, sampler handle |
| 7 | Minification: nearest=0, linear=1, nearest/mip-nearest=2, linear/mip-nearest=3, nearest/mip-linear=4, linear/mip-linear=5 |
| 8 | Magnification: nearest=0, linear=1 |
| 9 / 10 / 11 | S/T/R wrap: repeat=0, clamp-to-edge=1, mirrored-repeat=2 |
| 12 / 13 | Minimum and maximum LOD, raw FP32 |
| 14 | Comparison enabled |
| 15 | Comparison function: never, less, equal, lequal, greater, notequal, gequal, always (0..7) |

This is a resolved internal hardware layout, not GL enumeration values. LOD
fields preserve infinities, signed zero and reversed clamp ranges. NaN is
rejected by this internal contract. This owner stores the values; it does not
perform LOD clamping. [Texture swizzle](TEXTURE_IMAGE_SWIZZLE.md) belongs to the
captured image view and is applied by typed-fetch consumers after decoding.

## Ownership and fault behavior

The reader issues one uncached 64-byte transaction. Its one-entry cache key
includes the entire table descriptor and sampler handle, excluding the caller
tag. Explicit invalidation during an active request suppresses cache refill.
A read offered under backpressure remains stable through cancellation, is
accepted and drains before completion. Late cancel/invalidate cannot change an
already offered result. Reset follows the enclosing fabric's reset contract;
normal cancellation retains transaction ownership until all replies drain.

The composed port uses image/level tags 0..3 and sampler tag 4. Reserved replies
5..7 are backpressured and cannot retire sampler ID 4, including fault replies.
Tags remain
separate through the existing translation client; test translation uses walker
tag 8. A sampler error stops before image admission. An image or compatibility
error publishes no sampler or image payload. The outer result always retains
request identity, both handles, full selected level and caller tag, plus status,
fault source and a valid-address flag. A memory fault at address zero remains
valid. Earlier primary memory faults survive later cancellation.

Status reuses image-resource codes: success=0, invalid view=1, memory=3,
cancelled=4, incompatible format/type=6 and malformed encoding/identity=7.
Outer fault sources are none=0, sampler=1, image=2 and compatibility=3. Child
payloads are zero on failure. This component has no private SRAM and does not
claim native throughput, whole-GPU timing or complete GLES resource support.


## Component qualification

The final ten sampler/composition controls cover 324 filter/wrap combinations,
432 format/filter pairs, 19 malformed/semantic records, seven invalid table
ranges, 180 cancellation cuts and an additional stalled nonmip base read. Reserved-ID injections also prove
that unowned replies cannot consume a sampler lease. They
also check immutable completion, exact 4 GiB ends, address-zero primary faults,
Sv32 sampler/image/level permissions and healthy reuse. Ten existing image
controls pass in the preceding combined run; all their source and production
dependencies are byte exact in the final snapshot. Four ABI generators check
cleanly, the eight graphics C headers compile together, and CI selection passes.

Both [standalone synthesis screens](performance/mobile-image-sampler-synthesis-2026-10-20.json)
pass full mapping and reported setup at 1000 ps, 25% I/O, TC/SLVT, standard ABC
and no retiming. Sampler admission has core/I/O slack +413.729/+479.412 ps and
6,188 mapped cells; composed image/sampler admission has +104.437/+424.024 ps
and 69,907 cells. Both final screens are fresh after the reserved-ID fix; every
production manifest entry and emitted RTL file remains byte exact. These are
component results. Native sampling, sustained rates and whole-GPU timing are
still unqualified. Development fixture failures remain in the linked record.
