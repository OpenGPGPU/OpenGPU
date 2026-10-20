# Gradient image sampling development

Status: the [795-input frozen revision](performance/mobile-gradient-sampling-2026-10-21.json)
passes116 distinct aggregate tests and nine ABI checks. All three arithmetic
synthesis screens pass at1000ps, but the composed resolver/full sampler map
completely and fail setup by13.617/68.892ps. This revision remains a development
checkpoint. The qualified explicit-effective-LOD baseline is on main in
[the main audit](performance/mobile-effective-lod-main-2026-10-20.json).
No new native shader instruction or software capability is exposed.

## Admission and sampling

`TextureImageSampleUnit` now accepts either explicit effective lambda or two
three-component FP32 gradient vectors. The internal `gradient` flag selects
the latter; its unused explicit-lambda bits must be zero. Malformed mode inputs
fail before descriptor reads. Existing explicit requests leave the flag clear.
Software GXI1/GXL1/GXS1 layouts and native integer fetch remain unchanged.

Real image/sampler admission resolves the captured base extent and immutable
mip table before `TextureImageGradientLodUnit` derives lambda. Two-dimensional
and array images use X/Y components; volume images use X/Y/Z. Array-layer
derivatives and unused Z values are ignored. Nonfinite used gradients fail with
coordinate status5 before texel reads. Cube gradients use the original direction
and the face selected by preparation. A face/major mismatch rejects the internal
snapshot rather than sampling with unrelated geometry.

The resolved lambda passes through existing sampler clamps, min/mag and mip
selection, spatial/mip filtering, depth comparison and final swizzle. Publication
and VM leases remain pinned by the enclosing sample/native owner until final
retirement. Accepted numerator, divide and logarithm work drains on cancellation;
held results remain immutable. This path does not supply implicit quad/helper
derivatives, shader bias or native instruction transport yet.

## Cube projection derivatives

For the selected face, let `ma` be the absolute major component and `sc`/`tc`
the signed face basis components. The implemented Jacobian is

```text
du = (ma*dsc - sc*dma) / (2*ma*ma)
dv = (ma*dtc - tc*dma) / (2*ma*ma)
```

The major derivative includes the selected direction's sign. Each screen
direction uses this same selected-face basis, including at seams and major-axis
ties. It does not project neighboring directions onto independently selected
faces and subtract their UVs.

`TextureGradientProductDifferenceUnit` forms two exact 24x24 products, aligns
their exponents with sticky information and rounds the signed difference once
to24 significand bits. Six extra alignment bits retain full cancellation
precision; for exponent gaps of two or more the larger term bounds cancellation.
This preserves proportional-vector zeros and tiny nonzero numerators that
separately rounded products would lose.

`TextureCubeGradientUnit` forms the squared denominator through the same owner,
then reuses one FP32 divider on normalized significands. Numerator, denominator
and quotient retain independent extended exponents. Its output is a finite
`TextureGradientScalar`, not an intermediate packed FP32 value. Focused checks
observe exponents down to -555 and above127 without range loss. Nonzero results
are checked against the exact rational Jacobian with relative error at most
`4 * 2^-24`; exact zero numerators stay zero. Nonfinite input gradients/directions
and an all-zero direction return status5.

## Gradient magnitude and logarithm

The norm approximation follows the permitted bounds and monotonicity conditions
in [GLES3.0.6 section3.8.10.1](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf).
For each used axis, gradients are scaled by the admitted base extent. The
implementation chooses the maximum absolute component across both screen
directions. This Chebyshev norm is continuous and nondecreasing in every absolute
component and lies within the required maximum/sum bounds. Relative to the ideal
Euclidean norm, this choice can reduce lambda by at most `log2(d)/2`, where `d`
is the number of used dimensions. This is the chosen permitted approximation,
separate from arithmetic rounding error.

`TextureGradientLodUnit` scales each24-bit significand by its14-bit extent with an
exact38-bit product, then compares normalized significands and extended exponents.
No intermediate FP32 multiply can silently overflow or underflow. Cube images
use two projected derivatives; volume images use three coordinate derivatives.
Zero magnitude produces raw negative infinity, allowing sampler clamps to apply
normally afterward.

Forty binary-log iterations operate on a Q1.39 normalized magnitude. Each square
uses two20x40 products and an exact80-bit prefix addition, followed by one
normalization/truncation. The accumulated logarithm error is bounded by
`2^-39 / ln(2) + 2^-40 < 4e-12` before final RNE FP32 packing. Exact powers of two
return exact integer lambda. The38-bit scaled input precision and40-bit log
resolution preserve the min/mag sign immediately around magnitude one.

The arithmetic owners serialize requests. Their latency and resource reuse are
functional design choices; they do not establish the H4 cluster throughput gate.

## Current verification

Focused checks pass:

- 17,390 exact-integer product/subtraction cases and14 cancellation cuts.
- 1,768 exact-rational cube Jacobian cases, all faces/ties, proportional zeros,
  extreme exponents and102 cancellation cuts.
- 3,207 magnitude/log cases,4,617 monotonicity cases and170 cancellation cuts.
- 238 real descriptor gradient samples, including base levels, cube faces, depth
  comparisons and sampler clamps;152 cancellation cuts with exact healthy reuse.
- Existing352 explicit samples and their late-fault/snapshot controls.

The initial logarithm test passed arithmetic but failed its coverage assertion:
larger gradients hid the intended tiny peak. Explicit tiny-only cases correct
the fixture; the failed log is retained at
`.artifacts/mobile-gradient-lod-v1.log`, followed by the passing focused V2 log.
The fresh aggregate/RTL source archive is keyed by code commit `e97f270`.
Standalone product/cube/logarithm core margins are+0.301/+2.028/+69.630ps.
Composed failures trace raw/subnormal FP32 decoding into24x24 multiplication.
The following `codex/mobile-implicit-sampling` revision registers decoded operands
and adds extended helper differences and shader bias; it needs fresh qualification.
Native floating transport, implicit derivatives, banking/coherence and all
remaining H0-H8 requirements stay open.
