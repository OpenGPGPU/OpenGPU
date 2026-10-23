# Full-width native affine interpolation

`NativeFullVaryingInterpolationHostAxiSpec` passes both local-pool and global
payload profiles through the actual typed AXI host. Each case renders one
three-vertex triangle at 1× and 4× MSAA. The four-CU configuration retains sixteen
lanes, eight warps, default instruction/vector caches and the default 64 KiB L2.
The viewport is 32×32; the raster region contains 128 unique pixel centers in a
16×8 rectangle. This is a full fragment batch, not a four-CU occupancy test.

All sixteen attribute bindings contribute to sixty-four smooth user components.
The first group carries clip positions; the remaining groups carry distinct
dyadic color ramps and biases. Unit reciprocal W makes their nonconstant affine
values independently computable at every pixel center. The host checks the exact
set of 128 coordinates without assuming lane order, then compares all sixty-four
staged components in every lane with the closed-form reference. Across four
frames this checks 32,768 interpolated component words.

The fragment shader explicitly loads every varying row and computes a rotate,
XOR and row-salted checksum. All 512 resulting lane checksums match independent
expectations. The raw depth output carries this checksum while depth override
is disabled. RGBA8 and D24S8 image samples, pitched allocation guards, vertex
attribute/output words and the preserved default control word retain separate
oracles. The cases cover 512 pixels and 1,280 pixel samples per attachment
across their four frames.

The enabled payload profile keeps its fixed 64 KiB pool and 16 KiB reservation
bound. Full 69,120-byte vertex and 45,056-byte fragment prefixes spill. Both VM
contexts remain pinned while completion is held; reservations and pins drain.
Both cases finish with zero failed, canceled, ignored or pending tests. Original
default-profile and structured-SIMT programs, configurations, prefix sizes and
registered cases remain unchanged.

The full-interface fixture allows two million service cycles, with an independent
200,000-cycle no-progress guard. Serial interpolation of thirty-two full-width
quads already accounts for about 955,000 cycles in the qualified component
fixture, before native staging, execution and drain. These test guards do not
establish a product frame budget. Opt-in `-Dopengpu.fullVaryingProgress=true`
prints diagnostic progress; raw logs remain local.

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' 'set Test / logBuffered := false' \
  'testOnly opengpu.graphics.NativeFullVaryingInterpolationHostAxiSpec'
```

This qualifies the stated affine numeric composition. Full-width nonunit-W
perspective cases, mixed centroid/flat qualifiers, full-capacity binned rendering,
limit rejection, general coherence, rates and an enclosing clock remain open.
The [uniform renderer checks](NATIVE_UNIFORM_RENDERER_CAPACITY.md) separately
cover 128 vertex invocations, GPU publication, faults and recovery, including
the four-CU mobile-cache configuration. API/compiler integration and the
remaining H0–H8 requirements are not closed by these cases.
