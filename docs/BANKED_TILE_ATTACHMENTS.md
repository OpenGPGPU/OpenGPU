# Banked tile attachment bookkeeping

The banked persistent store updates its valid/dirty bitmaps from the actual
four write-commit strobes and the refill-commit strobe. Each committed index
selects one bitmap bit. Assertions check that the narrow enables equal the
nonzero decoded masks; SRAM writes, sample reservations and bitmap contents
are unchanged. This keeps a tile-wide mask reduction off the update enable.

The banked double-buffered wrapper registers each slot's load/store/resolve
byte pulse before summing the two slots. Aggregate diagnostic byte pulses and sample-commit counts are
therefore delayed by one cycle. Per-slot completion-byte counters still consume
the original service events. SRAM writes and reservation release remain on
their actual commit edge.
Unbanked pulse timing is unchanged. Both slots and all SRAM capacities remain.

Four one-entry read heads register complete work words before attachment
valid-bit lookup and bank arbitration. All eight packet contexts remain; a
full context table can retire its oldest result and admit its replacement on
the same edge. Read requests gain one initial head-capture cycle. Cancellation
before that capture produces no SRAM request and drains before healthy reuse.

Sample locks release one cycle after the actual write or canceled-work terminal
event. Completion and SRAM commit still record the original event; the extra
lock cycle separates bank arbitration from reservation decoding and prevents
premature same-sample reuse. Drain includes pending releases. All eight packet
contexts remain available, including same-edge retirement and replacement.

The merge arithmetic retains three-cycle latency and one result per cycle.
Its two final result slots use registered capacity for admission, cutting
bank-write readiness out of upstream stage control. All eight packet contexts
remain. Per-bank read/write classes arbitrate fairly, with round robin within
each class. Four reads and five writes that stay continuously eligible are
served within ten bank cycles; each bank still commits one operation per cycle.

Unlocked bank arbitration reduces valid directly from the offered requests.
Exhaustive mask checks at two, four and five ports match round-robin choice,
ready and data after seeding each legal arbitration history. Resolve requests
retain their prepared destination instead of recapturing it through request
readiness; an assertion checks the address invariant at acceptance.

The transfer service admits acknowledgement replacement from the export
scanner's independent ready signal. Shared read/refill response backpressure
no longer feeds request credit admission. Export acknowledgements still retire
and replace every cycle; external writes retain ownership until their actual
responses, including reverse-order replies. Private request admission waits for
a held acknowledgement to drain. An offered memory fault immediately
stops new external stores, independently of read/refill readiness. Fault
reporting and response ownership still retire on the actual handshake;
a held-fault regression checks exact drain and healthy owner reuse.

All 54 distinct arithmetic, bank-service, quad-owner and attachment checks pass,
including real SRAM, cold refills, provisional depth/stencil reads, partial
clears, packed formats, MSAA resolve, cancellation, late faults and reuse.
The bounded resident check retains 512 samples with four commits per cycle for
128 consecutive cycles. Aggregate diagnostic totals match the final per-slot
byte totals across overlapping exports and faults. Reproduce locally:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TileAttachmentTransferSpec opengpu.graphics.TileSampleMergePipelineSpec opengpu.graphics.BankedTileSampleSramSpec opengpu.graphics.TileSampleQuadMergeSpec' \
  'testOnly opengpu.graphics.DoubleBufferedTileAttachmentStoreSpec opengpu.graphics.BankedDoubleBufferedTileAttachmentStoreSpec opengpu.graphics.BankedPersistentTileAttachmentStoreSpec'
```

Three current-source two-engine integration cases also pass: ordered
real-SRAM depth/stencil/blend rendering, exact partial 4x resolve with guard
pixels, and actual shared-CU execution through Sv32 with fault recovery.

Complete 36-module synthesis with 32 real SRAM macros passes 1,000 ps TC/SLVT
with 25% I/O delay: core +19.639 ps and I/O +46.698 ps. Standard ABC retains
all logic, with no skipped/unresolved instances, retiming, mocks or false paths.
Reproduce with a sibling ChipAgent checkout:

```sh
sbt -J-Xmx2G -Dopengpu.productQualification=1 \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-tile-double-banked .artifacts/banked-attachments/emitted'
python3 scripts/extract_emitted_rtl.py .artifacts/banked-attachments/emitted DoubleBufferedTileAttachmentStore .artifacts/banked-attachments/complete
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/banked-attachments/complete DoubleBufferedTileAttachmentStore \
  --stage synthesis --synthesis-engine yosys --yosys-preserve-hierarchy \
  --period-ps 1000 --io-delay 0.25 --output .artifacts/banked-attachments/qualification
```

The emission checks product address capacity without rendering a product frame.
These results qualify this component and its bounded integration cases.
H2 acceptance, H3 throughput/feature closure, whole-GPU timing and FPS remain
separate requirements.
