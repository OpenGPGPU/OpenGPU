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
high bits of the 145-bit received count, samples and resolves.

Writable scratch is restricted to each actual VS/FS view, including the
per-state/per-engine fragment views. Unbound slots, prefix tails, slot gaps and
directory bytes beyond the active tile view remain immutable guards even when
their surrounding AXI backing is mapped. The tests accept scratch output bytes
inside the bound views and reject mutations in those surrounding guards.
Reproduce through the normal build with:

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

The separate executor is described in `NATIVE_LAYERED_PRODUCT_EXECUTOR.md`.
`scripts/qualify_native_layered_product.py` binds its build and each execution
to the exact emitted `geometry-compute` owner source, assertions, RTL, generator,
104-word factory, fixtures and current driver. It rejects old 96-word prepared
models before fixture work. Every execution row must include ordered actual
resource readback, complete successful GNX, drained traffic, observed retirement
and service/reset-through-retirement cycle intervals. Independent verification
requires all six exact image/sample/resolve/guard/GNY2 markers; a coarse IRQ or
partial GNY2 prefix cannot qualify a frame. Python CPU rejection and model-build
regressions pass (60 tests, one optional real-FlashSim test skipped).

Run the actual path only after obtaining a fresh complete factory compilation
and safe resources for emission and model generation:

```sh
python3 -B scripts/prepare_native_owner_flashsim.py --full-resources \
  --stage prepare --output .artifacts/layered-owner --java-heap 24G
python3 -B scripts/qualify_native_layered_product.py --product \
  --output .artifacts/layered-owner --flashsim-dir /absolute/path/to/FlashSim \
  --stage all --jobs 2 --shards 32 --precompiled-header --conservative-alias
```

Fresh full compilation, Scala-to-C++ fixture interoperability, complete model
generation and actual DUT qualification remain required. These commands have
not yet completed on this new profile. The corresponding compute executors use
their own exact factory contracts and still require independent DUT qualification.
These CPU checks do not execute GNJ2, GS, transform feedback or compute, establish
whole-host timing, render complete 640x480 frames or qualify 60 FPS. Actual
qualification must use the same complete two-CU, sixteen-lane/eight-warp host,
its observed discovery words, fresh inputs, returned backing and matching clock.
Previous GNJ1 models and six-scene results cannot qualify this native path.

## Same complete 112-word sample owner

The separate fixed `qualify_native_sample_layered_product.py` entry binds these
same six original recipes to `two-cu-vga60-owner-v2-sample-stage`, stage
`geometry-compute-sample`. It requires all 112 actual discovery words and GCR
flags `0x1fffff`. It does not accept the 104-word model, fixtures, rows or
verification markers. The old 104-word entry remains closed to this factory.

`PrepareNativeSampleLayeredProductFixtures` writes `OGLAY002`, GNJ2/GNF3 inputs
and independent GNY2 backing. Its distinct manifest is
`sample-layer-product-fixture-manifest.json`. `VerifyNativeSampleLayeredProduct`
uses the unchanged original frame oracle, guard checks and complete wide GNY2
checks, emitting `NATIVE_SAMPLE_LAYERED_PRODUCT` markers. The input backing,
shader words, geometry, attachment formats, sample counts and expected pixels
are unchanged; all scene fragment records remain ABI1, including the 4× scene.
This qualifies original product work on the sample-capable host only. It does
not exercise or qualify SampleID/Position inputs or shader SampleMask output.
The sample manifest and rows explicitly retain `sampleBuiltinsQualified=false`.

The C++ implementations share an internal template with two fixed source
wrappers: `native_layered_product_driver.cpp` selects 001/104 and
`native_sample_layered_product_driver.cpp` selects 002/112. Neither parser
accepts the other's magic or discovery table. Both builders still name their
executable `native_product_driver`; build identity includes the selected
wrapper and common execution/fixture headers. The Python implementation uses
two explicit immutable contracts and distinct fixed entry scripts, rather
than inferring a profile from a count. Source/RTL/assertion/model identity,
locking, actual IRQ/GNX, drained traffic, exact retirement and full-frame clock
scopes retain the same guards. No old qualification transfers to this source.

```sh
PYTHONDONTWRITEBYTECODE=1 OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_layered_product.py
PYTHONDONTWRITEBYTECODE=1 OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_sample_layered_product.py
python3 -B scripts/test_qualify_native_layered_product.py
python3 -B scripts/test_qualify_native_sample_layered_product.py
python3 -B scripts/test_test_selection.py
```

Both C++ parser regressions pass ASan/UBSan, and both fixed executors pass
lightweight stub compilation. Python guards cover cross-profile and malformed
discovery rejection, fixed runner/producer selection, source dependencies,
original-ABI scope and separate/mixed verifier markers. The new Scala fixture
suite includes checks for old/new backing identity and unchanged command tails, exact
112-word serialization, distinct manifests and rejection of untouched input
as returned GPU output. It has not executed: the fresh-only whole-main build
exhausted its bounded 384 MiB heap, so no mixed dependency class set was run.
The producer and new/old fixture suites pass a 128 MiB direct Scala parser
check; this is neither a typecheck nor factory execution.
Fresh Scala-to-C++ fixture interoperability and all actual DUT work remain
pending. When a safe full build and model window becomes available:

```sh
python3 -B scripts/prepare_native_owner_flashsim.py --sample-resources \
  --stage prepare --output .artifacts/sample-layered-owner --java-heap 24G
python3 -B scripts/qualify_native_sample_layered_product.py --product \
  --output .artifacts/sample-layered-owner --flashsim-dir /absolute/path/to/FlashSim \
  --stage all --jobs 2 --shards 32 --precompiled-header --conservative-alias
```

No new model or RTL was generated by this change. Complete six-frame 640×480
execution on this exact factory and independent returned-frame verification
remain required; 32×32 smoke inputs cannot replace the product target. The
same 112-word factory has its own explicitly selected compute executor
contract. Neither the product nor compute source contract claims GLES
sample-policy/compiler closure, dynamic interpolation, physical timing or FPS.
