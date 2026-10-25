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

Each generic word port has one registered admission entry. A queued row reserves
admission order until the downstream service accepts its request. A full-word
initializer additionally owns its row until the write response: independent
port queues can accept requests before SRAM execution. Younger packed writes
wait for that response, preserving every field initialized from clear or load.
Among unqueued offers, fill owners outrank idle requests for the same row;
port order breaks ties within each class.
Priority follows row ownership before physical-service classification, so an
idle client waiting for a fill cannot block that fill. Accepted writes first
enter narrow metadata rows; valid/dirty readers forward that commit window
before the tile-wide maps update. Drain includes both request queues and pending
metadata.

Word addresses, data and lookup results follow the existing registered
color-run owner. The live depth enable controls acceptance, with no added
pipeline clock. Packed color word phases use a balanced radix-four residue
tree for exact modulo three. Streaming cancellation blocks grant acceptance after
candidate selection; an already offered operation retains its owner.

Successful acknowledgements determine effects and counters. Held word/refill
offers remain owned across cancellation or faults. Already offered terminal
results remain immutable; cancellation of unfinished work waits for complete
drain before reuse. A depth client cannot address a color allocation.

Twenty-eight parallel-policy and seven depth-memory checks pass across four- and
eight-port profiles. They cover all 36 color formats, four MRT views, MSAA,
masks, blended packed neighbors, clear repetition through byte 65,535,
1/64 KiB capacity boundaries, descriptor/quad rejection, joined cold loads,
held replies, cancellation and healthy reuse. SRAM-macro models cover refill,
maintenance, concurrent packed initialization and cancellation of accepted
writes. The delayed-initializer regression checks independent buffered ports
with disjoint D24S8 masks on one cold word. One arithmetic check additionally
exhausts all 8,192 13-bit word addresses, with odd/wider inputs and 32-bit
boundaries. Together with the three
shared-tile and fifteen complete-owner checks, 54 tests pass with zero skipped,
canceled, ignored or pending cases. Four standalone/default-serial suites add
26 passing checks. The prepared encoder adds one complete test, bringing the
component set to 81 distinct cases across eleven suites.

The full 32 KiB/eight-port generic policy passes synthesis at 1,000 ps with
25% I/O delay: +64.255 ps core / +45.636 ps I/O. The complete pixel owner
passes independently at +4.460 ps core / +248.295 ps I/O. Both use standard
mapping without retiming or false paths and have zero skipped or unresolved
operators. Renderer timing, arbitrary format rates and product image publication
remain separate gates.

The prepared encoder checks 6,939 inputs across 36 formats within the original
seven-cycle bound. Its UNORM increment is bounded to the supported ten-bit
channels while retaining the full remainder and midpoint comparisons. The
source-checked SMT proof compares the original and narrowed numeric expressions
for every raw 32-bit input at each width from zero through ten, including
NaNs, saturation and ties to even. It does not prove whole-host behavior.

```sh
git submodule update --init depends/asap7_sram_0p0
sbt -Dopengpu.simHierarchy=Rgba8BlendFmaEngine -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simJobs=1 \
  'testOnly opengpu.graphics.ParallelTypedTileColorStoreSpec opengpu.graphics.EightPortTypedTileColorStoreSpec opengpu.graphics.ParallelTypedDepthMemorySpec'
sbt 'testOnly opengpu.graphics.TypedWordPhaseSpec opengpu.graphics.PreparedTypedColorEncoderSpec'
python3 -m venv .artifacts/unorm-proof-venv
.artifacts/unorm-proof-venv/bin/python -m pip install z3-solver==5.3.1.0
.artifacts/unorm-proof-venv/bin/python scripts/prove_prepared_unorm_rounding.py
sbt -Dopengpu.simHierarchy=Rgba8BlendFmaEngine -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simJobs=1 \
  'testOnly opengpu.graphics.StreamingTypedTileColorLanesSpec opengpu.graphics.TypedDepthStencilSpec opengpu.graphics.TypedTileColorStoreSpec opengpu.graphics.TypedTilePixelBackendSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-parallel-typed-tile-policy .artifacts/parallel-typed-tile-policy-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/parallel-typed-tile-policy-rtl TypedTileColorStore \
  --stage synthesis --period-ps 1000 --io-delay 0.25 --timeout 14400 \
  --output .artifacts/parallel-typed-tile-policy-synthesis
```
