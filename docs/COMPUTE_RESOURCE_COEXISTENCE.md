# Native compute resource coexistence

`QualifyComputeResourceCoexistence` is an explicit local qualification of one
complete default compute unit: sixteen lanes, eight warps, 128 work-items,
SRAM-backed register/shared storage, the enabled FPU backend and default
instruction/vector cache capacities. The same model includes the default
256-set, four-way, four-bank shared L2 with real SRAM arrays. Uniform, texture,
image, storage, counter, image-atomic and storage-atomic services are enabled
together. Geometry and tessellation belong to other shader stages.

One admitted task retains the following active set through completion:

| Resource | Retained capacity and executed access |
|---|---|
| Uniform blocks | Twelve simultaneous 16 KiB views, with independent scalar first words and vector last words; one view ends at 4 GiB. |
| Default uniforms | All 1024 components execute through divergent offsets, alongside the blocks: 50176 combined components. |
| Textures/samplers | Sixteen distinct binding, image, level and sampler records execute nearest R32UI sampling and typed coordinate fetches. |
| Images | Four R32UI units execute loads, stores and atomic adds. |
| Storage | Four simultaneous 128 MiB views execute checked loads/stores and atomic adds; independent last words exercise sparse view endpoints. |
| Atomic counters | One 32-byte view contains eight counters; 128 work-items contend, read the post-increment values, decrement and verify restoration. |
| Shared memory | All 4096 words of the 16 KiB SRAM receive resource-dependent values; after a workgroup barrier, a different warp reads every producer's words. |

The fixture supplies sparse byte memory below the actual L2. Ninety real Sv32
leaf mappings retain descriptor, uniform, texture/image/storage, counter and
coordinate pages. Large storage views map their first and last pages rather
than allocating or traversing 512 MiB. The block interiors are likewise not
exhaustively traversed. Resource services capture the task publications and
Sv32 root; live inputs are poisoned immediately after admission.

Sampling and fetch results come from the internal `ComputeTextureCuService`,
`NativeShaderTextureUnit`, sampler/image descriptor readers and texture data
pipeline. External scalar/vector texture handoffs remain idle. Counter,
storage and image atomics execute in the actual global translator and L2;
the lower-memory responder does not manufacture atomic results. It delays
writes by 31 cycles and updates bytes only when their responses are accepted.
Lower responses may reorder and held memory offers must remain stable.

Uniform, FPU and texture results feed a per-lane checksum. An initial barrier
keeps all shared-coordinate texture readers ahead of the first aliasing writer.
A storage store and an image load use different virtual views of the same
physical words; an image store consumes that actual loaded value and is then
observed through storage and texture views. Storage and image atomic adds
update those words before a further texture fetch. Fences and
convergent workgroup barriers separate these exchanges. The checked ordinary
texture/image/storage clients use uncached line traffic; AMOs use the translated
cache policy and real L2 serialization. This evidence covers these particular
views and instruction sequences, not general sampler-cache or host coherence.

Four kernels run without reset: full capacity, the first out-of-view storage
word, a denied texture-data mapping, then full capacity again with fresh page
tables, physical backing, generation and data. Faults retain their precise
instruction/virtual address and lane mask; each of eight distinct warps must
trap exactly once on an accepted handshake. Faulting destinations and forbidden
physical accesses remain absent. All five resource leases survive held traps
and held completion; a new task cannot enter until completion is consumed.
Read-only publication/uniform bytes and writable backing guards are checked.

The local qualification passed on 2026-11-05: one case, all four kernels,
zero failures or skipped cases. Each healthy kernel checked 528 vectors
(8448 lane values) and 96 scalar block endpoints, completed 768 actual L2
AMOs and acknowledged 1024 lower-memory word writes. Both fault kernels
accepted exactly eight distinct warp traps and emitted no AMOs or writes.
Held completion, resource-owner overlap and fresh healthy reuse passed.
The final-word cached AMO repair has a focused regression in
[global atomic translation](GLOBAL_ATOMIC_TRANSLATION.md).

Run the single case explicitly, outside regular PR and nightly lanes:

```sh
git submodule update --init depends/asap7_sram_0p0
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 \
  -Dopengpu.computeResourceCoexistenceQualification=1 -Dopengpu.productQualification=0 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit 'set Test / parallelExecution := false' \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "QualifyComputeResourceCoexistence.scala").contains(f.getName))' \
  'Test/runMain opengpu.core.QualifyComputeResourceCoexistence --reports .artifacts/compute-resource-coexistence'
bash scripts/test_scripts.sh
```

The runner is marked `@DoNotDiscover`, requires its explicit property and adds
no default CI case or lane. It renders no frame.

## Four-CU coexistence

`QualifyComputeResourceFourCu` runs the same capacity kernel on four stock CUs
at once. Each CU keeps its own Sv32 root, page tables and physical backing for
the complete mandatory active set: twelve 16 KiB uniform blocks plus all 1024
default components, sixteen texture/sampler/image/level records, four R32UI
image units, four 128 MiB storage bindings, one eight-counter view and all
16 KiB of shared memory. The CUs share one `SharedComputeMemoryInterconnect`
and one real four-bank L2 with 64 outstanding transaction IDs, and reach
memory only through the single lower port. Instruction fetch remains bare;
data access uses each CU's own Sv32 root.

The run checked 2112 vectors (528 per CU, 8448 lane values each), 384 scalar
block endpoints, 3072 actual L2 AMOs and 4096 acknowledged lower-memory word
writes, with resource-owner overlap on every CU. All four CUs completed
together, held completion for 13 cycles and drained. This is simultaneous
four-CU service/capacity coexistence for the capacity kernel only; the
storage-bounds and denied-texture fault kernels remain one-CU, and the product
command path, API/compiler accounting, whole-host timing and physical
qualification remain open. It does not close H7.

```sh
git submodule update --init depends/asap7_sram_0p0
sbt -J-Xmx7G -Dopengpu.computeResourceFourCuQualification=1 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 -batch \
  'Test/runMain opengpu.core.QualifyComputeResourceFourCu \
   --reports .artifacts/compute-resource-four-cu'
```

The 36-binding API uniform namespace, linked-program/API accounting, broader
formats and operations, product/whole-system routes, complete cross-domain
barriers, cancellation combinations and physical timing remain separate gates.
Neither witness establishes product performance.
