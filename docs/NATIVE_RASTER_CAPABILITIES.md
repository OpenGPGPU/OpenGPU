# Native raster discovery (GRI1)

GRI1 is an optional, read-only block at `0x1d0..0x1ec`, alongside native GVI1
and GFI1. Legacy hosts without native input support leave the range unmapped.
Local main implements this contract. The [combined qualification](performance/mobile-depth-texture-composed-2026-10-20.json)
passes 206 native controls, including AXI discovery and generated-header checks;
the [672-file main integration](performance/mobile-depth-texture-main-2026-10-20.json)
carries those checks by exact native source and RTL identity. This describes
implemented raster state and numeric policy, not complete GLES conformance or
production throughput.

| Word | Meaning | Value |
|---|---|---|
| 0 | Magic/version | `0x47524901` |
| 1 | Length in 32-bit words | 8 |
| 2 | Raster version mask | `0x6`: versions 1 and 2 |
| 3 | Features, depth formats and sample modes | See below |
| 4 | FP32 D16 resolution candidate | `0x37804001` |
| 5 | FP32 D24/D24S8 resolution candidate | `0x33c00000` |
| 6 | Floating depth-resolution policy | 3 |
| 7 | Reserved | 0 |

Word 3 bits 0–7 describe polygon offset, shader fragCoord depth, covered-sample
depth, shader-depth override bypass, line/point bypass, immutable depth-format
binding, bias before depth clamping, and inactive-depth bypass, respectively.
Bits 8–14 are a `TypedDepthFormat` mask: bits 1–5 are set for D16, D24, D32F,
D24S8 and D32FS8. Bits 16–18 encode supported 1x/2x/4x modes according to the
configured sample capacity. Reserved bits are zero.

Raster version 1 requires all offset fields to be zero. Version 2 accepts
finite signed factor/units and computes triangle bias as `m * factor + r *
units`, using the shared scalar service and the maximum absolute window-depth
slope. Bias is added to default center/sample depth before clamping. A shader
depth override replaces the biased default. Points, lines, no-depth and
stencil-only attachments bypass the bias. Reserved formats reject.

Floating policy 1 recorded the original RNE interpolation candidate. Policy 2
introduced directed final quantization but a binade-boundary RTL witness still
failed. Policy 3 bounds unbiased results at interior interpolation locations
to the active vertex-depth range before applying offset, retaining outside
helper extrapolation. It retains the same resolution and selects toward-zero final default-depth sums
and FP32 packing only for bound D32F/D32FS8 attachments. Fixed depth retains
RNE, preserving its qualified add/encode constants. Floating resolution chooses one binary32 ULP at the maximum post-viewport vertex
depth, with a minimum subnormal step of `2^-149`. Fixed policy uses words 4/5.
The [resolution record](POLYGON_DEPTH_RESOLUTION.md) qualifies the
post-interpolation add/encode envelope. Complete clipping/interpolation
precision and the full raster minimum-resolvable-difference contract remain
open. Software can read the exact policy without inferring a broader guarantee.

The generated native typed/binned C headers include all word indices, feature
masks, raster versions and policy IDs. Existing descriptor fields, record sizes,
GVI1/GFI1 words and shader instruction encodings retain their values. GRI1
writes fail even with zero strobes; AXI FIXED/INCR reads retain normal held-beat
semantics. Misaligned/aliased/unmapped reads and writes fail. The first address
after this block, `0x1f0`, remains unmapped.
