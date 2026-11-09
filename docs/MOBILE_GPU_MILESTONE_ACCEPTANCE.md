# Mobile GPU milestone acceptance

The exit criteria in [the original H0–H8 table](MOBILE_GPU_HARDWARE_ALIGNMENT.md#implementation-milestones)
remain authoritative. Acceptance is per milestone. Later feature, rate and
physical gates remain required for the complete mobile GPU target.

| Milestone | Status | Acceptance boundary |
|---|---|---|
| H0 | Complete: contract and measurement foundation | Evidence below covers each original exit criterion. |
| H1 | Complete: one persistent tile engine | Ordered pass lifetime, dependency/fault boundaries, current synthesis and Linux integration pass. |
| H2 | Complete: scalable bins and two engines | Overflow/restart, ordering, balanced progress, double buffering/backpressure and attachment traffic are qualified. |
| H3 | Complete: banked pixels, ordered hazards and legal early rejection | Production sample rates, adversarial ordering, actual early/late rendering and complete component synthesis timing pass. |
| H4 | Complete: banked texture clusters and native resource descriptors | Production cache-hit rate, miss/translation stress, actual banked bindings, render-to-texture visibility and complete component synthesis timing pass. |
| H5 | Complete: shader payload/export and GPU geometry transport | Common resident staging, indexed/instanced reuse, fault drain and task progress satisfy the original exit. |
| H6 | Open | Required feature and API coverage, the six-scene 720p product run and whole-host physical timing remain required. Component results do not establish product frame rate or clock. |
| H7 | Open | Compute, storage, image, atomic, barrier and indirect paths have scoped integration tests, including a [two-CU Linux resource guest](COMPUTE_SHARED_PROTECTION.md) and a [four-CU native coexistence run](COMPUTE_RESOURCE_COEXISTENCE.md#four-cu-coexistence) retaining the complete mandatory set on all four stock CUs. On current main a rebuilt Mesa 22.3.6 OpenGPU DRI driver booted the bounded ARTI guest and passed compute, vertex draw, ALU and resolve examples through the real DRM/Mesa API path (four lanes, two warps). General resource/barrier semantics, cross-domain ordering, full API/compiler integration, whole-host timing and physical qualification remain required. See the [3.1 matrix](MOBILE_GPU_CAPABILITY_MATRIX.md#later-capability-stages). |
| H8 | Open | Bounded isoline, triangle and quad jobs have [three-domain host tests](TESSELLATION.md#quad-tessellation-evaluation-handoff) with rotation/reflection invariance. [Native transport](TESSELLATION.md#native-tessellation-command-transport), clamp-to-border sampling, cube-array sampling, the three GLSL ES gather forms, all fifteen KHR advanced blend equations with blend-barrier ordering, ASTC LDR decoding and sampling, stencil-only sampling, region copies, per-sample shading, context robustness, primitive bounding boxes, explicit interpolation locations, and bounded debug stores are locally qualified. General provenance, writer protection, native geometry admission, sample-rate shader execution/merging, tile-pipeline blend integration, shader/ISA and API exposure and the remaining [3.2 feature gaps](GLES32_HARDWARE_GAPS.md) remain required. |

## H0 acceptance, 2026-10-27

Hardware source baseline: `5696e97f9913b32becca98092cfe7705a104ac8b`.
Final results belong here; source manifests, raw logs and emission
inventories remain local in `.artifacts/`.

| Original exit criterion | Evidence and result |
|---|---|
| Units and limits documented | [Mobile configuration](../src/main/scala/opengpu/config/MobileGpuConfig.scala), [resource-limit contract](MOBILE_GPU_RESOURCE_LIMITS.md), [capability inventory](MOBILE_GPU_CAPABILITY_MATRIX.md), versioned tile/native descriptors and generated discovery layouts distinguish implemented bounds from API requirements. |
| Supported elaboration combinations checked | `MobileGpuConfigSpec` checks 1/2/3/4-CU configurations, queue/payload sizing, tile classes and rejected combinations. Full four-engine native emitters pass at both 640x480 and explicitly enabled 1280x720; the legacy 64x64 compatibility host also elaborates. |
| Baseline counters explain stage utilization | The [six-scene compiled baseline](COMPILED_NATIVE_PRODUCT_QUALIFICATION.md#verified-development-result) passes exact stored-image and traffic-drain checks and matches Scala GPU/service cycles, bus/enabled bytes and all six fragment phases. The counter contract is idle/collect, staging, launch/invalidate, run, read and emit/release worker-cycles; run occupancy identifies substantial collection/staging/read overhead. Historical baseline, not current-profile performance qualification. |
| Analytic workload budgets available | `MobileGpuBudgetSpec` verifies exact ceilings for fractional sustained rates, rate provenance, exclusive serial work and frame budgets. A single 720p RGBA8 final store is 3,686,400 bytes, or 221,184,000 bytes/s at 60 FPS. A fitting service lower bound is necessary and does not prove frame rate. |

Reproduce the bounded configuration/budget checks:

```sh
sbt -J-Xmx2G 'testOnly opengpu.config.MobileGpuConfigSpec opengpu.config.MobileGpuBudgetSpec'
sbt 'runMain opengpu.elaboration.EmitNativeBinnedHostAbi --check'
```

The current audit passes all seven configuration/budget cases and the actual
Scala discovery generator/check; the C discovery header is byte-identical.
Complete-host emission commands and heap requirements are in [the native
profile](NATIVE_FOUR_ENGINE_HOST.md). These expensive local qualifications
remain outside regular CI.

H0 records the known implementation gaps; it does not satisfy them. In
particular, the current 1280x720 product extent still falls short of the API's
2048-pixel renderbuffer requirement. Broad API limits/conformance, H6
product/physical qualification and H7/H8 features remain open. Current
whole-host clock, six actual 720p workloads and 30/60 FPS are unqualified.

## H1 acceptance, 2026-10-27

Focused validation covers configuration, descriptor/control, persistent SRAM
and rendering suites. Ordered overlap, blending, depth/stencil,
load/clear/discard/store/resolve, partial clears, cancellation and fault drain
are covered. Clear-start rendering reads zero old attachment contents and writes
no intermediate attachment stores.

Reproduce the 71 focused cases locally, including transfer-credit qualification:

```sh
git submodule update --init depends/asap7_sram_0p0
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.tileTransferQualification=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.config.MobileGpuConfigSpec opengpu.config.MobileGpuBudgetSpec' \
  'testOnly opengpu.graphics.PersistentTileAttachmentStoreSpec opengpu.graphics.BankedPersistentTileAttachmentStoreSpec opengpu.graphics.PersistentTileRenderPipelineSpec' \
  'testOnly opengpu.graphics.TileCommandPassControllerSpec opengpu.graphics.TileJobDescriptorReaderSpec opengpu.graphics.TypedTileCommandBufferSpec'
```

The complete eight-credit store synthesis screen passes the 1,000 ps
TC/SLVT setup gate with zero skipped or unresolved instances and eight real
SRAM macros. It does not establish the whole-host clock.

Six system cases pass: unshared/shared AXI pass ownership, hot-texture
publication, first/last store and resolve write-fault recovery, the two 64x64
DRM images, and the actual compute replay in Bare/Sv32.

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
cleanly, including vertex/fragment rendering, the event-chained compute program,
blit/fill/strided copy, all advertised resolve sample modes, MSAA rendering,
persistent-depth submissions, mapping-failure recovery, VM isolation, delayed
context teardown, fault queries and vblank flip ordering.
See [private guest preparation](ARTI_PRIVATE_GUEST_VALIDATION.md).

H1 closes the original one-engine functional exit. Broader native feature/resource/
coherence coverage, sustained backend rates, current whole-host timing, full
physical qualification and six actual 720p workloads at 30/60 FPS remain required.

## H2 acceptance, 2026-10-28

Acceptance covers scalable GBJ1 bin pages and two independently progressing
tile engines, retaining both attachment slots per engine, all eight
banked packet contexts and the existing RGBA8/D24S8 attachment capacities.

| Original H2 exit | Acceptance evidence |
|---|---|
| Overflow/spill/restart | Exact exhausted-prefix resume, discontiguous spill backing, automatic continuation at 1x/4x sampling, bounded termination and reload-fault invalidation preserve the reference stream. Memory-submission cases also cover restart, malformed records, cancellation and shared-CU/Sv32 recovery. |
| Ordering | Replay retains per-engine order under backpressure; integrated depth/stencil/blend images, partial 4x samples and guard pixels match their exact ordered references. |
| Load balancing and independent progress | The scheduler completes four tiles as two per engine. Withheld results/acknowledgements leave the other engine progressing; shared-CU/Sv32 execution uses both engines and recovers faults. |
| Double buffering and backpressure | Attachment tests preserve separate slot ownership through held exports, cancellation, late faults and reuse. Transfer tests cover held/replaced acknowledgements, offered faults, exact drain and healthy reuse. |
| Attachment bytes/frame | Eligible clear/discard-start passes perform zero attachment loads; partial frames store only useful bytes including resolve bytes. Counters measure useful attachment traffic separately from payload traffic and padded AXI transfers. |

Two 32x16 resource checks retain four 16-lane/eight-warp CUs, a 128 KiB
L2, real SRAM and the 64 KiB payload arena, including a full 128-thread FP32/
texture batch. Their [local reproduction selectors](DEVELOPMENT_TESTING.md#full-mobile-binned-host-qualification)
remain outside PR/nightly CI. Routine rendering stays at or below 640x480.

All H2 component synthesis screens pass 1,000 ps TC/SLVT with 25% I/O,
standard ABC, real characterized SRAM and zero skipped/unresolved logic.
The [complete banked store](BANKED_TILE_ATTACHMENTS.md) contains 36 modules/32
SRAM macros and passes with core +19.639 ps and I/O +46.698 ps. This is
component setup timing; the current whole-host clock and full physical
reports remain unqualified.

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

H6-H8 remain open. Native four-engine composition, wider formats and
resource limits, general coherence, sustained backend rates, feature/API coverage,
whole-host timing, full physical qualification and six actual 720p workloads
at 30 then 60 FPS remain requirements of the complete target.

## H3 acceptance, 2026-10-30

Acceptance covers the banked pixel backend and early-rejection exit.
The native owner retains 64 quad metadata rows, 128 packet contexts, eight
word ports and 32 KiB of canonical attachment SRAM.

| Original H3 exit | Acceptance evidence |
|---|---|
| Pixel throughput | The complete production RTL sustains four sample updates per clock at 1x/2x/4x MSAA (complete-quad intervals of 1/2/4 clocks); exact canonical words and logical counters match. |
| Adversarial same-sample ordering | Packet-window/expander/worker cases cover alias ownership through terminal acknowledgement, reverse completions, full rings, refill, wraps and held outputs. Owner checks cover fault-edge ingress, immediate cancellation, descriptor-preparation cancellation cuts, ordered drain and healthy reuse. |
| Early/late equivalence | Actual-CU rendering at 1x/4x produces identical canonical color/depth/stencil words and sample retirements. Each certified occluded draw removes eight shader launches and 64 shaded lanes; discard, depth override and side-effect shaders retain their required execution. |
| Physical timing | Complete pixel and early-depth fragment synthesis pass 1,000 ps TC/SLVT with 25% I/O (pixel +13.620/+44.058 ps; fragment +80.809/+71.224 ps core/I/O), with complete setup coverage and no unresolved/skipped logic, retiming, false paths or mocked memory. |

Eight current hardware outcomes pass with no skipped, canceled or pending cases.

Run the focused owner cases locally:

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simHierarchy=Rgba8BlendFmaEngine -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'testOnly opengpu.graphics.NativeSampleTypedTilePixelBackendSpec -- -z "reject invalid layouts" -z "cancel descriptor preparation" -z "drain a faulting generic owner" -z "overlap native quads" -z "bound a stalled native prefix"'
```

The [production rate guide](NATIVE_PRODUCTION_PIXEL_RATE.md) reproduces the two
literal-RTL cases. The [early-depth guide](CONSERVATIVE_EARLY_DEPTH.md) reproduces
the paired renderer and supporting checks. Reproduce complete component
emission and synthesis with fresh output directories:

```sh
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-public-frame-pixel-child .artifacts/h3-pixel-rtl'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-native-early-depth-fragment-shader .artifacts/h3-fragment-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/h3-pixel-rtl TypedTilePixelBackend --stage synthesis \
  --synthesis-engine yosys --yosys-preserve-hierarchy --period-ps 1000 \
  --io-delay 0.25 --output .artifacts/h3-pixel-synthesis
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/h3-fragment-rtl FragmentShaderStage --stage synthesis \
  --synthesis-engine yosys --yosys-preserve-hierarchy --period-ps 1000 \
  --io-delay 0.25 --output .artifacts/h3-fragment-synthesis
```

This acceptance uses the synthesis-only evaluation stage. It does not
establish routed setup/hold, a whole-GPU clock, area/energy closure, broader
format rates, or six actual 720p workloads at 30 then 60 FPS. Those remain
open with H6/H7/H8.

## H4 acceptance, 2026-10-30

The texture response window stores payloads in characterized SRAM with
bank-limited writes and prefetched publication-order heads. Each CU-pair
cluster retains an 8 KiB data cache, sixteen fills, four page-walk IDs and
128 logical response rows; the production pool retains five configured
clients.

| Original H4 exit | Acceptance evidence |
|---|---|
| Cache-hit throughput | Both physical clusters sustain one bilinear RGBA8 sample per clock (two aggregate samples per clock) with exact lane results and zero warm external reads, using all 28 characterized SRAM models. |
| Miss/translation stress | Registered service/pool maintenance, Sv32 fault and flush boundaries, late fills, cancellation, held responses and healthy reuse pass; trilinear, miss, same-bank and dependent streams preserve exact vector words and read charges at separately reported lower rates. |
| Multiple bindings | Both real native stages execute 128 invocations with all 32 distinct bindings per stage, including successful nonfallback replies through the five-client banked pool and both physical clusters. Independent stage roots, exclusive 4 GiB table limits, index-32 faults, peer progress, held completions and reuse pass. |
| Render-to-texture visibility | Both 1x/4x modes pass actual GPU frames: a queued consumer waits for delayed producer-store acknowledgements with unchanged descriptors; every stored sample, resolve and guard matches, and VM/payload/memory ownership drains. |
| Physical timing | Complete 1,000 ps synthesis with 25% I/O passes: vertex +78.150/+116.522 ps, fragment +81.688/+98.809 ps, pool (104 definitions, 28 SRAMs) +77.644/+91.103 ps core/I/O. No skipped implementations, retiming, false paths, mocked SRAMs or OOM are accepted. |

All current hardware cases pass with zero errors or skips. Complete physical
results carry only across audited identical RTL.

Run the 35 component cases and cold-window case locally:

```sh
sbt -J-Xmx5G -J-XX:ActiveProcessorCount=2 -Dopengpu.productQualification=0 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'testOnly opengpu.graphics.NativeRgba8TextureSharedWindowSpec opengpu.graphics.NativeTextureResponseBankSpec opengpu.graphics.TranslatedBankedRgba8TextureServiceSpec opengpu.graphics.NativeRgba8TextureServiceSpec opengpu.graphics.NativeRgba8TextureSharedPoolSpec opengpu.graphics.TextureBilinearFilterPipelineSpec opengpu.graphics.NativeRgba8TextureColdWindowSpec'
```

The [binding guide](NATIVE_TEXTURE_BINDING_CAPACITY.md),
[production rate guide](NATIVE_PRODUCTION_TEXTURE_RATE.md),
[characteristic measurements](NATIVE_TEXTURE_CHARACTERISTIC_RATES.md) and
[visibility guide](NATIVE_RENDER_TEXTURE_VISIBILITY.md) reproduce the remaining
cases and state their limits.

For complete component synthesis, first emit and prepare the production model
using the rate guide. Its `vertex-closure`, `fragment-closure` and
`cluster-closure` directories contain the three full component hierarchies:

```sh
for component in vertex fragment cluster; do
  case "$component" in
    vertex) module=NativeShaderTextureUnit ;;
    fragment) module=NativeShaderTextureUnit_1 ;;
    cluster) module=NativeRgba8TextureSharedPool ;;
  esac
  CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
    ".artifacts/native-rate-model/${component}-closure" "$module" \
    --stage synthesis --synthesis-engine yosys --yosys-preserve-hierarchy \
    --period-ps 1000 --io-delay 0.25 --output ".artifacts/h4-${component}-synthesis" || exit
done
```

This closes the original H4 component exit under the synthesis-only policy.
A whole-GPU clock, routed setup/hold, broader API and resource visibility,
and the six 720p workloads at 30 then 60 FPS remain H6-H8 requirements.

## H5 acceptance, 2026-10-28

Acceptance covers the on-chip shader payload/export and GPU geometry fetching
exit. Six bounded two-CU cases verify the criteria below; the full four-CU
host is separately elaborated at 640x480.

| Original H5 exit | Acceptance evidence |
|---|---|
| No mandatory per-fragment global staging in the common path | The composed indexed/instanced vertex-to-fragment check runs 1x/4x sampling with exact FP32/raw typed exports and asserts zero global payload-staging requests for both stages. Payload leases and queued task pins remain live through a held final export. |
| Index/instance reuse | Actual-CU cases compare local/global payload modes over 32 vertex exports, two instances and two restarts. Opt-in post-transform reuse reduces shader batches from four to two with identical ordered exports. Reuse requires the documented pure-shader/immutable-resource promise. |
| Fault drain | Composed source-memory, fragment-memory, shader-trap and numeric faults preserve attribution and drain both memory clients before reuse. Cancellation retains ownership until accepted work, task pins and payload leases drain. |
| Task progress | A one-slot pool holds an offered vertex release while the fragment task uses controlled global spill and produces its exact output; releasing the vertex owner permits full drain and healthy reuse. |

The [lazy payload contract](LAZY_SHADER_PAYLOAD_INITIALIZATION.md) covers the
eager/lazy contract, transport and real-CU cases and the native-capacity pool
check. The 512 KiB physical pool retains twelve ports, thirty-one task pins
and all seven reservations. Complete vertex and fragment closures extracted
from the current host match their qualified hardware; standalone shader
emissions retain additional counters and are not substituted for the actual
host components.

| Complete component | Core / I/O setup margin (ps) | Reachable modules / real SRAM macros |
|---|---:|---:|
| Standalone native payload pool | +72.607 / +44.124 | 11 / 512 |
| Actual native vertex stage | +68.111 / +46.169 | 130 / 18 |
| Each actual native fragment worker | +61.950 / +50.283 | 122 / 14 |

These are component synthesis results at 1,000 ps TC/SLVT, 25% I/O, with
zero skipped/unresolved logic, retiming, memory mocks or false paths. They
establish no enclosing CU/interconnect/host clock, hold/route/power result
or product frame rate. Raw logs remain local under `.artifacts/`.

Reproduce the six bounded actual-CU cases locally:

```sh
git submodule update --init depends/asap7_sram_0p0
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.productQualification=0 \
  'testOnly opengpu.graphics.VertexShaderStageSpec -- -z "reuse pure indexed"' \
  'testOnly opengpu.graphics.VertexFragmentPipelineSpec -- -z "shared CU pool local=true" -z "spill fragments and make progress while a one-slot pool" -z "retain queued launches and held final exports through cancellation" -z "retain source and fragment fault attribution and drain both memory clients"'
```

The payload guide reproduces the retained contract/full-capacity checks and
pool timing. For actual worker timing, emit
`EmitPpaRtl mobile-native-four-engine-host <directory>` with `sbt -J-Xmx6G`,
extract `VertexShaderStage` and `FragmentShaderStage` through
`FragmentShaderStage_3` using `scripts/extract_emitted_rtl.py`, then use the
[complete-component synthesis command](FRAGMENT_QUAD_EXPORTS.md) separately
for each closure. The new `mobile-public-frame-vertex-shader` and
`mobile-public-frame-fragment-shader` targets expose native-profile standalone
stages with all counters; their emissions are not the pruned
host timing reference.
For host-pool timing, also extract `BankedShaderPayloadStore` from that same
complete host emission. Do not substitute `mobile-public-frame-shader-payload`.

H5 closes the original transport/geometry exit. Whole-host physical qualification
and six actual 720p workloads at 30 then 60 FPS remain required with H6.
