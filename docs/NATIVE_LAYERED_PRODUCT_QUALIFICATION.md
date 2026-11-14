# Native layered product inputs

`NativeLayeredProductFrameInputs` encodes the original desktop-composition,
textured-3d, opaque-overdraw, transparent-layers, small-triangles and msaa-4x
recipes as GNJ2/GNF3. Ordinary draws use the mixed geometry renderer with one
layer. The original vertex/fragment programs, vertex data, texture, state,
RGBA8/D24S8 attachments, 1x/4x samples and independent stored-image oracle are
preserved. The encoder accepts the original 8x8 decomposition and the separate
16x16 comparison; neither changes hardware capacities.

Each input owns a distinct 16 KiB GNY2 output with two guard lines. Verification
checks all stored samples, resolves, pitched image guards, immutable resources,
allocation metadata, GNY2 header/reserved words, complete command/pass identity,
exact draw counts, tile count, failure fields and wide source/bin/replay count
conservation. Conservation is not an independent count of clipped primitives.
Synthetic returned images are used only to test corruption rejection in the
verifier; they are not GPU execution evidence.

Two CPU tests pass after a direct Scala/Chisel-plugin compilation using a
256 MiB heap. They cover all six recipes and both tile decompositions, retained
source bytes, valid returned-image verification and mutations of shader,
descriptor, vertex, texture, sparse pages, GNY2 guards, identity, status, counts,
high bits of the 145-bit received count, samples and resolves. Reproduce through
the normal build with:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=1 \
  'testOnly opengpu.graphics.NativeLayeredProductFrameInputsSpec'
```

`PrepareNativeLayeredProductFixtures` prepares all six original scenes through
the layered encoder. It requires an existing output directory and
`opengpu.fullHostQualification=1`, accepts `--smoke|--product --output DIR`
with optional `--tile-log2 3|4`, and creates a fresh `fixtures/` directory with
`{scene}.memory`, `{scene}.commands` and `suite.txt`. Smoke uses 32x32; product
uses the mandatory two-CU 640x480 target. Hardware capacities stay unchanged.
The separate `layer-product-fixture-manifest.json` extends the complete
`PrepareOwnerResourceFullHost.manifest(false)` with workload dimensions, tiling
and scene IDs, retaining all 104 exact discovery address/value pairs and
`rtlEmitted=false`, `gpuExecution=false` and `fullResourceQualification=false`.

The command container starts with ASCII `OGLAY001`. All following integers are
little endian: four u32 values (width, height, samples, command ID), a u64 cycle
limit (10 million smoke, one billion product), then a u32 discovery count and
104 address/value u32 pairs in `OwnerResourceProfile.target.requiredDiscovery`
order. The remaining OGNAT001 container shape is retained: a count and eight
address/value writes, a count and thirteen address/mask/value checks, and a
count and two address/value completion-acknowledgment writes, with no suffix.
Opcode 7 uses SOURCE=`jobBase`, DEST=`jobBase+512`, BYTES=512. The job view limit
is independent of the separately allocated guarded 16 KiB GNY2 backing named
by the GNJ2 job's completion fields. GNX1 retirement reads retain all twelve
observation registers; wide results are verified from GNY2 backing.

`VerifyNativeLayeredProduct` accepts the same arguments with `--output` naming
the directory of independently returned `{scene}.memory` files. It checks exact
allocation metadata and calls `NativeLayeredProductFrameInputs.verify` for each
scene, covering images, samples, resolves, read-only guards and GNY2 publication.
The producer and verifier emit separate `NATIVE_LAYERED_PRODUCT_FIXTURES`,
`NATIVE_LAYERED_PRODUCT_IMAGE` and `NATIVE_LAYERED_PRODUCT PASS` markers. The
original GNJ1 producer and verifier are unchanged. The new producer sources pass
a direct Scala parser check. Its data-only suite has not yet run against a
fresh complete factory: fresh all-main compilation exhausted the bounded
384 MiB heap. Reproduce the pending producer checks with:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=1 \
  'testOnly opengpu.system.NativeLayeredProductFixturesSpec opengpu.graphics.NativeLayeredProductFrameInputsSpec'
```

The full-profile executor and actual DUT qualification remain required.
These CPU checks do not execute GNJ2, GS, transform feedback or compute, establish
whole-host timing, render complete 640x480 frames or qualify 60 FPS. Actual
qualification must use the same complete two-CU, sixteen-lane/eight-warp host,
its observed discovery words, fresh inputs, returned backing and matching clock.
Previous GNJ1 models and six-scene results cannot qualify this native path.
