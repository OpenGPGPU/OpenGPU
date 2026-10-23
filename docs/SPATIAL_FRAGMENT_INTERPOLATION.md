# Spatial native fragment interpolation

`Fp32PrimitiveRasterStage` connects four independent
`Fp32FragmentPreparation` owners to the four-lane
`Fp32PreparedQuadInterpolator`. Four raw-input credits cover preparation,
component evaluation, held output and completion. The two native tile engines
retain the shared four-CU shader pool. Polygon offset has its own scalar
service, computed before raster launch.

Preparation may finish out of order; transfer and retirement remain ordered.
A four-entry coordinate queue attributes faults to the correct accepted quad.
Cancellation stops new raw work and drains every reserved preparation result
and posted component request. Already offered quads and completions stay
immutable. Lane-prefix accounting preserves the scalar first error and exact
operation/flag totals, including an earlier component error before a later
preparation error. Flat NaN words, centroid qualifiers, helper lanes, covered
sample fallback, point coordinates and sample depths retain their contracts.

The bank evaluates four separately rounded weighted components each cycle.
It issues a full four-pixel, 64-component quad in 64 clocks. Raw preparation
remains the limiting stage: the bounded non-power-of-two setup test admits four
quads on consecutive clocks, then sustains four complete quads per 1,030 clocks
with a 1,040-clock regression bound. That test excludes shader execution,
attachment traffic and representative overdraw; it is not a product FPS result.

## Validation scope

93 distinct tests across 15 suites pass with zero failed, canceled, ignored or
pending cases. This includes scalar arithmetic and all four clients, exact
compact packing, preparation/owner comparisons, points/lines/triangles, random
coverage and interpolation, cancellation/reset/reuse, signed integer boundaries
and the minimum one-slot row-tag configuration with four contexts.

The full native GPU checks use four CUs, sixteen lanes, eight warps, default
instruction/vector/L2 caches and a 512-byte SoA stride. Both local-enabled and
global payload profiles pass with a fixed pool/spill budget. Real shaders
consume all 64 components and verify 512 checksums across 32,768 component
values. Mixed flat/centroid masks include bits 60 and 63, raw flat NaNs,
non-unit W and partial 4x coverage. Aggregate addressed/covered samples are
1,280/1,278. These bounded images remain at or below 32x32.

| Complete component | Core / I/O setup margin (ps) | Standard cells | Library area (µm²) |
|---|---:|---:|---:|
| Scalar arithmetic with compact packing | +159.873 / +487.250 | 15,212 | 2,036.77 |
| Raw quad preparation | +17.915 / +481.396 | 120,372 | 18,483.1 |

Both synthesis screens use 1000 ps, TC/SLVT and 25% I/O delay, with no retiming,
false paths, SRAM macros, skipped instances or unresolved mapping diagnostics.
These isolated estimates do not establish the complete engine, shared-CU/host
clock, placement/routing, aggregate area/power or 720p/30 FPS. The existing
[prepared-owner qualification](STREAMING_PIXEL_ARITHMETIC.md#owned-prepared-quads)
retains identical generated logic after excluding source comments.

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.ClipFloatArithmeticSpec opengpu.graphics.ClipFloatCompactPackingSpec opengpu.graphics.PrimitiveClipStageSpec opengpu.graphics.Fp32ViewportStageSpec opengpu.graphics.PolygonDepthOffsetSpec opengpu.graphics.Fp32FragmentInterpolatorSpec opengpu.graphics.Fp32FragmentPreparationSpec opengpu.graphics.Fp32StreamingFragmentInterpolatorSpec opengpu.graphics.Fp32StreamingFragmentDifferentialSpec opengpu.graphics.Fp32PrimitiveRasterStageSpec opengpu.graphics.Fp32TriangleRasterStageSpec opengpu.util.BlockPrefixAdderSpec opengpu.util.CarrySavePrefixSumSpec' \
  'testOnly opengpu.graphics.Fp32PreparedQuadInterpolatorSpec -- -z "minimum one-slot"'
sbt -J-Xmx6G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.NativeFullMixedQualifierHostAxiSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-fp32-fragment-preparation .artifacts/fragment-preparation-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/fragment-preparation-rtl Fp32FragmentPreparation --stage synthesis \
  --period-ps 1000 --io-delay 0.25 --output .artifacts/fragment-preparation-synthesis
```
