# Typed image storage formats

The image catalog is independent of attachment renderability. It contains all 54
uncompressed sized texture formats required by GLES 3.0 and the ten
[ETC2/EAC formats](TEXTURE_COMPRESSED_FETCH.md), for 64 image IDs. This is format fetch/decode
coverage in the native indexed component-fetch path, not GLES conformance or a
claim of complete sampling. Swizzle, general floating-coordinate
sampling, filtering/LOD, depth comparison and representative throughput remain
open in `MOBILE_GPU_HARDWARE_ALIGNMENT.md`.

The authoritative inventory is generated in
[`abi/texture-image-format-v1.json`](abi/texture-image-format-v1.json) and
[`../driver/opengpu_texture_image_format_abi.h`](../driver/opengpu_texture_image_format_abi.h).
IDs are internal storage encodings, not GL enums or pixel-upload types. GXI1/GXL1
record sizes and fields, all existing instruction encodings and image IDs0–35
retain their previous meanings. Attachment encoders/decoders retain 36 formats.

| Image IDs | Added storage | Bytes |
| --- | --- | --- |
| 36–39 | R/RG/RGB/RGBA8_SNORM | 1/2/3/4 |
| 40–45 | RGB8I/UI, RGB16I/UI, RGB32I/UI | 3/6/12 |
| 46 | SRGB8 | 3 |
| 47 | R11F_G11F_B10F | 4 |
| 48 | RGB9_E5 | 4 |
| 49–51 | DEPTH_COMPONENT16/24/32F | 2/4/4 |
| 52–53 | DEPTH24_STENCIL8, DEPTH32F_STENCIL8 | 4/8 |

Ordinary byte/half/word components place R at the lowest address. Mini-float
fields occupy bits 10:0,21:11,31:22. Shared-exponent mantissas occupy bits 8:0,
17:9,26:18; exponent is 31:27. Fixed depth uses low 16 or24 bits. FP32 depth uses
low 32 bits. D24S8 stencil occupies 31:24; D32FS8 stencil occupies 39:32 and the
remaining high 24 bits are padding. Stencil/padding do not enter a depth fetch.
These internal depth layouts match resident depth storage; API upload conversion
must prepare them explicitly rather than assuming external GL packing.

Missing RGB components return zero; missing alpha returns FP32 one for floating
formats and integer one for integer formats. Depth returns `(D,0,0,1)`. SNORM8
uses `max(signedByte/127,-1)`, including both -128 and -127 mapping to -1.
Normalized conversion rounds once to nearest/even FP32; the image decoder uses 80
fractional bits for SNORM and 16/24-bit depth. Packed floats expand exactly, with
mini-float NaNs canonicalized to `0x7fc00000`. FP32 color/depth storage preserves
raw bits. The interface consumes prepared storage and does not implement API
upload clamping or format conversion.

For uncompressed images, extraction, numeric preparation and rounding occupy
three cycles per component, twelve cycles for four components after admission, plus
any held output cycles. This adds four decoder cycles to the previous image
path. Attachment decoder latency is unchanged. The native instruction remains
serialized and no new rate claim is made. Compressed storage adds six internal
block-decode/handshake cycles, for eighteen decoder cycles before held output.

GXI1 admission rejects depth/depth-stencil with 3D targets before level or texel
reads. Noncomparison depth, integer and FP32 color require nearest-only sampler
state. Comparison remains rejected. Other image formats retain core-format
filterability admission rules; admitting a sampler does not implement its general
filtering in the explicit integer-coordinate component-fetch instruction.

## Runtime discovery

Native profiles expose the read-only eight-word GTF1 block at `0x220..0x23c`.
GTX1 at `0x200..0x21c` retains its original values, including legacy packed-sampler
format mask `0x1f` and typed component-fetch feature bit 512. GTF1 writes return
SLVERR. Profiles without native vertex/fragment execution leave GTF1 unmapped.

| Word | Value | Meaning |
| --- | --- | --- |
| 0 | `0x47544601` | GTF1 magic |
| 1 | 8 | Word count |
| 2 | 1 | Image catalog version |
| 3 | 6 | Format ID bits |
| 4 | 64 | Supported format count |
| 5 | `0xffffffff` | Supported IDs0–31 |
| 6 | `0xffffffff` | Supported IDs32–63 |
| 7 | 0 | Reserved, no filtering/comparison capability |

Software first checks GTX1 typed-fetch support, then reads GTF1. Older typed-fetch
hardware without GTF1 supports only IDs0–35. A header's compile-time count is
insufficient to discover device support. All 64 image IDs are assigned. Software
checks the device mask before using IDs54–63; earlier GTF1 hardware supports only its advertised subset.

The format inventory, noncomparison depth rules, depth expansion and packed float
semantics follow GLES 3.0.6 sections 3.8.3.1,3.8.13,3.8.15,3.8.17 and3.9.2.1:
[official specification](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf).

The [qualification record](performance/mobile-texture-image-formats-2026-10-20.json)
retains 119 distinct targeted tests, source identity, nine generated ABI checks,
combined C11 headers and both failing/corrected AXI fixtures. Four standalone
[synthesis screens](performance/mobile-texture-image-formats-synthesis-2026-10-20.json)
pass full mapping/setup at standard 1000 ps, 25% I/O, TC/SLVT without retiming.
The native wrapper maps 192,891 cells; decoder latency grows 8 to 12 cycles.

The [compressed extension qualification](performance/mobile-etc2-eac-fetch-2026-10-20.json)
now covers 130 distinct targeted tests and all 64 image formats. The 119-test
record above retains the earlier 54-format snapshot.
