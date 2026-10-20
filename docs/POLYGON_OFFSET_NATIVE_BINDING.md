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

The [two focused native AXI host controls](performance/mobile-polygon-native-host-2026-10-20.json)
pass on the frozen 638-file snapshot, unchanged through the run. Each submits
live GTD1 draws through Sv32 with a bound D24S8
attachment, positive/negative coefficients at 1x/4x sampling, fragment versions
3/4, and fragCoord.z written into MRT color. The controls check source/resolve
bytes and guards, line/point bypass, uniform tails, prewarmed image consumers
and VM ownership during held completion. All 285 main and 341 test Scala
sources compile. Four total frames and twelve triangle/line/point draws pass.
GPU-memory binned publication, every depth format and the full compatibility,
fault/reset and precision matrix remain separate gates.

Four GPU-memory binned host controls are implemented and running on a separate
frozen 638-file snapshot. Physical GNC1 and translated-image GNC2 each exercise
local/global two-CU profiles, 2x/version-3 default depth and 4x/version-4 shader
override, signed D32F coefficients, shader fragCoord.z, MRT blending and exact
source/resolve/guard bytes. Fresh programs/vertices, GND1 records, GFS1 state,
cached consumers, uniform tails and held-completion VM ownership are checked.
The direct absolute-sample oracle applies the extended offset before packing.
This is pending test coverage, not a passing publication claim.

A separate pending native typed-host matrix now iterates D16, D24, D32F,
D24S8 and D32FS8, all three sample modes and both coefficient signs for each
local/global two-CU profile. Its 60 total frames check 180 live triangle/line/
point draws, shader fragCoord.z, exact format-specific backing and sample-zero
depth resolve, stencil/padding/guard bytes, uniform tails, cached consumers and
VM ownership. Each frame uses fresh image/vertex allocations. The frozen
638-file snapshot changes only the native-host fixture; no passing all-format
result is claimed until the running matrix completes.
