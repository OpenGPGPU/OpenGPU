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
Generic fallback queues requests before SRAM admission and forwards pending
residency metadata. Fill owners outrank idle same-row waiters while accepted
queued words retain their reservations. Initializers keep row ownership through
the write response even when downstream ports buffer accepted requests; cohort
handoff waits for all owners to drain.

Fifteen tests pass with zero skipped or pending cases. They cover every
depth/stencil layout, compare/stencil operations, effective per-sample depth,
36 color formats with loaded samples and packed masks, all MSAA modes, exact
images/counters, cold-refill cancellation/faults, EOF and healthy reuse.
Behavioral full-owner tests compose components whose separate checks include
SRAM-macro models. Nine serial-reference checks cover the same baseline pixel
fixtures. The complete 81-check component/default set is described in
[the parallel policy](PARALLEL_TYPED_TILE_POLICY.md).

Matched 32-quad D24S8 fixtures compare the serial reference with the selected
eight-port owner, retaining exact stored images and counters:

| Workload | Serial clocks | Selected-owner clocks |
|---|---:|---:|
| RGBA8, 1x | 4,744 | 2,080 |
| Four mixed MRT views, 4x | 28,674 | 5,747 |
| Blended RGBA8, 1x | 26,632 | 2,080 |

For the same 32-quad D32F/mixed-four-MRT workload, four/eight ports take
3,992/3,695 clocks at 1x and 12,033/7,328 at 4x. Warm native streams of 128
quads take 187/315/571 clocks at 1x/2x/4x, including fill/drain, and retain
complete-quad intervals of 1/2/4 clocks. A held-output test bounds admission to
68 quads and drains cancellation in order. Recursive RTL contains 100 FP32
FMA lanes, one banked memory and sixteen SRAM arrays for both port profiles.
Each owner contains 32 KiB of characterized tile data SRAM. Budgeting four
copies with the full-capacity 512 KiB shared payload pool requires at least
640 KiB of data SRAM and 400 pixel FMA instances. That floor excludes L2,
texture/bin/CU storage, metadata, queues and transfer buffers; it is not a
whole-host area or resource budget. The payload pool is eight times the
initial 64 KiB proposal.

The indexed-blend owner retains all 64 quad metadata rows and 128 packet
contexts. Free-row capture, head/tail metadata ownership and internal
drain handshakes preserve cancellation, ordered retirement and reuse.
Twelve focused component cases carry through byte-identical complete
emitted RTL, while three ABI checks and four complete 4x4 indexed native
host frames pass freshly on the current-main integration.

Complete 1000 ps timing qualification remains open. A completed screen
covering 127 definitions and sixteen real 64-bit SRAM macros reports
-73.848 ps core / +146.447 ps I/O setup margin. The later empty-window
rebind change is functionally validated, with no positive complete
timing result claimed here. The 1000 ps target and 25% I/O budget are
unchanged. Functional feature integration does not qualify a 1 GHz
clock, the whole host or product frame rates.

These are bounded component results; renderer/resource
integration, attachment publication and product frame rates require separate
qualification.

```sh
git submodule update --init depends/asap7_sram_0p0
sbt -Dopengpu.simHierarchy=Rgba8BlendFmaEngine -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simJobs=1 \
  'testOnly opengpu.graphics.TypedTilePixelBackendSpec opengpu.graphics.NativeSampleTypedTilePixelBackendSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-native-typed-pixel-owner .artifacts/native-typed-pixel-owner-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/native-typed-pixel-owner-rtl TypedTilePixelBackend \
  --stage synthesis --period-ps 1000 --io-delay 0.25 --timeout 14400 \
  --output .artifacts/native-typed-pixel-owner-synthesis
```
