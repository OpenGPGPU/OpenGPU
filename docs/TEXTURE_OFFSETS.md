# Internal integer texture offsets

Status: the [799-input qualification](performance/mobile-texture-offsets-2026-10-21.json)
passes 124 distinct tests across 21 suites and nine unchanged ABI checks. Fresh
axis, resolver and full-sampler synthesis pass at 1000ps. Core/I/O slack is
+169.396/+632.673ps, +49.064/+477.467ps and +34.426/+411.929ps respectively.
The full sampler maps 438,950 cells with library area 60,891.212160. Five
standalone arithmetic profiles retain byte-identical positive synthesis.
No native instruction, compiler builtin or capability is added here.

The internal descriptor-to-texel sampler accepts three signed offsets with an
implemented range of -8 through +7, inclusive. It adds each offset to the integer
texel location at each selected mip, before repeat, mirrored repeat or edge
clamping. The offset does not change LOD, array layer, helper differences or the
cube Jacobian. Two-dimensional and array requests require a zero third offset;
cube requests require all offsets zero. Invalid requests fail before descriptor
reads. The selected-level owner also validates its independent trusted input.

This follows [GLES 3.0.6 section 3.8.10 and table 3.22](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf)
and [GLSL ES 3.00 section 8.8](https://registry.khronos.org/OpenGL/specs/es/3.0/GLSL_ES_Specification_3.00.pdf).
Offset expressions are compiler constants; this internal hardware endpoint
validates their range. Offsets are not supported for cube maps and do not apply
to the layer of an array texture.

## Exact coordinate arithmetic

The axis pipeline retains the original binary-rational coordinate and 149
fractional bits. Offsets never pass through FP32 addition to normalized
coordinates. Consequently even maximum finite coordinates retain the offset
when repeating or mirroring.

Periodic offsets are reduced modulo twice the extent. Extents below eight use
exact elaboration-time offset tables; larger extents require one conditional adjustment.
A separate elastic register stage reduces the shifted integer location before
final wrapping. Wide alignment and chunked signed arithmetic also have separate
registered cuts. The pipeline accepts one axis per cycle, with ten cycles of
unstalled latency and twenty buffered requests. Existing zero-offset results
remain unchanged apart from latency.

For edge clamping with a nonzero offset, the normalized coordinate is retained
until integer selection. Saturating it prematurely to [0,1] would lose legal
offsets that move outside coordinates back into the image. Coordinates outside
[-16,16] may safely saturate there because every permitted offset still clamps
to the same edge, even for an extent of one. Fractions remain exact within the
reachable interval; when both taps coincide their weighting cannot change the
sample. Zero-offset clamping retains its earlier fraction convention.

## Ownership and remaining work

The captured offset vector survives preparation, gradient resolution, shader
bias, both selected mip levels, held completion and cancellation. Array layers
are selected independently. Each mip uses its own dimensions and the same
integer offset. All accepted axis and memory operations retain their normal
drain ownership.

Native transport, compiler/validator support, integer texel-fetch offsets,
texture-size queries and projective lowering remain open, alongside the other
H0-H8 requirements. The fresh component synthesis screens qualify this changed RTL at the
original clock; earlier positive screens retain their own frozen source.
No whole-GPU clock, hold, power, routing or frame-rate result is claimed.
