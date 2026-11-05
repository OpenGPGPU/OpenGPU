# Native product primitive storage

The 720p small-triangle recipe contains 14,400 triangles. A complete native
window primitive record occupies 1,472 bytes, so even unclipped geometry needs
21,196,800 bytes. The previous fixed 16 MiB arena held only 11,397 records.
Its actual failed product snapshot contains exactly that complete prefix and
all 4,096 arena pages; the producer then rejects the next out-of-bounds record.

Product input generation now derives record storage from the actual native
record layout and the complete input primitive count. It reserves the clipper's
upper bound of seven output triangles per input triangle. The small-triangle
product recipe therefore reserves 148,377,600 bytes in a disjoint GPU address
range beginning at `0x60000000`. Untouched pages remain sparse. The ordinary
bin pool, API bin pool, shared-memory aperture and image allocations remain
separate, with the existing complete-region overlap checks still enforced.
Small fixtures keep their original 16 MiB allocation and addresses.

Command generation validates the encoded arena bounds and capacity before
publishing commands or beginning RTL emission. Negative counts, arithmetic
overflow beyond the 32-bit address space, unaligned bounds and reversed ranges
are rejected. Workload geometry, shader programs and product RTL capacities
are retained.

The focused checks use small recipes and numeric capacity boundaries, including
the 14,400-triangle budget without constructing or rendering a large frame.
All thirteen 32×32 fixture files remain byte-identical to the previous profile.
Explicit 1280×720 fixture generation checks the complete six-scene input set;
it is not a completed frame or performance qualification. Existing product runs
using the undersized arena do not establish six-scene acceptance.

Reproduce the local checks:

```sh
sbt -Dopengpu.productQualification=0 \
  'testOnly opengpu.graphics.NativeProductPrimitiveArenaSpec opengpu.graphics.NativeProductFrameInputsSpec opengpu.graphics.NativeProductWorkloadsSpec opengpu.graphics.NativeProductFrameReferenceSpec'
sbt -Dopengpu.fullHostQualification=1 -Dopengpu.productQualification=1 \
  'Test/runMain opengpu.system.PrepareCompiledNativeProduct --product --output .artifacts/product-arena-inputs --fixtures-only'
```

Actual product qualification still requires all six full-capacity RTL scenes,
independent stored-image and guard checks, a qualified physical clock and the
30/60 FPS budgets. H6–H8 remain open.
