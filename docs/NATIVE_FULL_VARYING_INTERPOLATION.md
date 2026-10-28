# Full-width native interpolation

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

`NativeFullPerspectiveVaryingHostAxiSpec` separately passes the same two payload
profiles with homogeneous W values 1, 2 and 4. Scaling each clip position by its
W preserves the projected triangle while requiring nonconstant perspective
interpolation. All source attributes are exact integers divided by 64. Integer
barycentric weights include reciprocal W; their products and sums fit 24
significant bits, so an independent rational quotient supplies the exact final
RNE result without reproducing the RTL's operation sequence. Directed witnesses
ensure this oracle differs from affine interpolation and produces nonconstant
interpolated clip W.

The perspective cases check all 32,768 staged component words, all 512 shader
checksums and all 512 fragment reciprocal-W words across four frames. They also
retain the separate vertex input/output, RGBA8/D24S8 image, guard, VM lifetime
and payload-drain checks, totaling 512 pixels and 1,280 samples per attachment.
The default, structured-SIMT and affine fixtures retain their original programs,
configurations, prefix sizes and registered cases. The perspective fixture uses
the same full-width shader programs and hardware configuration. Both new cases
complete with zero failed, canceled, ignored or pending tests.

The retained `NativeFullMixedQualifierHostAxiSpec` run also passes both
payload profiles, with one 1× and one 4× frame per profile. Its fixture is
unchanged from published commit `6470673e`. It retains W=1/2/4 and all sixty-four
components, adding flat components 5/60, centroid components 3/4/5/60/63 and
fragment descriptor version 3. Flat selection wins at overlapping qualifier
bits and preserves the provoking vertex's raw NaN payload at component 60.
Component 63 checks the highest centroid-mask bit; fragment reciprocal W stays
at the center. A partial corner excludes one 4× sample in each profile.

Across its four frames, the retained run checks 32,768 staged component words,
512 shader checksums and 512 fragment reciprocal-W words. Each profile addresses
640 attachment samples, of which 639 are covered; guards and uncovered samples
retain their independent image oracle. VM pins and fixed-budget payload leases
drain after held completion. These are completed historical runtime results;
unchanged fixture source does not qualify newer enclosing hardware changes.

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
  'testOnly opengpu.graphics.NativeFullVaryingInterpolationHostAxiSpec opengpu.graphics.NativeFullPerspectiveVaryingHostAxiSpec opengpu.graphics.NativeFullMixedQualifierHostAxiSpec'
```

Run the selected suite explicitly outside regular PR/nightly lanes. The retained
results qualify the stated affine, W=1/2/4 perspective and mixed-qualifier
fixtures. Current-hardware requalification, broader qualifier/precision cases,
full-capacity binned rendering, limit rejection, general coherence, rates and
an enclosing clock remain open.
The [uniform renderer checks](NATIVE_UNIFORM_RENDERER_CAPACITY.md) separately
cover 128 vertex invocations, GPU publication, faults and recovery, including
the four-CU mobile-cache configuration. API/compiler integration and the
remaining H0–H8 requirements are not closed by these cases.
