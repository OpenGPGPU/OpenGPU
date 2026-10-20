# Internal implicit LOD and helper snapshots

Status: functional implementation on `codex/mobile-implicit-sampling`.
Focused arithmetic and descriptor-to-texel tests pass; fresh aggregate and
synthesis qualification follow. The earlier explicit-gradient revision's
[116-test record and composed timing failures](performance/mobile-gradient-sampling-2026-10-21.json)
remain immutable. Main retains its separately qualified explicit-effective-LOD
sampler. No new native opcode, software record or capability is exposed here.

## Request lifetime

`TextureImageSampleRequest.implicitLod` selects fragment implicit sampling.
The request supplies four original FP32 coordinate vectors in TL/TR/BL/BR order,
a two-bit selected lane, a complete four-bit validity mask, and shader bias.
Preparation's selected coordinate must match the corresponding helper vector
on every used axis. Explicit lambda is unused and must have zero bits.
Explicit-gradient and implicit flags cannot both be set. Bias is legal only in
implicit mode. Invalid mode/snapshot fields fail before descriptor reads.

The helper snapshot contains the shader expression's values, including uncovered
helpers. Its validity is independent of framebuffer coverage and side-effect
masks. All four helper values are required; the owner cannot substitute zero
for a missing neighbor. Used nonfinite coordinates fail before texel reads.
Unused Z values are ignored for2D/array targets. Cube and volume targets use
all three components.

`TextureQuadGradientUnit` computes the selected pixel's fine differences:

| Lane | X difference | Y difference |
| --- | --- | --- |
| TL | TR - TL | BL - TL |
| TR | TR - TL | BR - TR |
| BL | BR - BL | BL - TL |
| BR | BR - BL | BR - TR |

Each difference has one RNE significand rounding and retains an extended
exponent. Opposite maximum finite coordinates yield a finite exponent128
derivative, while minimum-subnormal differences and exact zeros remain intact.
The method is a local forward/backward difference consistent with
[GLSL ES3.00 section8.9](https://registry.khronos.org/OpenGL/specs/es/3.0/GLSL_ES_Specification_3.00.pdf).
Native shader/raster integration must actually provide these complete values;
this internal interface alone does not prove helper execution under divergence.

## Extended cube gradients and bias

`TextureCubeGradientUnit` accepts either raw explicit gradients or normalized
extended gradients. The extended form covers unbiased exponents[-149,129], with
zero encoded by zero significand/exponent. This carries finite quad differences
past FP32 overflow into the original-direction cube Jacobian. It does not
differentiate independently projected neighboring faces.

The product-difference owner now accepts internal power-of-two offsets for
normalized operands. Effective nonzero operand exponents must lie in[-512,511].
Ordinary raw FP32 callers leave all offsets zero. Decoding and exponent validation
precede registered normalized operands; multiplication runs in the next state.
This cuts the actual raw/subnormal-decode-to-product path that missed setup in
the earlier resolver and complete sampler.

The extended gradients feed the admitted-base-extent magnitude/logarithm owner.
`TextureLodBiasUnit` clamps shader bias to the internal implementation limit
`[-16,+16]`, adds it with one RNE FP32 operation, and only then passes lambda to
the sampler's min/max clamps. Zero gradients produce negative infinity and
remain so after every permitted bias. NaN bias is rejected; infinite bias clamps
to an endpoint. Bias is not applied to explicit lambda or explicit gradients.

The complete owner retains image/sampler snapshots, helper coordinates and all
child tags through retirement. Cancellation drains accepted quad arithmetic,
cube products/divides, logarithm, bias addition and texel transactions. Errors
publish no partial RGBA; held results stay immutable. A standalone vertex lookup
without explicit LOD still requires a separate native stage policy selecting
lambda zero; this fragment helper mode does not replace that policy.

## Focused evidence and remaining work

Eight arithmetic tests pass with19,440 product-difference cases,1,776 cube
Jacobian cases,3,956 quad cases,4,265 bias cases and their cancellation/reuse
controls. All eight composed sampling tests pass:493 implicit cases span four
targets, all quad lanes, six cube faces, shadow filtering, base levels, bias
ordering, complete snapshots and finite-difference overflow. Another122 implicit
cancellation cuts pass, retaining the previous238 explicit-gradient cases,
152 gradient cuts and352 explicit-lambda samples. These are focused results;
aggregate source identity and setup timing need fresh qualification.

Native scalar/vector transport, compiler/validator updates, actual CU/raster
helper integration, vertex automatic LOD, offsets/queries and the remaining
texture-operation matrix remain open. The final common path must honor H5's
on-chip payload requirement rather than require new per-fragment global packet
staging. Banked cache throughput, coherence and the original full H0-H8 gates
remain required. PPA evaluation is synthesis only.
