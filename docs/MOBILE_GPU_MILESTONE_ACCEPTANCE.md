# Mobile GPU milestone acceptance

The exit criteria in [the original H0–H8 table](MOBILE_GPU_HARDWARE_ALIGNMENT.md#implementation-milestones)
remain authoritative. Acceptance is per milestone. Later feature, rate and
physical gates remain required for the complete mobile GPU target.

| Milestone | Status | Acceptance boundary |
|---|---|---|
| H0 | Complete: contract and measurement foundation | Evidence below covers each original exit criterion. |
| H1 | Complete: one persistent tile engine | Ordered pass lifetime, dependency/fault boundaries, current synthesis and Linux integration pass. |
| H2 | Complete: scalable bins and two engines | Overflow/restart, ordering, balanced progress, double buffering/backpressure and attachment traffic are qualified. |
| H3–H8 | Open | Original functional, throughput, resource and physical exits remain required. |

## H0 acceptance, 2026-10-27

Hardware source baseline: `5696e97f9913b32becca98092cfe7705a104ac8b`.
The unit-label correction accompanying this record changes discovery metadata,
with identical capability encodings and C ABI. Final results belong here;
source manifests, raw logs and emission inventories remain local in `.artifacts/`.

| Original exit criterion | Evidence and result |
|---|---|
| Units and limits documented | [Mobile configuration](../src/main/scala/opengpu/config/MobileGpuConfig.scala), [resource-limit contract](MOBILE_GPU_RESOURCE_LIMITS.md), [capability inventory](MOBILE_GPU_CAPABILITY_MATRIX.md), versioned tile/native descriptors and generated discovery layouts distinguish implemented bounds from API requirements. GNC2 `STORAGE_BYTES` is the shared resident arena per engine. The [current full native inventory](NATIVE_FOUR_ENGINE_HOST.md#sram-capacity) records 3,594,240 physical macro bytes, including replicas/padding, separately from 2,634,162 inferred-array bits. |
| Supported elaboration combinations checked | Five current `MobileGpuConfigSpec` cases check 1/2/3/4-CU configurations, queue/payload sizing, tile classes and rejected combinations. Unmodified full four-engine native emitters pass at both 640×480 and explicitly enabled 1280×720, each producing 1,439 SV files. The legacy 64×64 compatibility host also elaborates successfully. These are the named supported profiles; this does not claim every constructor permutation. |
| Baseline counters explain stage utilization | The [six-scene compiled baseline](COMPILED_NATIVE_PRODUCT_QUALIFICATION.md#verified-development-result), hardware `cc6377b`, passes exact stored-image and traffic-drain checks and matches Scala GPU/service cycles, bus/enabled bytes and all six fragment phases. The current counter contract remains idle/collect, staging, launch/invalidate, run, read and emit/release worker-cycles. For textured 3D, 104,731 GPU clocks produce 418,924 worker-cycles across four workers: 254,709 idle/collect, 33,122 staging, 424 launch/invalidate, 119,692 run, 10,407 read and 570 emit/release. Run occupancy is 28.57%; this identifies substantial collection/staging/read overhead. It is a historical baseline, not current-profile performance qualification. |
| Analytic workload budgets available | Two current `MobileGpuBudgetSpec` cases verify exact ceilings for fractional sustained rates, rate provenance, exclusive serial work and frame budgets. A single 720p RGBA8 final store is 3,686,400 bytes, or 221,184,000 bytes/s at 60 FPS. A fitting service lower bound is necessary and does not prove frame rate. |

Reproduce the bounded configuration/budget checks:

```sh
sbt -J-Xmx2G 'testOnly opengpu.config.MobileGpuConfigSpec opengpu.config.MobileGpuBudgetSpec'
sbt 'runMain opengpu.elaboration.EmitNativeBinnedHostAbi --check'
```

The current audit passes all seven configuration/budget cases and the actual
Scala discovery generator/check. The C discovery header is byte-identical;
only the JSON `STORAGE_BYTES` unit changes. Complete-host emission commands and
heap requirements are in [the native profile](NATIVE_FOUR_ENGINE_HOST.md).
The six-scene baseline reproduction command is in [the compiled runner guide](COMPILED_NATIVE_PRODUCT_QUALIFICATION.md#reproduce).
These expensive local qualifications remain outside regular CI.

H0 records the known implementation gaps; it does not satisfy them. In
particular, the current 1280×720 product extent still falls short of the API's
2048-pixel renderbuffer requirement. Broad API limits/conformance, H3/H4 rates,
H5 transport, H6 product/physical qualification and H7/H8 features remain open.
The H2 record below assesses two-engine scaling. Current whole-host clock, six actual 720p workloads and 30/60 FPS
are unqualified. H0 acceptance does not accept the expanded resource budget as
a final product budget or promote an assumed clock to a measured clock.

## H1 acceptance, 2026-10-27

Current focused validation passes 71 distinct checks across configuration,
descriptor/control, persistent SRAM and rendering suites with the pinned SRAM
submodule. Ordered overlap, blending, depth/stencil,
load/clear/discard/store/resolve, partial clears, cancellation and fault drain
are covered. Clear-start rendering reads zero old attachment contents and writes
no intermediate attachment stores. The matched eight-credit component completes
936 stores in 6,161 clocks versus 35,631 at one credit, with identical 1,926-clock
draw work; this is a bounded word-service result.

Reproduce the 71 focused cases locally, including transfer-credit qualification:

```sh
git submodule update --init depends/asap7_sram_0p0
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.tileTransferQualification=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.config.MobileGpuConfigSpec opengpu.config.MobileGpuBudgetSpec' \
  'testOnly opengpu.graphics.PersistentTileAttachmentStoreSpec opengpu.graphics.BankedPersistentTileAttachmentStoreSpec opengpu.graphics.PersistentTileRenderPipelineSpec' \
  'testOnly opengpu.graphics.TileCommandPassControllerSpec opengpu.graphics.TileJobDescriptorReaderSpec opengpu.graphics.TypedTileCommandBufferSpec'
```

The fresh complete eight-credit store synthesis screen passes the 1,000 ps
TC/SLVT setup gate with core +123.159 ps and I/O +276.580 ps, zero skipped or
unresolved instances, and eight real SRAM macros. It does not carry an older
post-route result or establish the whole-host clock.

Six current system cases pass: unshared/shared AXI pass ownership, hot-texture
publication, first/last store and resolve write-fault recovery, the two 64×64
DRM images, and the actual 23-word compute replay in Bare/Sv32. The replay
retains its 20,000-cycle memory-service guard. One additional four-CU/16-lane
persistent-host case was canceled by its heap guard and is excluded from
acceptance; it provides no scaling evidence. Per-case results establish six
passes, zero failed tests and that one excluded case.

Run each system selector against its own suite so filtering cannot accidentally
include a larger configuration:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.BufferedPersistentTileHostAxiSpec -- -z "shared=false, 1 CUs"' \
  'testOnly opengpu.system.BufferedPersistentTileHostAxiSpec -- -z "shared=true, 2 CUs"' \
  'testOnly opengpu.system.BufferedPersistentTileTextureVisibilitySpec -- -z "shared=true, 2 CUs"' \
  'testOnly opengpu.system.PersistentTileStoreFaultSpec -- -z "shared=false"' \
  'testOnly opengpu.system.DrmFragmentImageAxiSpec' \
  'testOnly opengpu.system.DrmComputeProgramReplaySpec -- -z "CUs=1"'
```

The fresh complete Linux guest passes the original DRM smoke and powers down
cleanly. Its 64×64 legacy compatibility host retains four general 16-lane,
eight-warp CUs, private four-lane/two-warp graphics and every assertion.
Checks include vertex/fragment rendering, the event-chained compute program,
blit/fill/strided copy, all advertised resolve sample modes, MSAA rendering,
persistent-depth submissions, mapping-failure recovery, VM isolation, delayed
context teardown, fault queries and vblank flip ordering. The original
300-second fence/MSAA and 30-second flip budgets remain unchanged.

Two host-harness repairs make that result reproducible: use a private guest
DTB matching the emitted 64×64 viewport, and bound idle MMIO settle batches to
64 cycles. The reused machine object advertised 640×480 and rejected the small
render buffer; the original adapter also advanced 20,000 idle cycles after
individual register accesses. All 1,660 generated DUT/runtime object payloads
are unchanged. The software adapter alone is rebuilt. Physical and translated
compute probes preserve their complete output oracle; their 20,000 explicit
IRQ polls are not a total GPU-cycle bound because MMIO advances cycles too.
See [private guest preparation](ARTI_PRIVATE_GUEST_VALIDATION.md).

H1 closes the original one-engine functional exit. The H2 record below assesses
two-engine scaling and double buffering. Broader native feature/resource/
coherence coverage, sustained backend rates, current whole-host timing, full
physical qualification and six actual 720p workloads at 30/60 FPS remain required.

## H2 acceptance, 2026-10-28

Acceptance covers scalable GBJ1 bin pages and two independently progressing
tile engines at `249b631ed216` ([PR #120](https://github.com/OpenGPGPU/gpu/pull/120)),
including the bin-store and directory repairs in PRs #117/#118. It retains both attachment slots per engine, all eight
banked packet contexts and the existing RGBA8/D24S8 attachment capacities.

| Original H2 exit | Acceptance evidence |
|---|---|
| Overflow/spill/restart | Exact exhausted-prefix resume, discontiguous spill backing, automatic continuation at 1x/4x sampling, bounded/zero-progress termination and reload-fault invalidation preserve the reference stream. Six memory-submission cases also cover restart, malformed records, cancellation and shared-CU/Sv32 recovery. |
| Ordering | Replay retains per-engine order under backpressure. Integrated depth/stencil/blend images, batches that leave an older tile untouched, partial 4x samples and guard pixels match their exact ordered references. |
| Load balancing and independent progress | The scheduler completes four tiles as two per engine with all twelve references. A second case requires simultaneous engine activity. Withheld results/attachment acknowledgements leave the other engine progressing. Shared-CU/Sv32 execution uses both engines and recovers faults. |
| Double buffering and backpressure | Current attachment tests preserve separate slot ownership through held exports, cancellation, late faults and reuse. The resident SRAM check retains 512 samples and 128 consecutive four-commit cycles. Transfer tests cover held/replaced private acknowledgements, offered faults, exact drain and healthy reuse. |
| Attachment bytes/frame | The ordered 16x8 banked frame performs zero attachment loads and 1,024 useful store bytes. The partial 13x7 4x frame stores 3,276 useful bytes, including 364 resolve bytes. The earlier 72x72 paged frame stores 41,472 useful bytes across 81 tiles/243 draws; a held-export four-tile case measures 1,800 load and 1,800 store bytes. These counters measure useful attachment traffic, separately from payload traffic and padded AXI transfers. |

The control qualification covers 95 distinct outcomes: 42 fresh bin-store/
directory cases and 53 cases carried through unchanged test and DUT sources.
Twenty earlier integrated outcomes are retained from `0c603c2`; nineteen
completed before a 3 GiB heap failure and the remaining tall shared-CU/Sv32
case passed in a fresh 4.5 GiB JVM. The failed aggregate process is excluded.
Current banked qualification adds 54 functional outcomes and three composed
cases: real-SRAM ordered rendering, partial 4x resolve and actual shared-CU/Sv32
execution. This states each evidence boundary without treating earlier complete
host runs as new runtime qualification of the changed banked implementation.

Two earlier 32x16 resource checks retain four 16-lane/eight-warp CUs, a 128 KiB
L2, real SRAM and the 64 KiB payload arena, including a full 128-thread FP32/
texture batch. Their [local reproduction selectors](DEVELOPMENT_TESTING.md#full-mobile-binned-host-qualification)
remain outside PR/nightly CI. Routine rendering stays at or below 640x480.

All ten H2 component synthesis screens pass 1,000 ps TC/SLVT with 25% I/O,
standard ABC, real characterized SRAM and zero skipped/unresolved logic,
retiming, mocks or false paths. Nine control/memory emissions retain their
qualified RTL bytes. The [complete banked store](BANKED_TILE_ATTACHMENTS.md)
contains 36 modules/32 SRAM macros and passes with core +19.639 ps and I/O
+46.698 ps. This is component setup timing; the current whole-host clock and
full physical reports remain unqualified.

Reproduce the bounded controls and memory-submission checks locally:

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 \
  'testOnly opengpu.graphics.BinPagePoolSpec opengpu.graphics.PrimitiveBinReaderSpec opengpu.graphics.PrimitiveBinWriterSpec opengpu.graphics.PrimitiveBinStoreSpec opengpu.graphics.TileBinDirectorySpec opengpu.graphics.PagedTileBinDirectorySpec opengpu.graphics.TileBinReplaySchedulerSpec opengpu.graphics.TileBinContinuationControllerSpec'
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'testOnly opengpu.graphics.MemoryTileBinRenderPipelineSpec'
```

The [banked attachment guide](BANKED_TILE_ATTACHMENTS.md) reproduces the 54
component outcomes and synthesis. Run the three composed cases explicitly:

```sh
sbt -J-Xmx4608m -Dopengpu.simJobs=1 -Dopengpu.bankedBins=true \
  -Dopengpu.productQualification=0 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  'testOnly opengpu.graphics.TileBinRenderPipelineSpec -- -t "should render interleaved bins on two persistent engines and match the ordered depth stencil blend image with SRAM macros=true" -t "should render partial 4x tiles and resolve exact samples without touching guard pixels" -t "should render both tile engines through actual shared CUs with Sv32=true height=16 and recover faults continuing=false"'
```

H3–H8 remain open. Native four-engine composition, wider formats and resource
limits, general coherence, sustained backend rates, feature/API coverage,
whole-host timing, full physical qualification and six actual 720p workloads
at 30 then 60 FPS remain requirements of the complete target. Seven additional
host publication/fault/reset outcomes are validated on baseline `0c603c2`;
the original heap-failed aggregate process remains excluded.
