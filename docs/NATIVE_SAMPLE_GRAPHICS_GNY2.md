# Ordinary112 clipping-triangle GNY2 oracle

`scripts/native_sample_graphics_gny2_oracle.py` supplies a separate expected
result for `original-abi1-uniform-triangle-v1`. It checks all 4096 little-endian
words, all 628 catalog fields, reserved gaps and high field padding. It does
not reuse the sample-point oracle or interpret shader instructions.

Call `verify(data, command_id=original_command_id, context=original_context)`
only after independent compiler/reflection/source/canonical-descriptor checks
and the successful actual held receipt establish those identities. The fixed
pass/generation are 11/13. These are expected invariants, not observed DUT
results. The same per-job values apply to both original owners; each job must
retain its own command ID, completion view and source leases. The oracle does
not authorize hardware ACK, retirement or cleanup.

## Active values

The original VS copies the explicit vertices `(-1,-1,0,1)`, `(3,-1,0,1)` and
`(-1,3,0,1)`. Exact rational half-space clipping produces four distinct square
corners, not a point. `PrimitiveClipStage`'s `classify` boundary rule copies
boundary endpoints without duplicate intersections; `planeAdvance` sets
`partCount=count-2`. The resulting two nondegenerate fan parts have finite,
in-bounds 4x4 viewport coordinates. Both touch the only tile/layer. No cull,
scissor, restart, GS, indirect draw or TF extension is enabled.

Each nonzero field belongs to a group below. Paths are under
`src/main/scala/opengpu/`; updates count accepted events, not elapsed cycles.

| GNY2 fields | Expected value and derivation | RTL definition/update |
| --- | --- | --- |
| `commandId` | Original nonzero 8-bit enqueue ID | `graphics/NativeBinnedGraphicsJobReader.scala`, `Phase.finish`; `graphics/NativeLayeredCompletionWriter.scala`, `io.in.fire` |
| `descriptorReadBytes` | 3072 = GNJ512 + GNF2048 + one GFS512 preflight | `NativeBinnedGraphicsJobReader`, `descriptorRequest.fire` and `childRequest.fire`; fresh reader invalidated on submit |
| `drawReadBytes` | 4096 = GND2048 scanned before begin, then fetched for execution | `NativeBinnedGraphicsJobReader`, `requestDraw`, `drawValidated`, `Phase.offerBegin` |
| `issuedDraws`, `completedDraws` | 1 each, one original draw | `NativeBinnedGraphicsJobReader`, `io.draw.fire` / `io.drawCompletion.fire` |
| `frame.version`, `frame.images.version`, `frame.images.images.version` | 1 each | `system/NativeLayeredVertexBinImagePipeline.scala`, `graphics/LayeredWindowPrimitiveBinImagePipeline.scala`, `graphics/LayeredTileChainScheduler.scala`, result construction |
| `frame.draws` | 1 | `NativeLayeredVertexBinImagePipeline`, `io.draw.fire` |
| `frame.admittedParts`, `frame.receivedParts` | 2 each, the two post-clip fan offers | `NativeLayeredVertexBinImagePipeline`, `source.io.out.fire`, `images.io.in.fire`, `nextSequence` / `totalReceived` |
| `frame.images.producer.accepted`, `.stored`, `.references` | 2 each | `graphics/WindowPrimitiveBinProducer.scala`, `io.in.fire`, successful `State.storeWait`, successful `io.appended.fire` |
| `frame.images.producer.recordWriteLines` | 46 = 2 complete records × 23 lines | `WindowPrimitiveBinProducer`, transfer result `writeLines` accumulation; `graphics/WindowPrimitiveRecords.scala`, one reply per line |
| `frame.images.directory.committedCount` | 2 | `graphics/TileBinDirectory.scala`, successful reference count commit |
| `frame.images.directory.allocatedPages`, `.tilesOpened` | 1 each; two 16-byte references fit the same 4096-byte pool page | `TileBinDirectory`, first `headerWait` opens the tile; `BinPagePool` allocation completion |
| `frame.images.directory.directoryMisses` | 1; first append misses the freshly cleared resident cache, second hits the same tile | `TileBinDirectory`, `directoryLookup` / `directoryMisses` |
| `frame.images.directory.directoryReadBytes` | 64, one metadata miss read | `TileBinDirectory`, `io.directoryMemoryRequest.fire` |
| `frame.images.directory.directoryWriteBytes` | 128 = fresh-v3 initialize64 + final dirty flush64, no eviction | `TileBinDirectory`, `initialize`, final metadata flush and request-byte accumulator |
| `frame.images.images.tiles` | 1, one closed original tile chain | `LayeredTileChainScheduler`, `io.tileDone.fire` totals(0) |
| `frame.images.images.scanned`, `.selected` | 2 each, one layer-zero scan/select per reference | `graphics/LayeredWindowPrimitiveReplay.scala`, reader reference / output fire |
| `frame.images.images.pageReadLines` | 1; header32 + 2×reference16 = 64 bytes | `graphics/PrimitiveBinReader.scala`, line register `recordHit` and `io.memoryRequest.fire`; replay adds `readLines` |
| `frame.images.images.recordReadLines` | 46 = 2×23; this layered path has a direct full-record transfer, not a record cache | `LayeredWindowPrimitiveReplay`, `record.io.result.fire` |
| `frame.images.images.accepted`, `.issued`, `.completed` | 2 each | `graphics/TypedReplayTileImagePipeline.scala`, record acceptance, `image.io.draw.fire`, successful draw completion; layered owner/scheduler sum results |
| `frame.images.images.storedBytes` | 256 = 4×4×16, one enabled RGBA32UI attachment flushed once after both draws | `graphics/TypedTileImageTransfer.scala`, acknowledged successful store `chunk` accumulation; layered tile completion sums transfer bytes |
| `*.identity.context/pass/generation` at six identity roots | Original context / 11 / 13 | Job capture and frame/state/arena/pool descriptors; each enclosing result copies the retained identity |

The six identity roots are `identity`, `frame.identity`,
`frame.images.identity`, `frame.images.producer.identity`,
`frame.images.directory.identity` and `frame.images.images.identity`. The table
covers all 48 nonzero scalar fields. There is one layer, one sample and one
attachment; the four-color-plus-depth point total is inapplicable.

The 23-line record is independently derived from the full-p16 Bundle widths:
`WindowPrimitiveRecords`, `PrimitiveAssemblyStage`, `VertexShaderStage`,
`Fp32ViewportStage`, `ClipFloatArithmetic` and `Fp32TriangleRasterStage`.
The census has 332 non-geometry scalar leaves, starts at header word16, inserts
line padding before a crossing multiword field, and ends at
`raster.polygonOffsetUnits` word356. Thus 357 occupied-through words round to
23 lines. All 16 varying slots on all three vertices participate, even though
this source uses one slot. Geometry metadata is excluded from the scalar leaf
format by the actual GWP header rule.
Every independently censused leaf width is also checked against the completion
catalog's actual `WindowPrimitiveRecord` restart-snapshot type. Its GNY2 word
positions are not used to compute the separate GWP record placement.

## Zero fields

Three complete snapshots account for 555 fields, with separate update proofs:

| Prefix | Fields | Why every nested field is zero |
| --- | ---: | --- |
| `frame.sourceFault.` | 108 | `NativeLayeredVertexBinImagePipeline` zeros the register on begin; only `sourceFailure` captures a source result. Successful source work does not populate this diagnostic snapshot. |
| `frame.images.producer.restart.` | 336 | `WindowPrimitiveBinProducer` zeros the result in `State.finish` and copies `current` only for arena exhaustion status2. Two 1472-byte records fit the 65536-byte arena; two references fit the 4096-byte pool. |
| `frame.images.images.fault.` | 111 | `LayeredTileChainScheduler` zeros the held fault on begin; only source-chain/child-error branches assign it. Successful work counters are stored separately in totals. |

The remaining 25 zero scalar fields are all explicit in the new module:

- Job `faultAddress`, `faultDraw`, `faultStage`, `status`: successful job/reader.
- Frame `droppedParts`, `sourceFaultValid`, `faultDrawValid`, `faultDraw`,
  `faultStage`, `status`: both source parts admitted without cancel/fault.
- Frame `submittedGeometryPrimitives`, `completedGeometryInvocations`,
  `emittedGeometryPrimitives`: the canonical ordinary extension is zero, so
  `NativeLayeredVertexBinImagePipeline` drives `ordinary=true` and increments
  these only inside `when(draw.geometry.valid)`, which is false. The presence
  of GS/tessellation/compute hardware does not populate a successful job's
  diagnostic snapshot or enable another domain for this command.
- Image-owner `status`; directory `spillTransitions`, `status`: successful
  fresh pass with one initial pool page and no spill/resume.
- Producer `restartValid`, `binStatus`, `faultTile`, `status`: no exhaustion,
  append failure, cancellation or memory fault.
- Image summary `loadBytes`, `resolvedBytes`: explicit clear load-op and all
  resolve flags zero. `faultValid`, `status`: successful scan/image work.
  `overflow`: the 11 per-tile totals are at most 256, far below 64-bit overflow.

The catalog check rejects unknown fields, changed snapshot counts, missing or
duplicate fields, overlapping/cross-line/out-of-range placements and an
unclassified active field. It requires exactly 628 fields, 73 explicit scalar
names and 766 occupied words including the header. No unknown item is silently
treated as zero.

No serialized GNY2 counter in this fixed recipe depends on cycle latency,
cache replacement between multiple tiles, engine assignment or draw overlap:
one tile retains one engine through both ordered records, and all totals reset
on a new job/frame begin. Other inputs, faults, spill, resume, layers, MSAA and
resolves require their own derivation. A future DUT disagreement must be
diagnosed against these definitions; it is not permission to relax the oracle.

## CPU validation and limits

Six data-only Python tests pass in about two seconds. They independently check
the rational clipped square/two nondegenerate fan parts and full-p16 layout,
the active totals and all zero snapshots, and reject 16384 low/high-bit word
mutants across two owner command IDs. Additional tests cover all wide-field
words/high padding, malformed catalog placement/classification, wrong external
identity and exact EOF. Handfilled expected GNY2 inputs are test fixtures, not
GPU observations.

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_native_sample_graphics_gny2_oracle.py
```

No Mesa, JVM, container, model or DUT was launched. This slice does not connect
the new oracle to the controller, qualify a prepared executor, alter the point
oracle/ordinary descriptor checker, or claim any returned image/GNY2, hardware
ACK, live cleanup, clock or FPS result.
