# Ordered native typed pixel owner

`TypedTilePixelBackend` can select the native sample owner with
`parallelLanes=true`, `parallelDepth=true`, `nativeSamples=true` and four or
eight `streamingWordPorts`. The default remains the serial reference. Native
RGBA8+D24S8 cohorts and generic typed depth/color work share one canonical
32 KiB SRAM; cohort changes wait for complete physical drain.

The owner preflights whole quads before packetization, retains two input slots,
64 tagged quad metadata rows and two output slots, and aggregates acknowledged
packet effects into ordered quad and pass totals. Generic fallback uses the
registered depth lane, prepared clear encoder and streaming typed contexts.
Held results remain immutable through cancellation, faults and early EOF.

Fifteen tests pass with zero skipped or pending cases. They cover every
depth/stencil layout, compare/stencil operations, effective per-sample depth,
36 color formats with loaded samples and packed masks, all MSAA modes, exact
images/counters, cold-refill cancellation/faults, EOF and healthy reuse.
Behavioral full-owner tests compose components whose separate checks include
SRAM-macro models.

For the same 32-quad D32F/mixed-four-MRT workload, four/eight ports take
3,767/3,560 clocks at 1x and 10,694/6,844 at 4x. Warm native streams of 128
quads take 187/315/571 clocks at 1x/2x/4x, including fill/drain, and retain
complete-quad intervals of 1/2/4 clocks. A held-output test bounds admission to
68 quads and drains cancellation in order. Recursive RTL contains 100 FP32
FMA lanes, one banked memory and sixteen SRAM arrays for both port profiles.
These are bounded component results; complete timing, renderer/resource
integration, attachment publication and product frame rates require separate
qualification.

```sh
git submodule update --init depends/asap7_sram_0p0
sbt -Dopengpu.simHierarchy=Rgba8BlendFmaEngine -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simJobs=1 \
  'testOnly opengpu.graphics.NativeSampleTypedTilePixelBackendSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-native-typed-pixel-owner .artifacts/native-typed-pixel-owner-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/native-typed-pixel-owner-rtl TypedTilePixelBackend \
  --stage synthesis --period-ps 1000 --io-delay 0.25 --timeout 14400 \
  --output .artifacts/native-typed-pixel-owner-synthesis
```
