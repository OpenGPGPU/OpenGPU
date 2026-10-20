# Polygon depth-offset arithmetic client

This component is a prerequisite for the open G04 raster requirement. Internal
primitive raster integration now shares its scalar service and applies the
result to default depths as described below. Live/native callers still tie the
binding to zero at that checkpoint. Later [version-2 raster/attachment binding](POLYGON_OFFSET_NATIVE_BINDING.md)
is implemented with validation pending. GLES 3.0.6 section 3.6.2 permits the maximum absolute x/y plane
derivative as the slope approximation and combines slope and resolution terms.
Fixed and floating depth representations require different resolution policies.
[Official specification](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf)

`PolygonDepthOffset` snapshots identity, sequence, primitive, three signed
fixed-point screen positions, three FP32 window depths, enable/kind, signed FP32
factor/units and positive FP32 resolution. It computes pixel-scale x/y slopes
from the signed triangle area, selects the larger magnitude and returns an
extended `ClipFloat` offset. Each scalar operation uses the existing rounded
24-bit-significand contract. Keeping the extended exponent avoids premature
FP32 overflow or subnormal loss. Application and final depth clamping belong to
the future raster integration; shader-written depth must bypass this offset.

The module has tagged math request/response ports and owns no FPU, divider, CU
or SRAM. Its test harness supplies one existing `ClipFloatArithmetic` service.
A nondegenerate enabled polygon uses fifteen serialized operations. Point/line
and disabled requests bypass arithmetic. Degenerate polygons and jointly zero
factor/units return zero. Active polygon state rejects nonfinite coefficients,
out-of-range depths, nonpositive/invalid resolution and signed64-unrepresentable
area before issuing math. The area guard uses widened subtraction/products.

Cancellation retains an already offered request, accepts and drains its scalar
reply, and holds an immutable tagged completion before reuse. Status codes are
0 success, 1 malformed state, 4 cancellation and 7 numeric/range failure. Primary
faults survive subsequent cancellation. No request may borrow another owner's
math response.

The [three-check component regression](performance/mobile-polygon-depth-offset-2026-10-20.json)
passes on 630 unchanged source/ABI files. Eighty-seven exact arithmetic cases
include both windings, translated coordinates, mixed signs, a subnormal
resolution and a result beyond FP32's finite range. Nine malformed states,
bypasses, degeneracy, zero factors, wide area and healthy reuse are checked.
Held offered/accepted arithmetic and completions exercise cancellation without
premature retirement. Standalone emission has one active RTL file, with no
arithmetic lane or memory macro in its reachable hierarchy.

Completion requires the bound-format resolution policy, stable descriptor
binding, shared interpolation service and per-polygon lifetime, application
before default center/sample-depth clamping, and actual-CU/native image tests
including depth override, clipping, helpers, faults and reuse. Setup-cycle and
integrated synthesis measurements follow that wiring. No new PPA or rate result,
API support or H0–H8 closure is established by this component checkpoint.

The [candidate resolution selector](POLYGON_DEPTH_RESOLUTION.md) now has separate
format/rounding evidence, including exhaustive fixed-depth add/encode envelopes.
Its full raster precision and attachment binding remain pending.

## Primitive raster integration

The [42-check primitive regression](performance/mobile-polygon-offset-raster-2026-10-20.json)
passes on 638 unchanged source/ABI files. An internal `PolygonDepthOffsetBinding`
is snapshotted with each primitive. Enabled triangles compute the offset once
before raster launch; disabled and line/point paths bypass it. The primitive
owner routes tagged setup and interpolation requests exclusively through one
`ClipFloatArithmetic` service. Standalone primitive emission confirms exactly
one FMA lane and one divider, with one offset client and one interpolator and
no CU or SRAM.

Interpolation adds the extended offset to default center/sample depth before
clamping and packing. The same save path handles coherent covered-location
fallback. Zero offset adds no depth arithmetic. Twelve signed biased draws
across both windings and 1x/2x/4x match exact depth references; operation counts
prove fifteen setup operations per triangle plus one addition per evaluated
depth. Extrapolated helpers distinguish pre-clamp addition from biasing an
already clamped depth. Tests also cover culling, line bypass, bad bindings, three
setup cancellation cuts, held biased quads, immutable completion and healthy
reuse. Existing interpolation, centroid/pole, sample-depth and point/line checks
pass in the same run; the centroid/pole cases use zero bias. All four native ABI
checks remain byte-reproducible.

The first run passed 33/38. Four failures were missing optional defaults in an
older direct-raster harness; the fifth assumed positive zero for reverse-winding
helper varyings. Aggregate defaults and the existing bit-exact interpolation
reference correct those fixtures. No primitive-path RTL changed between runs;
the separately qualified policy selector and emitter entry were added. Both
frozen results are retained.

At that checkpoint, production live/prepared callers keep the internal binding zero.
Versioned raster descriptors, bound-format resolution/precision, actual-CU
fragCoord and shader depth override, clipped/centroid biased images, native
binned publication, full resource ownership and integrated rate/synthesis gates
remain required. The original H0–H8 scope is unchanged.

## Anchored slope candidate

The new candidate prepares `z1-z0` and `z2-z0` once per polygon and uses two
products, one sum and one division for each axis. This removes the repeated
common depth term and reduces normal setup from fifteen to thirteen scalar
operations, with no extra FPU, divider, CU or SRAM. Constant vertex depths must
give zero slope even when a large factor would amplify rounding residue.
The new 32-case semantic control and the broader numeric/ownership regression
are pending. Earlier fifteen-operation evidence remains scoped to its frozen
source; neither candidate closes full raster precision or H0–H8.
