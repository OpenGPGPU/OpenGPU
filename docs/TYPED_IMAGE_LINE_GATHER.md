# Native image line gathering

`TypedTileImageTransfer` gathers disjoint stored or resolved chunks into one
64-byte masked write. A line change, overlap, operation change, full buffer or
required external read flushes the buffer. Offered requests remain immutable.
Only successful physical acknowledgements commit byte/pixel counts; cancellation
discards only unissued buffered data. Owner identity, the single outstanding
transaction and all supported formats remain unchanged.

Seventeen transfer checks and two staged-admission checks pass, covering all 36
color formats, packed and unaligned views, 1x/2x/4x samples, allocation guards,
exclusive 4 GiB ends, permissions, aliases, faults and cancellation. A 16×16
RGBA8 stored-and-resolved case issues 32 complete lines with exactly 2,048
enabled bytes and intact guards.

The complete transfer maps without omitted or unresolved logic and passes
1000 ps synthesis at ASAP7 TC/SLVT with 25% I/O delay: +34.459 ps core setup
and +441.687 ps I/O setup. The gather chunk length is captured in the existing
address-preparation phase, adding no pipeline clock. These are component,
synthesis-only results; enclosing renderer/host timing, whole-product resources,
representative frame rates and the H0–H8 gates require separate qualification.

The enclosing shared-CU fixture derives final publication from the independent
set of active image bytes. It rejects duplicate or out-of-image write strobes
and holds the acknowledgement carrying the final bytes regardless of request
grouping. Exact backing images, cached visibility, fault/cancel drain and VM
ownership assertions remain in place.

```sh
sbt -J-Xmx4G -Dopengpu.productQualification=0 -Dopengpu.simJobs=2 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simSeparateBuild=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.StagedTypedImageAdmissionSpec opengpu.graphics.TypedTileImageTransferSpec'
sbt -J-Xms512M -J-Xmx4G -J-XX:+UseG1GC \
  -J-XX:MinHeapFreeRatio=10 -J-XX:MaxHeapFreeRatio=20 \
  -Dopengpu.productQualification=0 -Dopengpu.simJobs=2 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit \
  'testOnly opengpu.graphics.TypedTileImagePipelineSpec'
```

For synthesis, emit `mobile-typed-image-transfer` with `EmitPpaRtl`, then run
`scripts/qualify_ppa.py <rtl-dir> TypedTileImageTransfer --clock clock
--period-ps 1000 --io-delay .25 --output <output-dir>` with the documented
ASAP7 environment. See [staged admission](STAGED_TYPED_IMAGE_ADMISSION.md)
for the complete reproducible emission and synthesis commands.

Use the [explicit full SRAM smoke commands](EXPLICIT_NATIVE_PRODUCT_FRAMES.md)
for local-only enclosing replay. Routine images remain at or below 640×480;
720p workloads require explicit product qualification. Occupied bus bytes and
enabled write bytes measure traffic, not SRAM capacity or occupancy.
