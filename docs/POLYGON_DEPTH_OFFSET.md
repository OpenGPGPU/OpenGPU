# Deferred polygon depth-offset arithmetic candidate

This document records a development-branch prerequisite for the open G04 raster
requirement. The candidate is excluded from main: a later flat-depth witness
produced a nonzero slope. Its three original arithmetic-contract checks did not
cover that semantic defect. The candidate and its correction remain on
`codex/mobile-polygon-slope-precision` and `codex/mobile-polygon-slope-anchor`.
It was never connected to either production hierarchy in the centroid snapshot. The enclosing owner
must supply the depth attachment's resolution policy and apply its result to
default depths. GLES 3.0.6 section 3.6.2 permits the maximum absolute x/y plane
derivative as the slope approximation and combines slope and resolution terms.
Fixed and floating depth representations require different resolution policies.
[Official specification](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf)

The deferred `PolygonDepthOffset` candidate snapshots identity, sequence, primitive, three signed
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
