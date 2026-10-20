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

The initial component checkpoint tied the enclosing primitive owner's mask to
zero. The native binding series now carries the fragment descriptor's mask
through both prepared replay and live vertex/raster ownership, snapshotting it
with each accepted draw. Actual-CU/native centroid images and composed timing
remain to be qualified; discovery is covered by the controls below. This checkpoint does not close
centroid/API or H6 coverage.

## Native descriptor extension

Fragment descriptor versions 3 and 4 enable the 64-bit component mask. Version
3 retains version 1's shader SoA layout; version 4 retains version 2's pointCoord
and primitive-kind layout. Versions 1/2 require a zero centroid mask. All
versions reject mask bits outside the active varying slots before shader work.

GTD1 places the mask in words 538–539 and GFS1 in words 124–125, previously
reserved. Header magic, record sizes, every existing field offset and narrow
padding rule remain unchanged. GND1 primitive/bin records do not change.
Generated C headers expose the new versions and mask offsets. An audit compares
all 354 existing GTD1 draw fields, 71 GFS1 state fields and existing C defines
against the preceding committed layouts; every prior entry matches, and C11
size/offset probes pass. All [15 runtime reader/raster compatibility checks](performance/mobile-centroid-native-binding-2026-10-20.json)
pass on 629 unchanged source/ABI files. The later 24-check source below adds
positive centroid-state and discovery controls; exact native images remain open.

## Native fragment input discovery

Native profiles exposing GVI1 now also expose the read-only GFI1 block at
`0x1c0..0x1cc`. Its four words contain magic `0x47464901`, word count 4,
descriptor-version bit mask `0x1e`, and feature/sample-mode flags. Feature bits
0/1/2/3 describe center, flat, centroid and coherent sample-location behavior.
Bits 8/9/10 describe 1x/2x/4x sampling supported by the configured profile.
Resource capacities remain in the existing profile record. Legacy profiles
leave GFI1 unmapped. Writes, including zero strobes, return SLVERR.

Positive GFS1/GTD1 controls exercise both new versions, the highest mask bits,
held descriptor offers, out-of-slot/old-version rejection and version-4 prefix
bounds. Discovery checks cover held FIXED/INCR reads, every denied word/strobe,
address aliases and 1/2/4-sample configurations plus the legacy map. The first
combined run passes 23/24 checks; its new triangle-owner case used the existing
point/line-only reference. The corrected fixture now selects the triangle
reference; the [complete 24-check rerun](performance/mobile-centroid-discovery-controls-2026-10-20.json)
passes on 631 unchanged source/ABI files. No RTL correction was required for
that fixture mismatch. Exact native centroid image checks remain pending.

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

The [expanded 18-check regression](performance/mobile-centroid-partial-perspective-2026-10-20.json)
passes on 628 unchanged source/ABI files. It includes sixteen negative/zero-center
mode/winding/qualifier cases, all existing interpolation/sample-depth controls,
and real two-CU local/global staging. All six sample choices across 2x/4x produce
exact matching shader fragCoord.xyzw, guarded uniforms, protected sample depths
and held exports. Three malformed metadata scenarios reject before shader access;
the mode-mismatch case also has an out-of-mode mask and does not isolate the
sample-index bound. The later binding/discovery controls above cover descriptor
transport. Native centroid images and prepared replay remain separate pending work.
