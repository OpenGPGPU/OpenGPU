# Image-view component swizzle

GXI1 image admission captures an optional component mapping. Native scalar and
vector typed fetches and `TextureImageBoundFetchUnit` apply it to the decoded
RGBA value. The raw fetch/decode children retain their unswizzled output so a
general sampler can apply the same captured mapping after filtering.

This follows the component selection and typed constants in
[OpenGL ES 3.0.6, section 3.9.2.1](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf).
The implementation does not yet provide general floating-coordinate sampling,
filtering, LOD selection or depth comparison.

## GXI1 extension

The record remains version1, 128 bytes. All original fields through word22
retain their offsets and widths. Software must check GTF1 word7 bit0 before
using the extension; older hardware requires these formerly reserved words to
be zero.

| Word | Width | Meaning |
| --- | --- | --- |
| 23 | 2 | Swizzle version: 0 legacy identity, 1 explicit |
| 24 | 3 | Output R selector |
| 25 | 3 | Output G selector |
| 26 | 3 | Output B selector |
| 27 | 3 | Output A selector |
| 28–31 | 32 each | Reserved zero |

Selectors are R=0, G=1, B=2, A=3, ZERO=4, ONE=5. Legacy version0 requires
all four selector words to be zero and returns the original RGBA value.
Explicit version1 with four zero selectors returns RRRR; explicit identity is
`[0,1,2,3]`. Each narrow field requires zero high padding.

Unsupported versions2/3, selectors6/7 and noncanonical legacy selectors return
view status1 at the layout stage before level or texel reads. Nonzero padding
and reserved words return encoding status7. Failed results expose no valid
binding, mapping or component values.

ZERO returns all-zero bits. ONE returns FP32 `0x3f800000` for float/normalized
textures and integer `1` for signed/unsigned integer textures. Channel selection
preserves all 32 source bits, including NaN payloads and signed zero. Missing
channels and depth base-color expansion are supplied by the existing format
decoder before mapping. sRGB conversion and compressed block reconstruction
also occur before mapping.

## Lifetime and faults

Image admission captures the mapping with the format and selected level. Cache
hits reuse that immutable snapshot; publication updates require the existing
invalidation protocol. Held results cannot change when backing memory changes,
or when cancellation/invalidation arrives after completion.

Every fetch performs normal descriptor, coordinate, translation and data checks
even when every selector is ZERO or ONE. Errors suppress all output components;
a later active vector lane error suppresses the entire destination write.
Inactive lanes retain their previous values.

GTF1 remains at `0x220` with eight words. Word7 is now a feature mask with bit0
for image swizzle. The original seven words and all image format IDs remain
unchanged. The old generated `RESERVED_WORD` macro remains an offset alias;
new software should use `FEATURES_WORD` and `IMAGE_SWIZZLE`.

## Qualification scope

The [functional qualification](performance/mobile-image-swizzle-2026-10-20.json)
passes 138 distinct targeted tests against 770 committed inputs, including
11,664 explicit map queries, 16,384 encoded states, 216 bound uncompressed
queries and 768 native component checks per stage across all 64 formats.
Shared CUs execute 572 typed stage draws. Translated native AXI executes 68
frames and 204 submitted primitive draws; both shader stages and payload modes
are covered. Nine ABI generators, combined C11 headers and CI selection pass.

All four [synthesis screens](performance/mobile-image-swizzle-synthesis-2026-10-20.json)
pass full mapping and setup at 1000 ps with unchanged TC/SLVT, 25% I/O,
default ABC and no retiming. Core setup margins are +166.594 ps (bound fetch),
+214.933 ps (sampler admission), +180.831 ps (endpoint) and +136.810 ps (native
wrapper); every I/O group also passes. The initial vector fixture's incorrect
integer-ONE expectation for FP32 is retained with its correction and passing
reruns. No production change was needed for that diagnostic.

PPA evaluation remains synthesis only. This feature does not close H4/H6,
banked cache throughput, complete resource publication/coherence, general sampler
semantics, compiler/driver adoption, whole-GPU timing or the H0–H8 objective.
