# Bounded texture image-level addressing

Status: isolated component candidate; four-check runtime qualification and
standalone RTL emission are pending. This implements address ownership needed
by general image views. It is not enabled by native GTX1 and does not complete
texture resource support.

`TextureImageAddressUnit` accepts a resolved mip-level view with each request.
The view and already selected integer texel coordinates are captured together.
Three elastic stages produce one address per cycle on an unstalled stream.
Accepted requests retain ordered, immutable results under backpressure. The
unit has no memory transactions or persistent cache state; a cancelling owner
must drain its accepted results.

| Target | Depth/layers | Selection | Active plane pitch |
|---|---|---|---|
| 2D | Both 1 | x/y; z, layer and face zero | None |
| 2D array | Depth 1; layers within the configured limit | x/y and layer; z and face zero | Layer pitch |
| Cube | Square x/y extent; depth/layers 1 | x/y and one of six faces; z and layer zero | Face pitch in the layer-pitch field |
| 3D | Depth within the configured limit; layers 1 | x/y/z; layer and face zero | Slice pitch |

Unused plane pitches must be zero. All targets have a row pitch. The default
component limits are 16383 for 2D/cube width and height, 256 for all three volume
axes, and 256 array layers; smaller limits are supported by the constructor.
These are component capacities, not a shader-visible capability advertisement.

Packed storage accepts 1, 2, 3, 4, 6, 8, 12 or 16 bytes per texel. A separate
4x4-block layout accepts 8 or 16 bytes per block and reports the texel's x/y
position inside that block. Addressing a block does not decode ETC2/EAC or
establish format legality. Arbitrary byte-aligned bases and pitched rows are
supported; the future fetch owner must gather a span that crosses memory beats
or cache lines. The selected mip number is preserved as metadata. Mip selection,
chain completeness and per-level table fetch remain owner responsibilities.

For a block width/height of 1 or 4, the level's row bytes are
`ceil(width / blockWidth) * blockBytes`. The minimum plane span is
`(ceil(height / blockHeight) - 1) * rowPitch + rowBytes`. Each active plane pitch
must cover that span, preventing row/slice/layer overlap. The allocation check
covers every plane of the level, even when the requested texel is in its first
row. Final row/plane padding is not part of the required allocation span.

Allocation bases and selected level bases are 32-bit byte addresses. Exclusive
limits and pitches have 33 bits; a limit of exactly 4 GiB is legal. The unit
validates full-width request coordinates before using bounded operands, retains
wide products/sums, and checks the complete span before narrowing a successful
address. Rejected requests preserve tag/mip metadata and return zero address,
byte count and block coordinates. Status priority is malformed view (1), invalid
coordinate (2), then bounds/layout failure (3); success is zero.

The GLES 3.0 image dimensions, array layers and six cube-face arrays motivate
these distinct targets; see sections 3.8.3 and 3.8.17 and the implementation
limits in the [GLES 3.0.6 specification](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf).
The pitch, allocation and fault conventions here are an OpenGPU hardware
contract, not GL command encodings or a conformance claim.

Next integration work includes versioned GPU-memory views and mip tables,
indexed per-stage bindings and context/generation ownership, format-aware
bounded fetch, coordinate conversion/wrap, cube selection and seamless edges,
3D filtering, sampler state, comparison, LOD operations, and native fault/VM
integration. These remain requirements of H4/H6. PPA evaluation is synthesis
only; no area, timing, power or cluster-rate result is available for this unit.
