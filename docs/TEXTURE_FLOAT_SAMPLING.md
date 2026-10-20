# Floating-coordinate sampler development

The general sampler remains under development. Its first coordinate component,
`TextureFloatAxisPipeline`, converts one normalized FP32 axis and a selected
mip-level extent into nearest or linear texel coordinates and an FP32 weight.
This is standalone hardware; native shaders still use the existing legacy
sampler or integer-coordinate typed fetch. No new sampling capability is exposed.

The tap and wrap rules follow
[GLES 3.0.6, sections 3.8.9.1 and 3.8.10](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf).
For linear filtering, the underlying positions are `floor(u * N - 0.5)` and its
successor. The fractional part supplies the second tap's weight. Nearest uses
`floor(u * N)` and repeats that index with zero weight.

## Axis interface and arithmetic

Requests contain raw FP32 coordinate bits, a nonzero14-bit extent, a two-bit wrap
mode, a linear flag and a32-bit tag. Responses contain two signed16-bit indices,
the raw FP32 weight, status and the original tag.

| Mode | Meaning | Output index range |
| --- | --- | --- |
| 0 | Repeat | `0..N-1` |
| 1 | Clamp to edge | `0..N-1` |
| 2 | Mirrored repeat | `0..N-1` |
| 3 | Internal cube border clamping | `-1..N` |

Mode3 is an internal coordinate operation. GXS1 still rejects wrap mode3. A
future cube owner must choose the face, project the direction, select clamp to
edge for nearest and border clamping for linear, and map border taps to adjoining
faces. This unit does not implement those steps.

The implementation reduces exact binary periods before multiplying the24-bit
significand by the14-bit extent. It retains149 fractional bits through integer
tap selection, including the smallest FP32 subnormal. Negative values, integer
parity and huge finite exponents therefore cannot silently collapse to a
different texel. Weight conversion rounds to nearest, ties to even.

For clamp coordinates outside`[0,1]`, and border coordinates outside`[-1,2]`,
canonical endpoint coordinates generate the weight. Both selected taps coincide
in those regions, so this changes no filtered value. Other weights come from
the original exact fractional position. NaN, infinity and zero extent return
coordinate status5, zero indices and zero weight, retaining the caller tag.

Seven stages use two-entry queues to bound the ready path and retain accepted
requests through backpressure. With no stalls the latency is seven cycles and
the initiation interval is one axis per cycle. Up to14 requests can be buffered.
This arithmetic pipeline owns no memory transactions and has no cancel input;
the parent must drain all accepted tags before retiring cancelled work.

## Standalone evidence and remaining integration

The [qualification record](performance/mobile-float-axis-synthesis-2026-10-20.json)
records two passing tests with91,135 exact-rational reference queries. These
cover all256 FP32 exponent encodings, both signs, all16,383 nonzero extents,
representative significands, wrap/filter combinations, half-texel ULP neighbors,
random coordinates, rejected inputs, held output stability and ordered tags.
The dense stream checks the seven-cycle latency and one-cycle interval.

Fresh synthesis at1000ps, TC/SLVT,25% I/O, default ABC and no retiming passes
mapping and setup: core+194.895ps, I/O+632.673ps,19,046 mapped cells. Library cell
area is2229.52986 units; this is not physical die area. No route or power
qualification is claimed.

Required next work is the complete native floating sampling path: coordinate
packet and instruction transport, authoritative image/sampler admission,
selected-level metadata, array-layer rounding, cube projection and seamless
edges/corners, explicit/gradient/implicit LOD, min/mag and mip filtering, depth
comparison and post-filter image swizzle. Banked caches, miss joining,
publication/coherence, representative texture throughput and remaining H0–H8
requirements also remain open. This standalone axis does not close those gates.
