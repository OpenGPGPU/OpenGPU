# Coalesced typed tile word service

`CoalescedTypedTileMemory` places a tagged coalescer before the atomic banked
SRAM service. Equal-word reads share one physical read. Masked writes combine
only when their fields are disjoint; neighboring RGBA8 or D24S8 fields can
therefore form a full 64-bit write without a read/modify/write bank cycle.
Overlapping writes remain separate operations.

The default configuration has four logical clients, sixteen reorder slots per
client, four physical request ports and eight banks. Its 32 KiB data array uses
sixteen 256x64 SRAM macros. Formation priority rotates. Each word remains
reserved through its physical reply, including while a request offer stalls.
Original 16-bit tags and per-client completion order survive reordered bank
replies. Read data and faults fan out to every group member; writes retain the
backing service's zero-data acknowledgement. Accepted work drains without a
cancellation flush, and held offers and replies remain immutable.

Seven executed tests pass with no cancelled, ignored or pending tests. Both
behavioral and vendor SRAM sustain 128 consecutive admissions and replies per
client: 512 logical masked writes become 256 full physical writes with zero
partial writes, and 512 logical reads become 256 physical reads. Read and write
streams are measured separately. Stored words match the independent model.
Additional cases cover overlapping masks, fault fanout, zero masks, repeated
tags, independent stalls and deliberately reordered physical replies.

Complete mapped synthesis passes at 1,000 ps, ASAP7 TC/SLVT and 25% I/O budgets:
+61.175 ps core and +532.542 ps I/O setup margin, with zero skipped or unresolved
mappings. These results qualify the complete coalescer/SRAM component.

The parent still owns attachment initialization, validity, dirty state, refill
and export. Integration with overlapping typed quads and sufficient combined
depth/color bandwidth remains H3 work. Separate word-stream rates do not prove
four complete blended RGBA8+D24S8 sample updates per clock or renderer FPS.

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TypedTileWordCoalescerSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-coalesced-typed-tile-memory .artifacts/coalesced-word-rtl'
python scripts/qualify_ppa.py .artifacts/coalesced-word-rtl CoalescedTypedTileMemory \
  --stage synthesis --period-ps 1000 --io-delay 0.25 \
  --output .artifacts/coalesced-word-synthesis
```
