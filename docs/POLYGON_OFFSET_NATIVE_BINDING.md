# Polygon offset raster and attachment binding

This branch connects the primitive offset client to live and prepared draws.
Compatibility and actual-CU image regressions are running. Native feature
advertisement, full precision and H0–H8 closure remain open.

Raster version 1 requires the new enable/factor/units fields to be zero. Version
2 accepts finite FP32 factor/units and enables triangle offset; point/line
rasterization ignores it. Unknown versions and nonfinite coefficients reject
before primitive launch. Shader layouts and instruction encodings do not change.

GTD1 uses reserved words 540/541/542. GND1 appends the three fields at words
354/355/356. The layout audit preserves all prior 549 GTJ1/GTI1/GTD1 and 563
GNJ1/GNF1/GND1 field entries, previous C defines and fixed record sizes. C11
probes pass. GFS1 is unchanged: offset belongs to per-primitive raster state.
Window records append after the legacy prefix and retain the full-capacity
1,472-byte/23-line stride.

`TypedTileRenderPipeline` provides the immutable bound depth format. Shader and
live vertex/raster owners snapshot it at draw acceptance. Both window-primitive
routes derive candidate resolution from that format and post-viewport depths.
No depth or stencil-only means no effective offset; reserved formats reject.
Existing primitive validation rejects malformed depths. Untyped clients without
the optional format port retain a documented D24 default.

The primitive client computes once and shares the scalar FMA/divider with
interpolation. It biases default center/sample depth before clamping. Protected
metadata transports those depths through shaders; the late pixel selector uses
shader-written depth when override is enabled. Pending actual-CU tests read
fragCoord.z into MRT color, change coefficients while reusing GFS1 state, and
exercise signed default offsets, triangle override, line/point bypass and cached
guarded publication. These are intended behaviors until the tests complete.

Further work includes complete interpolation precision, all five depth formats,
biased centroid/perspective/clipping cases, native host image/fault/reset
qualification, accurate raster capability discovery, setup cycles and integrated
synthesis. The format policy remains a candidate. PPA remains synthesis only.
