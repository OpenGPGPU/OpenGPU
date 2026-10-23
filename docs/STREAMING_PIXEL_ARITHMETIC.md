# Pixel arithmetic capacity

The pixel path needs spatial arithmetic and overlapping quads to support the
720p/30 FPS target. Raising the supported frame extent or replicating the
existing serial service does not establish that rate. This document defines
the wider implementation target and its qualified arithmetic blocks.

## Wider implementation target

Retain two raster/tile engines and the shared four-CU shader pool. Each engine
needs four independent component pipelines, each accepting a three-term
weighted component every cycle. Preserve the existing separately rounded
extended-exponent multiply/add/divide sequence; replacing division with a
rounded reciprocal multiply would change results.

| Resource | Per engine | Both engines |
|---|---:|---:|
| Component pipelines | 4 | 8 |
| Streaming multipliers | 12 | 24 |
| Streaming adders | 8 | 16 |
| Streaming dividers | 4 | 8 |
| Independently owned quad contexts, initial target | 4 | 8 |
| Raw preparation pixel-lane services | 16 | 32 |

Each arithmetic pipeline must sustain an initiation interval of one. Four
lanes issue a full 4-pixel × 64-component quad in 64 cycles. With setup and
completion fully overlapped, two engines would issue eight pixels in 64 cycles:
125 million pixels/s at an assumed 1 GHz, versus 27.648 million pixels/s required
for 1280×720 at 30 FPS. These are capacity calculations, not measured renderer
rates or an established product clock. Depth/sample work, small triangles,
overdraw, shader execution, memory traffic and backpressure need separate rates.

Primitive setup must be reusable across quads and keep pace with component
issue. Quad contexts must retain identity, flat/centroid masks, sample/depth
state and results through stalls, faults and cancellation. Count all context
storage, queues and arithmetic registers in the physical budget. The [native spatial path](SPATIAL_FRAGMENT_INTERPOLATION.md) now connects
four raw preparation owners to the component bank, with four private pixel-lane
services in each owner. The bounded setup fixture sustains four full quads per
389 clocks against a 400-clock bound. Preparation still limits component-bank
utilization; primitive reuse, composed timing and product rates remain required.

Sixty-four FP32 varying words require 256 bytes per fragment. At the 720p/30
baseline, staging alone writes 7.078 GB/s and shader input consumption reads
another 7.078 GB/s, before built-ins and other traffic. Qualify concurrent payload
ports, local-pool capacity, spills and shared-memory credits with the wider
arithmetic. An AXI beat width alone does not demonstrate this bandwidth.

Using 389 clocks per four quads at the same assumed 1 GHz gives
82.3 million pixels/s across two engines, before shader, payload, attachment
and overdraw costs. Three full-screen layers already require 82.944 million
pixels/s at 720p/30. Preparation therefore still needs primitive reuse or more
throughput; the current expansion does not provide that headroom. Native
fragment dispatch must also demonstrate simultaneous use of all four CUs,
and staging/readback must overlap transfers rather than wait for each reply.
These remain separate capacity gates; frame dimensions do not qualify them.

## Qualified streaming divider

[`ClipFloatStreamingDivider`](../src/main/scala/opengpu/graphics/ClipFloatStreamingDivider.scala)
accepts one tagged division each cycle. With no backpressure, it offers the
result after 29 advancing edges and then retires one result each cycle. Its
twenty-four spatial quotient steps retain exact sticky information for RNE.
The finite exponent range remains [-512,511]; arithmetic flags and independent
FP32 packing flags retain the scalar representation's contract. Special and
malformed requests traverse the same pipeline in order.

Backpressure freezes the pipeline. Cancellation blocks new requests and marks
internal accepted tokens, which drain with their computed values; callers must
discard cancelled results. Cancellation cannot mutate a response already
offered to the consumer.

Four divider tests plus seven existing scalar arithmetic tests pass with zero
failed, canceled, ignored or pending tests. Coverage includes a dense 512-request
stream, exact rational results, full-range random exponents, FP32 packing
boundaries, malformed/special operands, output stalls, cancellation with a full
pipeline and healthy reuse without reset.

Complete component synthesis at 1000 ps, TC/SLVT, with 25% I/O delay and no
false paths or retiming passes core/I/O setup by +292.133/+440.354 ps. Mapping
contains 13,107 standard cells, library area 1,899.66 µm² and no SRAM macros,
with zero skipped instances or unresolved diagnostics. This is a synthesis
estimate for one divider, not physical closure of eight dividers or the renderer.

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.ClipFloatStreamingDividerSpec opengpu.graphics.ClipFloatArithmeticSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-clip-float-streaming-divider .artifacts/streaming-divider-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/streaming-divider-rtl ClipFloatStreamingDivider --stage synthesis \
  --period-ps 1000 --io-delay 0.25 --output .artifacts/streaming-divider-synthesis
```

## Qualified streaming add/multiply

[`ClipFloatStreamingAddMul`](../src/main/scala/opengpu/graphics/ClipFloatStreamingAddMul.scala)
supports add, subtract, multiply and the internal directed add. It retains the
existing normalized FP32 FMA core with sixteen owned result credits. Tagged
extended exponents, malformed-input state and cancellation travel separately
through owned metadata slots. Dense requests are accepted and retired every
cycle with 11-cycle latency. Accepted work drains during cancellation; offered
responses remain immutable. Division is rejected rather than silently routed
to another operation.

Fifteen tests across the streaming add/multiply, streaming divider and existing
scalar arithmetic suites pass with zero failed, canceled, ignored or pending.
The new four-case suite independently checks exact rational arithmetic and
directed rounding, all special/sign combinations, extreme exponents, malformed
inputs, a dense 512-request stream, randomized stalls and cancellation recovery
without reset.

Complete add/multiply component synthesis passes the same 1000 ps TC/SLVT and
25% I/O constraints by +74.477/+346.132 ps core/I/O, with 15,714 standard cells,
library area 2,202.44 µm², no SRAM macros and zero skipped/unresolved instances.
Individual arithmetic blocks passing does not establish the composed lane's
clock or aggregate area.

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.ClipFloatStreamingAddMulSpec opengpu.graphics.ClipFloatStreamingDividerSpec opengpu.graphics.ClipFloatArithmeticSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-clip-float-streaming-add-mul .artifacts/streaming-add-mul-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/streaming-add-mul-rtl ClipFloatStreamingAddMul --stage synthesis \
  --period-ps 1000 --io-delay 0.25 --output .artifacts/streaming-add-mul-synthesis
```

## Qualified weighted component

[`ClipFloatWeightedComponent`](../src/main/scala/opengpu/graphics/ClipFloatWeightedComponent.scala)
spatially composes three multipliers, two adders and a divider. It accepts and
retires one prepared weighted component per cycle, with 64-cycle latency and
the same six separate RNE operations as the serial interpolator. A two-slot
queue between the adders breaks the long combinational backpressure path.
Ordered metadata retains all arithmetic/packing flags and the first error in
scalar evaluation order. The operation count and flags stop at that error;
failed components return zero. Final packing flags are also reported separately.

Three component tests pass: a dense 512-component stream (3,072 scalar
operations), independent rational intermediate rounding, random full-range
exponents, special/malformed inputs, long output stalls, cancellation before
offer and while full, drain with cancel held high and reuse without reset.
Cancellation blocks admission until every accepted component drains and
preserves an already offered result. No tests fail, cancel, skip or remain pending.

Complete component synthesis passes the same 1000 ps TC/SLVT and 25% I/O gate
by +114.871/+383.768 ps core/I/O. It contains 110,755 standard cells, library
area 15,543.8 µm², no SRAM macros and zero skipped/unresolved instances.

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.ClipFloatWeightedComponentSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-clip-float-weighted-component .artifacts/weighted-component-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/weighted-component-rtl ClipFloatWeightedComponent --stage synthesis \
  --period-ps 1000 --io-delay 0.25 --output .artifacts/weighted-component-synthesis
```

## Qualified four-lane quad arithmetic

[`ClipFloatQuadComponent`](../src/main/scala/opengpu/graphics/ClipFloatQuadComponent.scala)
implements four spatial component pipelines with shared per-vertex component
values and independent weights/denominators for the four pixels. It atomically
accepts one varying row for all four pixels every cycle. A full 64-component
quad takes 64 issue cycles; row latency is 65 cycles, so the final row of the
first quad is offered 128 cycles after its first request. Subsequent full quads
can issue every 64 cycles once prepared inputs are available.

The block contains twelve streaming multipliers, eight adders and four dividers.
Atomic result joins preserve all four lane tags, results, flags and errors under
backpressure. Cancellation drains accepted rows with cancel held high, keeps a
held four-lane response immutable and admits healthy work again without reset.
The enclosing primitive/quad scheduler owns attribute storage, prepared weights,
depth/sample work and quad context lifetime.

All 21 tests in five arithmetic suites pass with zero failed, canceled, ignored
or pending cases. The bank's three tests include four consecutive full quads
(256 rows/1,024 components), independent separately rounded rational results,
random values and long output stalls, per-lane malformed faults, cancellation
drain and healthy reuse.

Complete four-lane synthesis passes 1000 ps TC/SLVT with 25% I/O by
+45.445/+327.230 ps core/I/O, with 445,611 standard cells, library area
62,389.3 µm², no SRAM macros and zero skipped/unresolved instances. This screen
covers the actual four-lane block, including its fork/join control. Two engines,
quad-context storage, transport, physical area/power and the enclosing clock
still need composed qualification.

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.ClipFloatArithmeticSpec opengpu.graphics.ClipFloatStreamingDividerSpec opengpu.graphics.ClipFloatStreamingAddMulSpec opengpu.graphics.ClipFloatWeightedComponentSpec opengpu.graphics.ClipFloatQuadComponentSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-clip-float-quad-component .artifacts/quad-component-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/quad-component-rtl ClipFloatQuadComponent --stage synthesis \
  --period-ps 1000 --io-delay 0.25 --output .artifacts/quad-component-synthesis
```

Quad scheduling, native integration,
aggregate storage/area/power, composed timing and workload rates remain open.
Routine rendering validation stays at or below 640×480; 720p remains explicit
product qualification. This component does not close H0–H8.


## Owned prepared quads

[`Fp32PreparedQuadInterpolator`](../src/main/scala/opengpu/graphics/Fp32PreparedQuadInterpolator.scala)
adds four private quad contexts around the four-lane component bank. Accepted
attributes, masks, depth/sample metadata and output snapshots remain owned until
ordered completion. Registered attribute selection and bank admission overlap
row issue with writeback. With prepared inputs available, sixteen full quads
retire in order with a steady 64-clock interval; all 4,096 component results
match independently rounded rational references. This measures prepared
interpolation, excluding raw weight/depth preparation and the native renderer.

Nine tests pass with zero failed, canceled, ignored or pending cases. They cover
mixed flat/centroid components including bits 60 and 63, raw NaN flat values,
helper and covered-sample fallback, points and lines, unused rows, held outputs
while later contexts finish, reset and cancellation drain. Per-lane operation
and flag prefixes preserve the scalar first error, including an earlier
component failure before a later preparation failure. A trusted parent may
transfer already reserved preparation results during cancellation; it must
bound credits and stop new raw work. Offered quads and completions stay
immutable, and cancellation never abandons posted arithmetic.

Complete owner synthesis passes 1000 ps, TC/SLVT and 25% I/O delay with
no retiming or false paths. Both variants have zero skipped or unresolved
mapping diagnostics. Counts include the component bank, contexts, queues and
all arithmetic/storage registers; storage uses standard cells, without SRAM
macros. These are synthesis estimates, excluding raw preparation and native
integration, placement/routing and product FPS.

| Owner admission | Core setup margin (ps) | I/O setup margin (ps) | Standard cells | Library area (µm²) |
|---|---:|---:|---:|---:|
| Fresh prepared work | +59.945 | +472.492 | 775,041 | 113,271 |
| Parent-reserved transfers | +39.349 | +687.183 | 774,899 | 113,255 |

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.Fp32PreparedQuadInterpolatorSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-fp32-prepared-quads .artifacts/prepared-quads-rtl' \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-fp32-reserved-prepared-quads .artifacts/reserved-prepared-quads-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/prepared-quads-rtl Fp32PreparedQuadInterpolator --stage synthesis \
  --period-ps 1000 --io-delay 0.25 --output .artifacts/prepared-quads-synthesis
# Repeat synthesis for .artifacts/reserved-prepared-quads-rtl with a separate output.
```
