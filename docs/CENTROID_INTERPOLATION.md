# Centroid interpolation component

GLSL ES 3.00 section 4.3.9 permits a centroid smooth input to use a covered
sample for multisampling. Single-sample inputs use the center; flat centroid
inputs retain the provoking vertex's raw value. See the
[normative qualifier rules](https://registry.khronos.org/OpenGL/specs/es/3.0/GLSL_ES_Specification_3.00.pdf).

`FragmentInterpolationRequest.centroidMask` selects individual varying components.
For a covered multisample lane, the component chooses the lowest set coverage
bit and computes perspective weights at that sample. A second weight bank and
denominator preserve ordinary center interpolation in the same quad. Center
reciprocal W, center depth and each covered sample's default depth retain their
existing calculations. Point values are constant. Helpers retain center values;
flat bits bypass interpolation, including when their centroid bit is also set.

The existing owned scalar service computes the additional weights. There is no
new FPU, CU, SRAM, instruction encoding or shader SoA layout. Zero masks and
flat-only masks bypass centroid preparation. Masks beyond active varying slots,
invalid sample modes and out-of-mode coverage are rejected before output. The
request snapshots its mask/mode; accepted arithmetic drains before cancellation
completion, and an already offered quad remains immutable.

The initial controls compare against an independent absolute-sample coordinate
reference for mixed qualifiers, all coverage masks at 1x/2x/4x, both windings,
point/line/triangle values, helpers, raw flat NaNs, center/default sample depths,
64 components, malformed requests and cancellation/reuse. All
[13 checks across three suites](performance/mobile-centroid-interpolation-2026-10-20.json)
pass on 628 unchanged source/ABI files, including eight existing interpolation
and two existing covered-sample-depth controls. The new direct reference covers
50 quads and observes changed centroid values while preserving mixed center and
flat components. The initial reference-test syntax failure is retained separately.

This is a component prerequisite. The enclosing primitive owner currently ties
the new mask to zero. Versioned native fragment-state binding, actual-CU/native
images, resource discovery and composed timing are still required. This
checkpoint does not close centroid/API or H6 coverage.

## Partial-coverage perspective fallback

A tiny triangle can cover a sample while its pixel center extrapolates to a
negative or zero perspective denominator. A directed case initially returns
numeric fault 7 and drops that valid fragment. A candidate correction chooses
the lowest covered sample for every smooth input and for fragCoord.xyzw when
the center is invalid. It preserves the per-sample default depths and the
helper-center policy. Ordinary positive centers retain the prior behavior.

`Fp32InterpolatedLane.sampleLocationValid` and `sampleLocation` carry this internal
choice to shader staging. The existing x/y raster coordinates still identify
the pixel; shader x/y are sampled at the matching quarter-pixel offsets. The
existing scalar service recomputes reciprocal W and depth at that same position.
No shader SoA field or offset is added. GLSL ES 3.00 section 7.2 permits
multisample fragCoord at a sample location; section 4.3.9 permits ordinary smooth
inputs there as well. Staging rejects inactive, out-of-mode or uncovered sample
location metadata before any shader access.

The focused negative-center correction passes all eight mode/winding/qualifier
cases. Expanded zero-denominator, legacy interpolation/depth and actual shared-CU
local/global staging checks are running on a frozen combined source; their
passing result and native composition remain to be established.
