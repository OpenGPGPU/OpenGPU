# Banked typed tile SRAM

`BankedTypedTileMemory` supplies four tagged word ports over eight independent
64-bit SRAM banks. The default 32 KiB store uses sixteen characterized
256x64 single-port macros. Consecutive word addresses select different banks.
Full writes and reads can issue every clock per bank. Partial bit masks use
an atomic read/modify/write with a second bank clock; packed neighboring
channels remain intact. Zero-mask writes have no SRAM side effects.

Bank arbitration serializes conflicts fairly. Responses may reorder across
banks, retain their request tags, and remain stable under backpressure.
Out-of-range addresses return faults without touching SRAM. Accepted writes
are irrevocable. The caller must drain requests and replies before releasing
tile ownership; pass validity, lazy clears and refills remain caller duties.

Four tests pass with behavioral and unmodified vendor SRAM models, with no
ignored or pending tests. Each port issues and retires 128 consecutive reads,
full writes and masked writes without a gap. The masked stream alternates
two groups of four banks, sustaining four 64-bit word operations per clock.
Checks also cover segment boundaries, conflicting masks, bounded arbitration,
independent write/read response stalls, preserved held data and invalid ranges.

The complete memory service passes synthesis/mapping at 1000 ps, TC/SLVT,
25% I/O delay: core margin +299.905 ps and I/O margin +322.960 ps, with zero
skipped or unresolved instances. This is a synthesis screen, without routing.

This service is the SRAM primitive for parallel typed pixel controllers.
The existing `TypedTilePixelBackend` and `TypedTileColorStore` still serialize
lane/sample/attachment work. Integrating parallel controllers, preserving
load/clear/blend/fault ordering, and measuring actual sample-update throughput
remain required. Word bandwidth does not establish pixel throughput or FPS.

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.BankedTypedTileMemorySpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-typed-tile-memory .artifacts/typed-tile-memory-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/typed-tile-memory-rtl BankedTypedTileMemory --stage synthesis \
  --period-ps 1000 --io-delay 0.25 --output .artifacts/typed-tile-memory-synthesis
```
