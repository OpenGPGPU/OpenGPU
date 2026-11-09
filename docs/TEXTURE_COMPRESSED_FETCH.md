# Native ETC2/EAC component fetch

Status: qualified native component fetch with 130 distinct targeted tests,
nine generated ABI checks, combined C11 headers and five standalone synthesis
setup/mapping screens. Full H4/H6/H0–H8 hardware completion remains open.
The [functional record](performance/mobile-etc2-eac-fetch-2026-10-20.json) retains
source identity, the independent oracle and failed controls.

The image catalog assigns IDs54–63 to the ten core ETC2/EAC formats and ID64
to one-byte stencil-only storage. Existing IDs0–53 and GXI1/GXL1/GXR1/GXB1/GXQ1
record layouts remain stable. GTF1 now enumerates 93 formats with a seven-bit ID
field; its two 32-bit mask words cover IDs below 64 and the count-authoritative
feature governs the rest. GTX1's legacy sampler mask and instruction encodings
retain their meanings. The attachment catalog stays 36. IDs65–92 enumerate the
28 ASTC LDR formats as 16-byte blocked storage with footprint-carrying level
views; bounded block decoders exist, but the per-texel sampling pipeline is not
yet wired, so binding and typed fetch reject an ASTC view with the format status
before any data read. Canonical descriptor padding still
rejects format values outside the advertised count.

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
General filtering/LOD, depth comparison, decoded-block caching, Mobile
throughput, compiler/driver admission and complete hardware qualification remain
separate outstanding requirements.

[Image-view component swizzle](TEXTURE_IMAGE_SWIZZLE.md) is now captured at
GXI1 admission and applied after compressed reconstruction by typed-fetch
consumers.


The composed address owner registers target/block coefficient preparation before
full-width multiplication. Its address pipeline grows from 4 to 5 cycles while
retaining one address/cycle; the original 3/4-cycle profiles remain available.
Image decode is serialized: 12 cycles for uncompressed storage and 18 cycles for
compressed storage after decoder admission, plus any held-result cycles.

## ASTC LDR enumeration

IDs65–92 are `COMPRESSED_RGBA_ASTC_WxH_KHR` and
`COMPRESSED_SRGB8_ALPHA8_ASTC_WxH_KHR` for the footprints 4x4, 5x4, 5x5, 6x5,
6x6, 8x5, 8x6, 8x8, 10x5, 10x6, 10x8, 10x10, 12x10 and 12x12. Each entry
records 16 bytes per block plus its width and height in the generated C header
and JSON. GXL level records now carry those two dimensions in words 28/29, and
the address unit computes row/column counts and in-block coordinates for all
14 footprints (directed across block boundaries and invalid footprints). The
entries are not yet wired into the sampling pipeline, so binding and typed fetch
still reject them before any data read. The bounded decoders now cover all 2048 block modes, the void-extent constant
color, the weight-grid integer sequence and bilinear infill for all footprints and both
dual-plane weight grids,
the ten LDR endpoint modes with UNORM16 weight interpolation, dual-plane
component selection, sRGB half-step expansion and UNORM8 output, and a general
block decoder that chains these owners into per-texel output for single and
multi partition, dual-plane, sRGB and void-extent blocks (320 directed blocks,
209 fully decoded against an independent pipeline oracle), the per-texel
partition pattern for two to four partitions, the color-endpoint format selection
for one to four partitions, per-partition endpoint-value assembly, and the color-integer sequence
with its quantization-level search. Sampling-pipeline admission and API
exposure remain required.

The final controls include 176,144 independent block comparisons, 10,240 complete
compressed FP32 pixel comparisons and 8,190 exhaustive EAC reconstruction-value
checks. Native tests cover 2,320 compressed components per stage, all local block
coordinates, NPOT edges, cube faces/array layers, three mip levels, held results,
post-data cancellation, vector fault atomicity and 1/4/16/32-lane widths.
Actual-CU checks cover 284 stage draws; translated native checks cover 68 frames
and 204 submitted primitive draws across both payload modes, with legacy paths
retained. The 40-offset post-data scan observes 28 cancelled and 12 held results.

The [synthesis record](performance/mobile-etc2-eac-synthesis-2026-10-20.json)
retains the initial endpoint miss of -15.685 ps and its correction. At unchanged
standard 1000 ps, 25% I/O, TC/SLVT and no retiming, final core/I/O setup margins are
block +551.516/+647.030 ps, typed fetch +223.136/+416.631 ps, sampler admission
+205.927/+424.063 ps, shader endpoint +112.409/+384.441 ps and native wrapper
+116.537/+167.515 ps. The block result is carried by byte-identical emitted RTL;
the four composed screens are fresh after the address cut. The wrapper maps
199,144 cells. These screens do not qualify a whole-host clock or frame rate.
