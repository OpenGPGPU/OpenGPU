# Spatial native fragment interpolation

`Fp32PrimitiveRasterStage` defaults to eight independent raw preparation owners
feeding four streaming component lanes. Eight credits cover preparation,
component evaluation, held output and completion. Each owner has four private
pixel-lane services, giving thirty-two preparation services per raster engine
and sixty-four across two engines. The coordinate queue uses the same context
count. Two-, four- and eight-context configurations remain supported.

Each owner captures its raw request once; its four lane children read that
immutable snapshot until common retirement. Common setup is counted once and
lane-major fault prefixes remain ordered. The two native tile engines share the
four-CU shader pool. Polygon offset has its own scalar service before raster
launch.

Polygon determinants, signed bounds, raster-origin products, sample gradients
and edge-conservation checks use explicit register stages. Point/line coverage
registers sample product partials, carry summaries and projected offsets.
Threshold setup and squared sample distance share an exact product pipeline.

Preparation may finish out of order; transfer and retirement remain ordered.
An eight-entry coordinate queue attributes faults to the correct accepted quad.
Cancellation stops new raw work and drains every reserved preparation result
and posted component request. Already offered quads and completions stay
immutable. Lane-prefix accounting preserves the scalar first error and exact
operation/flag totals, including an earlier component error before a later
preparation error. Flat NaN words, centroid qualifiers, helper lanes, covered
sample fallback, point coordinates and sample depths retain their contracts.
Cancellation may complete private sample sign/scale/carry scratch writes on the
abort clock; state, arithmetic admission and publication still abort immediately.
A 4x sampled preparation regression checks all 570 pre-result cancellation
boundaries, held results and bit-exact healthy reuse.

The bank evaluates four separately rounded weighted components each cycle and
issues a full four-pixel, 64-component quad in 64 clocks. The eight-context
fixture admits eight quads on consecutive clocks and sustains four complete
quads per 256 clocks, with a 272-clock regression bound. The four-context
configuration needs 389 clocks for the same four quads. Doubling preparation
capacity therefore increases this bounded rate by 52% and fills the component
bank's issue budget. Shader execution, payload traffic, attachments and
overdraw require separate rates; these measurements do not establish FPS.

## Validation scope

The eight-context profile passes nine targeted tests: three streaming-owner
checks and six primitive-raster checks. They cover all 64 components across
32 quads, eight consecutive admissions, rejected high-index contexts, ordered
retirement, held offers, cancellation, reset and healthy reuse. The existing
four-context/scalar checkpoint passes 34 tests across five suites, with zero
failed, canceled, ignored or pending cases. Its differential checks compare
all 64 components and exact arithmetic accounting against the scalar reference,
including centroid/depth fallback, first-error ordering, held offers,
cancellation, reset and reuse. Point/line checks additionally cover 2,401 directed
diamond-boundary lines, subpixel widths 2/4/8/16 and cancellation/reset at every
pre-offer cycle. Signed-product tests check full-width results, II=1, stalls and
reset. Host-script checks pass. Routine coverage stays at or below 640x480.

The common native image fixture passes two 1x/4x MSAA frames with eight
contexts and the two-transfer staging integration, using all 128 invocations,
one varying slot and four MRTs, with no payload spills and exact image guards.

Full-interface native qualification uses four CUs, sixteen lanes, eight warps, default
instruction/vector/L2 caches and a 512-byte SoA stride. Both local-enabled and global payload profiles pass with a fixed pool/spill
budget, eight contexts and the two-transfer staging integration. Real shaders
consume all 64 components and verify
512 checksums across 32,768 component values over four frames. Mixed flat/centroid masks include bits 60 and 63, raw flat NaNs,
non-unit W and partial 4x coverage. Aggregate addressed/covered samples are
1,280/1,278. These bounded images remain at or below 32x32.
The 128-thread fixture observes one active CU. Concurrent four-CU fragment
dispatch and full-capacity native binned rendering remain separate gates.

| Complete component | Core / I/O setup margin (ps) | Standard cells | Library area (µm²) |
|---|---:|---:|---:|
| Scalar arithmetic with compact packing | +159.873 / +487.250 | 15,212 | 2,036.77 |
| Four-lane raw quad preparation | +81.276 / +489.487 | 264,178 | 37,059.3 |
| Polygon depth-offset geometry and scalar client | +96.568 / +467.576 | 24,849 | 3,324.23 |
| Pixel-center triangle coverage and setup | +41.546 / +621.807 | 120,795 | 16,144.0 |
| Point/line coverage and setup | +91.658 / +592.985 | 137,741 | 18,838.6 |

The shared request and sample-scratch timing changes reduce preparation area
by 19.8% from the earlier sixteen-service mapping (46,211.5 µm²), preserving
the same arithmetic services and bounded quad rate.

All synthesis screens use 1000 ps, TC/SLVT and 25% I/O delay, with no retiming,
false paths, SRAM macros, skipped instances or unresolved mapping diagnostics.
These isolated estimates do not establish the complete engine, shared-CU/host
clock, placement/routing, aggregate area/power or 720p/30 FPS. The existing
[prepared-owner qualification](STREAMING_PIXEL_ARITHMETIC.md#owned-prepared-quads)
covers four contexts. The expanded eight-context owner requires fresh timing
and area qualification.

The complete eight-context raster/interpolation owner (`Fp32PrimitiveRasterStage`)
must pass its synthesis gate before merge. The qualified four-context baseline
passes +29.020/+477.735 ps with 2,041,453 cells and 292,126 µm² of library area. Its scope excludes shader
execution, payload transport and the attachment backend; it cannot qualify
the whole GPU clock or product frame rate.

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.Fp32FragmentInterpolatorSpec opengpu.graphics.Fp32FragmentPreparationSpec opengpu.graphics.Fp32StreamingFragmentInterpolatorSpec opengpu.graphics.Fp32StreamingFragmentDifferentialSpec opengpu.graphics.Fp32PrimitiveRasterStageSpec' \
  'testOnly opengpu.graphics.PointLineRasterizerSpec opengpu.util.PipelinedSignedProductSpec'
sbt -J-Xmx6G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.NativeFullMixedQualifierHostAxiSpec'
for ppa_case in \
  ClipFloatArithmetic:mobile-clip-float-arithmetic \
  Fp32FragmentPreparation:mobile-fp32-fragment-preparation \
  PolygonDepthOffset:mobile-polygon-depth-offset \
  TriangleRasterizer:mobile-fp32-center-raster \
  PointLineRasterizer:mobile-point-line-coverage \
  Fp32PrimitiveRasterStage:mobile-fp32-primitive-raster; do
  ppa_module="${ppa_case%%:*}"
  ppa_profile="${ppa_case#*:}"
  sbt "runMain opengpu.elaboration.EmitPpaRtl $ppa_profile .artifacts/$ppa_profile-rtl"
  CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
    ".artifacts/$ppa_profile-rtl" "$ppa_module" --stage synthesis \
    --period-ps 1000 --io-delay 0.25 --output ".artifacts/$ppa_profile-synthesis"
done
bash scripts/test_scripts.sh
```
