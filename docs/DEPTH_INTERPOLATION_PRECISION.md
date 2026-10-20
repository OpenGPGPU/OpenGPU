# Raster depth-resolution witness

[GLES 3.0.6 section 3.6.2](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf#page=120)
defines depth resolution through polygon rasterization and depth-buffer
conversion. The existing add/encode envelope is only part of that requirement.

A scalar binary32 replay of the current weighted-depth sequence finds a
constant-depth witness with eight subpixel bits:

- Vertices: `(-1239639,-17329276)`, `(2479278,-17329276)`,
  `(-1239639,34658552)`.
- Signed area: `193338417342276`; integer spans and area fit the existing
  triangle admission bounds.
- At pixel `(0,0)`, center edges are `64439008650732`, `64452793556076`,
  `64446615135468`.
- All three vertex depths are either `0x3f000001` or `0x3f000002`. Their
  difference is `0x33800000` (`2^-24`), one resolution step with the same
  binary32 exponent on both triangles.
- The software replay produces `0x3f000001` for both center depths.

The replay rounds edge conversion, each product, each sum and division
separately. The [actual RTL run](performance/mobile-depth-resolution-raster-failure-2026-10-20.json)
reproduces the collapse: 0/1 tests pass, and both observed center depths are
`0x3f000001`. The failing pair is retained on an unchanged source snapshot.
The new `Fp32DepthResolutionRasterSpec` uses actual primitive coverage,
single-pixel scissoring, immutable held quads and healthy reuse. It requires
distinct center depth and exact constant center/helper/covered-sample depth in
both windings and all three sample modes. No offset or clamping is involved.

The frozen 640-file reproduction preserves all combined production/ABI bytes.
RTL reproduction confirms the first pair; later windings/sample modes are not
reached by that failing run. No passing precision or full G04/H6 result is
claimed. Any arithmetic correction must rerun the relevant numeric, ownership,
image and combined-source checks before integration.

A second software witness uses integer-pixel vertices `(-262,-131)`,
`(524,-131)`, `(-262,262)`, fitting a viewport rectangle of 786x393 pixels.
Its scalar replay collapses the same source pair to `0x3f000002`. The expanded
RTL test tries this smaller geometry first, then the retained large-edge case;
the [second frozen RTL run](performance/mobile-depth-resolution-viewport-failure-2026-10-20.json)
also fails at that first pair, with both center depths equal to `0x3f000002`.

The implemented candidate correction anchors depth at vertex zero:
`z = z0 + (e1*(z1-z0) + e2*(z2-z0))/area`. This avoids repeatedly multiplying
and summing the common depth term. Two differences are prepared once per quad
through the existing scalar service; center, covered samples and centroid
fallback share that depth path. Offset-before-clamp and cancellation ownership
remain in the same owner. A second candidate anchors polygon derivatives using
the same two depth differences. It requires thirteen setup operations instead
of fifteen and retains the external scalar service. A 32-case control checks
zero slope at four constant depth levels, both windings, opposite large factors
and preserved resolution terms. Its before/after RTL checks and the full
numeric/ownership and integrated qualification remain pending; negative
reproductions do not qualify either correction.

## Flat-polygon slope witness

The [standalone RTL control](performance/mobile-polygon-flat-slope-failure-2026-10-20.json)
also fails for constant depth `0x3f000001`, with pixel vertices `(0,0)`,
`(500,100)`, `(100,400)`, factor `2^20` and zero units. The weighted derivative
leaves rounding residue; RTL returns extended offset `E(false,-15,11573807)`,
about `4.21e-5`, instead of zero. The failing 640-file source is retained.
The planned 32-case test stops at its first case in this run. An anchored
slope correction is separate pending work and needs full native integration.

## First anchored-depth regression

The [first anchored run](performance/mobile-depth-interpolation-anchor-v1-2026-10-20.json)
passes the twelve constant-depth pairs and all four ABI checks. The numeric/
ownership group is 42/43: its full64-component test still expected1584 scalar
operations, while the changed schedule uses1582. Two shared differences plus
four lanes of six weight, five depth and384 varying operations give1582.
The corrected expectation changes no production bytes; the full44-check rerun
is pending. This does not establish full precision or integrated closure.


## Directed scalar quantization prerequisite (2026-10-20)

The [seven-check scalar regression](performance/mobile-depth-quantization-scalar-2026-10-20.json)
passes on 640 unchanged source/ABI files: 700 directed exact-rational cases,
904 legacy RNE arithmetic cases, 2065 codec words, ownership/cancellation,
four ABI checks and scalar emission. Internal operation4 reuses the existing
FMA toward-zero mode and directed gradual FP32 packing, including far-operand
sign/sticky handling and finite overflow saturation. Operations0–3 retain RNE.
One FMA and one divider remain; no CU or SRAM is introduced.

No raster caller selects this operation yet. A future bound-format policy must
keep fixed-depth resolution assumptions distinct from floating depth, and
repeat the actual sloped-depth witness plus numeric/image/ownership controls.
This component result does not resolve that failing witness or close G04/H6,
full precision, PPA or H0–H8.


## Interior binade-boundary witness and range candidate

Final toward-zero addition alone still collapses a pair to `0x3f000000` in
actual RTL. Relative raw coordinates are `(-90880,-58597)`, `(90880,-58597)`,
`(-60947,39297)` around pixel-center `(0,0)`, translated by `(128,128)`
for raster pixel `(0,0)`. The area is 17793213440 and exact edges are
`[1,7142622719,10650590720]`: the center is strictly inside. Source depths
`[3e000001,3efffffe,3efffffe]` and `[3e000003,3effffff,3effffff]` differ
by exactly `2^-25` and retain the same maximum exponent. The negative frozen
run remains on `codex/mobile-depth-directed-boundary`.

The new candidate bounds the unbiased final depth to the active vertex-depth
range only when all three signed edge weights have the area's sign or zero.
Comparison uses the extended exponent/significand so negative subnormals cannot
escape through negative-zero FP32 packing. Bounds precede polygon bias.
Outside helper centers retain extrapolation, and fixed formats retain RNE.
This adds comparisons and policy state, with no new FPU/divider or math calls.
GRI1 policy3 distinguishes the bounded candidate from the failed policy2;
header constants1 and2 remain available. Qualification is pending.
