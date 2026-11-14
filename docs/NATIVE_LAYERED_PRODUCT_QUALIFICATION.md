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

The full-profile product command producer/executor integration remains required.
These CPU checks do not execute GNJ2, GS, transform feedback or compute, establish
whole-host timing, render complete 640x480 frames or qualify 60 FPS. Actual
qualification must use the same complete two-CU, sixteen-lane/eight-warp host,
its observed discovery words, fresh inputs, returned backing and matching clock.
Previous GNJ1 models and six-scene results cannot qualify this native path.
