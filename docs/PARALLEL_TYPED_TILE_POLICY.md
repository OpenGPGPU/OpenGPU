# Parallel typed tile policy

`TypedTileColorStore` retains its default serial reference and offers parallel
typed contexts with four or eight word ports. The parallel owner snapshots and
validates each tile and quad, initializes clear/discard words lazily, joins
duplicate cold refills and preserves packed neighbors through atomic masked
word operations. One quad retains order until every lane and word reply drains.

The parallel path uses the shared streaming blend/encoder and prepared clear
encoder. Optional depth ports share allocation validation, captured depth clear
values, initialization and refill ownership. An external word service lets the
canonical native sample store supply the same SRAM after drained cohort changes.
Without it, the policy owns its banked array. It never allocates both arrays.

Successful acknowledgements determine effects and counters. Held word/refill
offers remain owned across cancellation or faults. Already offered terminal
results remain immutable; cancellation of unfinished work waits for complete
drain before reuse. A depth client cannot address a color allocation.

Thirty-two parallel-policy checks pass across four- and eight-port profiles,
including all 36 color formats, four MRT views, MSAA, masks, blended packed
neighbors, clear repetition through byte 65,535, 1/64 KiB capacity boundaries,
descriptor/quad rejection, joined cold loads, held replies, cancellation and
healthy reuse. SRAM-macro models cover refill/maintenance and matched streams;
four depth-memory checks cover concurrent initialization, packed updates,
load cleanliness, fault drain and reuse. The serial reference also passes the
same focused cancellation check. Enclosing pixel/renderer timing, arbitrary
format throughput and image publication require their integration gates.

```sh
git submodule update --init depends/asap7_sram_0p0
sbt -Dopengpu.simHierarchy=Rgba8BlendFmaEngine -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simJobs=1 \
  'testOnly opengpu.graphics.ParallelTypedTileColorStoreSpec opengpu.graphics.EightPortTypedTileColorStoreSpec opengpu.graphics.ParallelTypedDepthMemorySpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-parallel-typed-tile-policy .artifacts/parallel-typed-tile-policy-rtl'
```
