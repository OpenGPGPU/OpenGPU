# Floating-coordinate sampler development

The general sampler remains under development. `TextureImageSampleUnit` now
connects GPU-memory image/sampler admission to floating-coordinate sampling at an
explicit effective LOD. It includes mip selection, array layers, cube projection,
depth comparison, spatial/mip filtering and final swizzle. Native shaders still
use the existing legacy sampler or integer-coordinate typed fetch. No new native
sampling capability is exposed.

The tap and wrap rules follow
[GLES 3.0.6, sections 3.8.9.1 and 3.8.10](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf).
For linear filtering, the underlying positions are `floor(u * N - 0.5)` and its
successor. The fractional part supplies the second tap's weight. Nearest uses
`floor(u * N)` and repeats that index with zero weight.

## Axis interface and arithmetic

Requests contain raw FP32 coordinate bits, a nonzero 14-bit extent, a two-bit wrap
mode, a linear flag and a 32-bit tag. Responses contain two signed 16-bit indices,
the raw FP32 weight, status and the original tag.

| Mode | Meaning | Output index range |
| --- | --- | --- |
| 0 | Repeat | `0..N-1` |
| 1 | Clamp to edge | `0..N-1` |
| 2 | Mirrored repeat | `0..N-1` |
| 3 | Internal cube border clamping | `-1..N` |

Mode 3 is an internal coordinate operation. GXS1 still rejects wrap mode3.
`TextureCubeProjectionUnit` chooses the face and projects the direction; the
selected-level owner chooses clamp to edge for nearest or border clamping for
linear and maps border taps to adjoining faces. The axis itself remains
independent of cube face selection.

The implementation reduces exact binary periods before multiplying the 24-bit
significand by the 14-bit extent. It retains 149 fractional bits through integer
tap selection, including the smallest FP32 subnormal. Negative values, integer
parity and huge finite exponents therefore cannot silently collapse to a
different texel. Weight conversion rounds to nearest, ties to even.

For clamp coordinates outside`[0,1]`, and border coordinates outside`[-1,2]`,
canonical endpoint coordinates generate the weight. Both selected taps coincide
in those regions, so this changes no filtered value. Other weights come from
the original exact fractional position. NaN, infinity and zero extent return
coordinate status 5, zero indices and zero weight, retaining the caller tag.

Ten stages use two-entry queues to bound the ready path and retain accepted
requests through backpressure. With no stalls the latency is ten cycles and
the initiation interval is one axis per cycle. Up to 20 requests can be buffered.
Separate alignment and chunked signed-arithmetic stages bound the wide path;
see [arithmetic pipelining](VERTEX_INPUT_PIPELINING.md) and
[texture offset support](TEXTURE_OFFSETS.md).
This arithmetic pipeline owns no memory transactions and has no cancel input;
the parent must drain all accepted tags before retiring cancelled work.

## Base-level admission

The internal image and image/sampler requests now have `selectBaseLevel`. A
sampling owner sets it with explicit level zero; admission selects the actual
captured GXI1 base level, returns its extent and preserves sampler-derived mip
completeness. The resolved level also follows bound fetches and data faults.
This avoids guessing level zero before LOD calculation. Existing integer fetch
callers leave the flag clear and retain explicit full-width level checks.

The mode participates in image cache identity. Nonzero unused explicit levels
are rejected before descriptor reads. Nonmip base selection avoids a redundant
base validation walk. The focused checks cover all 16 base levels, all targets,
swizzle, cache separation, data faults and all six sampler minification filters.
The image/sampler/native regression passes 41 tests; focused reruns add checks
for a cache collision at the same raw level and fault reporting after selection.
These are internal interfaces; software record layouts and capability words do
not change. General sampling admission now uses internal `sampling` and `shadow`
flags. Color images ignore comparison enable, depth shadow reads require it, and
nonshadow depth/integer/raw-FP32 reads retain their nearest-only policy. Existing
explicit integer fetch admission remains comparison-free.

## Captured-view fetch

The typed and raw fetch owners have an optional internal captured-view input.
In this configuration they snapshot the admitted GXL1 view with the request and
compute checked addresses directly. They never reread descriptor memory while
fetching taps. The default configuration retains the existing descriptor reader.
There is no software layout or capability change.

The shared address pipeline still checks the complete allocation, pitches and
coordinates. Data fills retain their existing two logical IDs, exact byte
gathering, cross-page permission checks and cancellation drain. Captured-view
checks cover all 64 formats at four offsets, poisoned descriptor memory, an
unmapped descriptor page under Sv32, invalid layouts, held offers and 80
cancellation cuts. A bounds-status expectation in the initial drain fixture was
corrected; its focused rerun passes without a production change.

## Selected-level sampling owner

`TextureImageLevelSampleUnit` now composes coordinates, captured-view fetch and
FP32 filtering for one admitted level. It snapshots image/sampler state and
supports nearest values of every storage kind, filtered color formats, 2D/array/
3D addressing and seamless cube edge taps. A cube double-border corner uses the
current face's available corner texel, a deterministic construction permitted by
GLES section3.8.9.1. The parent still projects cube directions and selects LOD.

Only nonzero-weight taps are fetched, and identical texel locations within a
sample share the decoded value. Required reads remain checked even for constant
swizzles or comparison functions. Depth comparison uses GXS1 function order and
occurs before filtering. Fixed-depth references clamp to[0,1]; floating-depth
references do not clamp. NaNs retain unordered comparison behavior. Swizzle can
be applied after the level filter or deferred until a parent blends mip levels.

Accepted coordinate, fetch and FMA work drains before cancellation completion;
held results retain their values. This is a functional selected-level owner,
not a native shader instruction or a qualified texture-cluster throughput path.
The [selected-level qualification](performance/mobile-level-sampling-2026-10-20.json)
passes 67 distinct targeted tests and nine ABI checks against 780 committed
inputs. It includes 394 format/wrap samples, 384 cube face/edge/corner samples,
17,247 cube mappings and 896 depth comparison samples. The captured-fetch,
axis, filter, base-admission and existing native integer-fetch controls also
pass. An initial shadow test caught fixed-depth NaN references being clamped to
1.0; the corrected clamp preserves unordered comparisons. Both the initial
failure and the diagnostic are retained alongside the final passing evidence.

Three fresh [synthesis screens](performance/mobile-level-sampling-synthesis-2026-10-20.json)
pass full mapping and setup at 1000 ps, TC/SLVT, 25% I/O, default ABC and no
retiming:

| Profile | Core margin (ps) | I/O margin (ps) | Mapped cells |
| --- | ---: | ---: | ---: |
| FP32 filter | +350.767 | +471.902 | 22,308 |
| Captured typed fetch | +211.925 | +439.266 | 58,422 |
| Selected-level sampler | +82.172 | +440.036 | 99,878 |

At that frozen selected-level revision, source and emitted RTL inputs remain
exact. Its fresh emission also proves the
standalone axis RTL byte identical to its earlier passing synthesis screen;
that carry does not qualify other owners whose source changed.

## Explicit effective-LOD sampling

`TextureImageSampleUnit` snapshots a preparation request plus raw FP32 effective
lambda. Preparation supplies independent image/sampler tables and handles,
expected numeric kind, declared target, three FP32 coordinates, FP32 array layer,
comparison reference, shadow/swizzle flags and tag. The parent pins publication
and address translation until the final result retires. Software GXI1/GXL1/GXS1
records and native instruction encodings are unchanged.

Admission captures the actual base level and every mip required by the sampler.
`levelMask` identifies validated views; all other slots are zero, including on
errors. Cache hits retain the complete immutable mip snapshot. Sampling each
selected level uses the captured view, so neither the second mip nor individual
taps reread descriptor memory. Rebinding or publication still requires explicit
invalidation even when handles and identity are reused.

Sampler LOD clamps apply before min/mag selection. Lambda greater than zero
selects minification; zero and negative lambda select magnification. Nonmip
filters use the admitted base. Nearest-mip half ties choose the lower mip;
mip-linear uses the floor and successor with the original fractional weight.
Selection saturates at the admitted last level. Reversed sampler clamps are
undefined in GLES; this internal contract deterministically lets the upper clamp
win. NaN lambda is rejected with coordinate status5; infinities may be clamped.
Lower/upper clamps, integer extraction, fractional normalization, selection and
level-mask validation use separate registered states. Only the four FP32
exponents for integer lambda below16 need fixed-slice extraction; larger values
saturate before those parts are used. This preserves tiny fractional weights
and avoids a combined clamp/normalize/select timing path.

Array layers use `floor(layer + 0.5)` before clamping to the layer count; huge
finite values saturate without narrowing overflow. Nonfinite array layers are
rejected. Other targets ignore the layer operand. Cube directions must be finite
and nonzero. Absolute-major ties select X, then Y, then Z. Two owned RNE divisions
produce the face ratios, followed by a fused-affine-equivalent RNE conversion to
normalized coordinates. Nearest/linear cube tap behavior remains as described
above. Unused nonvolume/noncube Z coordinates do not participate in validation.

One selected-level owner and its FMA are reused across both mips. Depth comparison
precedes spatial and mip filtering. Swizzle runs once after the last filter.
Zero-weight second mips are skipped, but constant swizzles and comparison
functions do not suppress required faulting texel reads. First faults survive
later cancellation; failed samples publish zero RGBA. Accepted descriptor, axis,
divide, fetch and filter work drains before completion. Held results are immutable.

The outer memory port has16 logical IDs: preparation uses0..4, selected-level
sampling maps its IDs to8..11, and reserved responses are backpressured. Actual
texel fills currently use10/11. These are internal routing IDs, not a promise of
16 concurrent fills. The owner serializes sample requests and establishes no
texture-cluster throughput rate.

Full-exponent cube tests exposed shared FP32 divider defects in subnormal input
normalization and underflow guard/sticky formation. The corrected divider also
honors directed overflow rounding and exception flags, and reports busy across
all accepted pipeline stages. Its finite arithmetic regression uses exact
rational division over7,126 queries and all five rounding modes. Cube tests use
independent host division and fused affine arithmetic, including100 cancellation
cuts. The [complete-owner qualification](performance/mobile-effective-lod-sampling-2026-10-20.json)
contains 107 distinct tests and nine ABI checks on 788 frozen inputs.
Two fresh [synthesis screens](performance/mobile-effective-lod-synthesis-2026-10-20.json)
pass full mapping and setup at 1000 ps, TC/SLVT, 25% I/O, default ABC and no retiming:

| Profile | Core margin (ps) | I/O margin (ps) | Mapped cells |
| --- | ---: | ---: | ---: |
| Complete effective-LOD sampler | +179.638 | +408.384 | 304,435 |
| Corrected FP32 divider | +324.168 | +390.675 | 3,843 |

The first sampler screen fails by 804.665 ps through combined LOD resolution;
registered clamp/split/selection steps move the limiting path to array-layer
conversion at -136.698 ps. Layer guard extraction, rounding and saturation now
use separate states. Supplemental STA also finds a -3.147 ps periodic wrap path;
direct endpoint selection within the proven `[-1,2N]` range removes chained
modulo/subtraction arithmetic. The final pass retains seven-cycle axis latency,
one axis per cycle, numeric semantics and the original clock constraint.
The updated axis is included in the full-owner screen; earlier byte-exact
standalone-axis carry applies only to its historical selected-level snapshot.

## Standalone evidence and remaining integration

The [qualification record](performance/mobile-float-axis-synthesis-2026-10-20.json)
records two passing tests with 91,135 exact-rational reference queries. These
cover all 256 FP32 exponent encodings, both signs, all 16,383 nonzero extents,
representative significands, wrap/filter combinations, half-texel ULP neighbors,
random coordinates, rejected inputs, held output stability and ordered tags.
That historical dense stream checks seven-cycle latency and a one-cycle interval;
the current pipeline's latency is ten cycles, with the same initiation interval.

Fresh synthesis at 1000 ps, TC/SLVT, 25% I/O, default ABC and no retiming passes
mapping and setup: core +194.895 ps, I/O +632.673 ps, 19,046 mapped cells. Library cell
area is 2229.52986 units; this is not physical die area. No route or power
qualification is claimed.

Required next work connects the admitted effective-LOD owner to native floating
packet and instruction transport, shader bias, gradient-derived lambda and
implicit quad/helper derivatives. The current explicit lambda is supplied by
its parent; it is not a substitute for those shader operations. Banked caches,
miss joining, publication/coherence,
representative texture throughput and remaining H0–H8 requirements also remain
open. The current filter is serialized; its functional capacity does not
establish a cluster sampling rate.
