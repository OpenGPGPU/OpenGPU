# Tile-ordered primitive binning

`WindowPrimitiveBinProducer` buffers up to eight durable record handles and
their rectangular tile cursors. A balanced selection tree merges those sorted
cursors by tile ID; equal IDs select the older input record. The producer
visits only tiles belonging to a primitive's bounds. It does not scan the
bounding union of unrelated primitives.

Record handles retain input order. Tile visits and physical bin-page allocation
can change, while each tile retains its complete reference order. Every record
store is acknowledged before its cursor becomes eligible. The batch flushes
when full or whenever the input is idle, so a producer waiting for append
progress cannot deadlock a partial batch. EOF is acknowledged after pending
references drain.

Record-arena exhaustion retains the exact rejected input and finishes the
older buffered references before returning it. A bin rejection invalidates the
pass and never claims a restartable prefix. Cancellation preserves a held
append and drains its reply; unoffered buffered references are discarded.

Bounds preparation registers vertex selection, extrema, expansion and clipping
before the final tile/arena decision. Point/line size selection is captured at
input admission, with the maximum bias formed from the registered margin.
Signed 64-bit coordinates are widened to
67 bits. Adding one pixel in subpixel units to the maximum's expansion bias
preserves the original signed floor-and-increment result. A 33-bit incrementing exclusive end
checks arena capacity through the 4 GiB boundary, including empty inputs.
Grid admission registers rounded dimensions before their capacity check.
The enclosing pass captures those dimensions at its descriptor handshake.
A two-entry bin-response queue exposes a registered head, removing pointer
selection before the directory's control paths. It drains before pass completion.

The producer admits up to 65,536 grid entries, with IDs 0–65,535. The enclosing
directory must support that count. A bounded regression stores one record and
visits the last tile; it does not rasterize or traverse a 2048×2048 frame.

## Matched bounded results

Baseline `e3d162e8c88b` and the batched producer use the same 20×17 fixture,
four complete records, all 16 varying slots, 26 references, nine tiles, two replay
engines, two resident directory slots, and identical memory/backpressure rules.
Every payload and per-tile reference order matches.

| Metric | Baseline | Batched, registered bounds |
|---|---:|---:|
| Producer build clocks | 2,383 | 2,247 |
| Complete fixture clocks | 5,159 | 5,035 |
| Directory misses | 35 | 18 |
| Directory read bytes | 2,240 | 1,152 |
| Directory write bytes | 2,816 | 1,728 |

Build clocks end at the input EOF acknowledgement. Complete clocks include the
fixture's held outputs and terminal result. The reductions are 5.7% in build
clocks, 2.4% in complete clocks and 43.0% in directory traffic. These results do
not establish a 720p frame improvement, whole-host clock or H6 acceptance.

Twenty-four local cases pass, including batch boundaries, idle partial flush,
single-record configuration, exact exhaustion restart, signed extreme bounds,
every bounds-preparation cancellation cut, held offers/replies, record/bin/read
faults, both replay engines, the complete-grid boundary and actual shared-CU
geometry. Routine fixtures remain small; the full-capacity emission below
does not render a frame.

## Component synthesis

The complete four-engine `WindowPrimitiveBinReplayPass` passes 1,000 ps ASAP7
TC/SLVT synthesis setup with 25% I/O delays. It retains all 16 varying slots,
4 KiB pages, eight write credits, eight resident directory slots, 16,384 paged
entries and characterized directory SRAMs. Core slack is **+53.440 ps** and I/O
slack is **+247.969 ps**. Mapping reports zero skipped or unresolved logic;
`check_setup` coverage is complete and clean. Direct and SBT emission produce
the same 69 SystemVerilog files byte for byte.

This result covers the bin-pass component. Whole-host clock, routed timing,
product frame rates and H6–H8 acceptance remain separate requirements.

## Reproduce

```sh
git submodule update --init depends/asap7_sram_0p0
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=2 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.WindowPrimitiveBinProducerSpec opengpu.graphics.WindowPrimitiveBinReplayPassSpec opengpu.graphics.WindowPrimitiveBinReplayGridAdmissionSpec' \
  'testOnly opengpu.graphics.GeometryWindowBinPassSpec'

sbt -Dopengpu.productQualification=1 \
  'Test / runMain opengpu.graphics.EmitNativeWindowBinTiming .artifacts/native-bin-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/native-bin-rtl WindowPrimitiveBinReplayPass --stage synthesis \
  $(python3 scripts/report_ppa_sram_inventory.py .artifacts/native-bin-rtl WindowPrimitiveBinReplayPass) \
  --synthesis-engine yosys --yosys-preserve-hierarchy --period-ps 1000 \
  --io-delay 0.25 --output .artifacts/native-bin-synthesis
```

The PR selects the two bounded producer/replay suites. The wider admission,
shared-CU and physical checks stay local. Product execution, assertion-enabled
whole-host qualification, routed timing and the remaining H6–H8 requirements
remain separate gates.
