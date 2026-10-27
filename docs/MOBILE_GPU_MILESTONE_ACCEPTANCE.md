# Mobile GPU milestone acceptance

The exit criteria in [the original H0–H8 table](MOBILE_GPU_HARDWARE_ALIGNMENT.md#implementation-milestones)
remain authoritative. Acceptance is per milestone. Later feature, rate and
physical gates remain required for the complete mobile GPU target.

| Milestone | Status | Acceptance boundary |
|---|---|---|
| H0 | Complete: contract and measurement foundation | Evidence below covers each original exit criterion. |
| H1 | Open: final integration validation | Focused attachment checks and synthesis pass; current system and Linux guest checks must finish. |
| H2–H8 | Open | Original functional, throughput, resource and physical exits remain required. |

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
2048-pixel renderbuffer requirement. Broad API limits/conformance, H2 scaling,
H3/H4 rates, H5 transport, H6 product/physical qualification and H7/H8 features
remain open. Current whole-host clock, six actual 720p workloads and 30/60 FPS
are unqualified. H0 acceptance does not accept the expanded resource budget as
a final product budget or promote an assumed clock to a measured clock.

## H1 remaining acceptance

Current focused validation passes 71 distinct checks across configuration,
descriptor/control, persistent SRAM and rendering suites. Three initial missing
SRAM-model setup failures are covered by passing runs with the pinned submodule;
they are not counted as skips. Ordered overlap, blending, depth/stencil,
load/clear/discard/store/resolve, partial clears, cancellation and fault drain
are covered. Clear-start rendering reads zero old attachment contents and writes
no intermediate attachment stores. The matched eight-credit component completes
936 stores in 6,161 clocks versus 35,631 at one credit, with identical 1,926-clock
draw work; this is a bounded word-service result.

The fresh complete eight-credit store synthesis screen passes the 1,000 ps
TC/SLVT setup gate with core +123.159 ps and I/O +276.580 ps, zero skipped or
unresolved instances, and eight real SRAM macros. It does not carry an older
post-route result or establish the whole-host clock.

H1 stays open until the current AXI dependency/texture publication, write-fault,
DRM image/replay checks and fresh Linux guest smoke have terminal results. Do
not substitute model compilation or an old guest image for those results.
