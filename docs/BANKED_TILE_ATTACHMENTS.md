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

All 34 quad-owner, unbanked/banked single-slot and double-buffered regressions pass,
including real SRAM, cold refills, provisional depth/stencil reads, partial
clears, packed formats, MSAA resolve, cancellation, late faults and reuse.
The bounded resident check retains 512 samples with four commits per cycle for
128 consecutive cycles. Aggregate diagnostic totals match the final per-slot
byte totals across overlapping exports and faults. Reproduce locally:

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TileSampleQuadMergeSpec opengpu.graphics.DoubleBufferedTileAttachmentStoreSpec opengpu.graphics.BankedDoubleBufferedTileAttachmentStoreSpec opengpu.graphics.BankedPersistentTileAttachmentStoreSpec'
```

These component results do not close H2/H3 or qualify whole-GPU timing or FPS.
