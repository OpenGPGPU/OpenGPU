# Pixel arithmetic capacity

The pixel path needs spatial arithmetic and overlapping quads to support the
720p/30 FPS target. Raising the supported frame extent or replicating the
existing serial service does not establish that rate. This document defines
the wider implementation target and the first qualified building block.

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
storage, queues and arithmetic registers in the physical budget. The current
scalar interpolator and its native clients still require scheduler and transport
replacement; this target is not wired into `MobileGpuConfig` yet.

Sixty-four FP32 varying words require 256 bytes per fragment. At the 720p/30
baseline, staging alone writes 7.078 GB/s and shader input consumption reads
another 7.078 GB/s, before built-ins and other traffic. Qualify concurrent payload
ports, local-pool capacity, spills and shared-memory credits with the wider
arithmetic. An AXI beat width alone does not demonstrate this bandwidth.

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

Streaming multiply/add composition, quad scheduling, native integration,
aggregate storage/area/power, composed timing and workload rates remain open.
Routine rendering validation stays at or below 640×480; 720p remains explicit
product qualification. This component does not close H0–H8.
