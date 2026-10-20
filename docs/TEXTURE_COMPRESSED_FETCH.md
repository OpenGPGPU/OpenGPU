# Native ETC2/EAC component fetch

Status: implementation and independent block/image checks in progress. Native
execution, complete fault/mip qualification and fresh synthesis are required
before promotion. Full H4/H6/H0–H8 hardware completion remains open.

The image catalog uses its remaining IDs54–63 for the ten core ETC2/EAC formats.
Existing IDs0–53 and GXI1/GXL1/GXR1/GXB1/GXQ1 record layouts remain stable.
GTF1 enumerates 64 formats with two full 32-bit masks. GTX1's legacy sampler mask
and instruction encodings retain their meanings. The attachment catalog stays 36.
All 6-bit image IDs are now assigned; canonical descriptor padding still rejects
format values with high bits set.

| ID | Internal image format | Codec | Bytes per 4×4 block |
| --- | --- | --- | --- |
| 54 | RGB8_ETC2 | RGB | 8 |
| 55 | SRGB8_ETC2 | RGB | 8 |
| 56 | RGB8_PUNCHTHROUGH_ALPHA1_ETC2 | Punch-through | 8 |
| 57 | SRGB8_PUNCHTHROUGH_ALPHA1_ETC2 | Punch-through | 8 |
| 58 | RGBA8_ETC2_EAC | RGB + alpha | 16 |
| 59 | SRGB8_ALPHA8_ETC2_EAC | RGB + alpha | 16 |
| 60 | R11_EAC | Unsigned R | 8 |
| 61 | SIGNED_R11_EAC | Signed R | 8 |
| 62 | RG11_EAC | Unsigned RG | 16 |
| 63 | SIGNED_RG11_EAC | Signed RG | 16 |

Compressed bytes retain the specification's external order: the first byte is at
the lowest address and raw bits 7:0. An RGBA block stores alpha first and RGB
second; an RG block stores R first and G second. GXL1 owns block4x4=true and
blockBytes 8/16. Mip row pitches count compressed block columns; NPOT edge blocks
remain fully allocated, while coordinates beyond the declared extent are rejected.
GXI1 admits 2D, array and cube images and rejects 3D ETC2/EAC before child reads.

The block decoder receives an immutable block and local 2-bit X/Y coordinates.
It handles individual/differential/T/H/planar color modes, punch-through selectors,
EAC alpha, unsigned 11-bit and signed 11-bit channels. Extraction, products, sums
and clamping are registered separately; results remain held until consumed.
The image decoder normalizes the selected integer values to FP32, converts sRGB
RGB components before returning them and supplies missing-channel defaults.
Alpha uses 8-bit UNORM, EAC uses denominators 2047/1023. Signed base -128 is treated
as -127 as required by the specification. Multiplier zero means zero for alpha
and a 1/8 multiplier for EAC11. No decoded-block cache, new filtering mode or
sampling-throughput improvement is claimed.

The [independent test oracle](../src/test/resources/opengpu/texture/detex/UPSTREAM.json)
pins unmodified detex C sources and retains their permission notice. The adapter
canonicalizes signed base -128 to -127 before calling upstream, whose decoder
otherwise rejects that value. Expected FP32 values use host arithmetic on its
quantized outputs. Production RTL does not call or include the C decoder.

Initial comparisons exposed an ETC color sum truncated before saturation; the
adder now retains its carry. A later reconstruction-value fixture initially
packed repeated 3-bit EAC selectors in host byte order; its corrected input uses
compressed byte order. Both failures and subsequent controls are retained under
`.artifacts/mobile-etc2-eac-*`.

The format rules follow GLES 3.0.6 section 3.8.6 and Appendix C:
[official specification](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf).
General filtering/LOD, swizzle, depth comparison, decoded-block caching, Mobile
throughput, compiler/driver admission and complete hardware qualification remain
separate outstanding requirements.
