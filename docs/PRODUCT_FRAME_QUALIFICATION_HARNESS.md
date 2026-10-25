# Product-frame qualification backing memory

The bounded native AXI fixture reserves fixed 64 KiB image slots. Those slots
are disjoint for its original 32×32 cases, but cannot be reused by merely
increasing the frame dimensions. Keep those validated fixtures unchanged.

`NativeFrameMemory.layout` sizes separate stored and resolve ranges from each
format, pitch, sample count and full height. It retains unaligned bases, row
padding and guards, and rejects allocations beyond the exclusive 4 GiB limit.
`PagedFrameMemory` represents uniform guards without allocating every byte;
modified pages use 4 KiB byte arrays. Its page-byte count excludes JVM overhead.
Snapshots copy touched pages independently, and line writes preserve masked bytes.

`NativeFrameImage.verify` streams independent caller-supplied stored samples and
resolve values, checking every active byte, row pad and guard without building
a second per-byte expected-image map. The caller must supply a workload-specific
reference; this helper does not derive expected pixels from the DUT.

Nine bounded checks pass, covering mixed-format 1x/2x/4x allocation metadata,
address boundaries, 300 randomized masked AXI lines, snapshots and corruptions
in stored images, resolves, padding and both guard ends. Oracle data fixtures
use 32×32 images; larger dimensions exercise allocation metadata and bounded
address/guard probes without rendering.

```sh
sbt -J-Xmx4G -J-XX:+ExitOnOutOfMemoryError \
  'testOnly opengpu.testutil.NativeFrameMemorySpec'
```

This is backing-memory and oracle infrastructure. Connecting an explicit
product driver, implementing the agreed workload suite and measuring complete
720p frames at a qualified host clock remain required. It provides no GPU
runtime, memory-usage, performance or 30 FPS qualification.
