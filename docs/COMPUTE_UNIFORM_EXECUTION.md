# Compute uniform execution

The opt-in `GpuSystem` compute-uniform path captures a GUR1 root, identity,
private interval and Sv32 root for each task. Direct and indirect commands
retain the publication through workgroup dispatch and public completion.
Graphics launches do not inherit compute permissions. Task-enabled scalar and
vector uniform instructions use the checked reader independently of sampler
availability. Read traffic and page walks use their own CU transaction IDs.

The reader accepts twelve logical block mappings into 24 bindings and a
separate default-uniform view. Block views are at most 16 KiB and start on a
256-byte boundary; the compute default view holds at most 1024 32-bit
components. Descriptor identity, stage, read permission, logical bounds,
backing-line bounds and translation are checked before a data read. Accepted
traffic drains before cancellation or completion. As with the graphics
uniform path, the publisher owns descriptor contents, data publication and
VM context through completion.

A compact two-CU system test executes twelve different blocks in queued direct
and indirect commands, covering six workgroups, 72 exact vector results and held
completion ownership while image and storage paths are enabled. The reader
also checks default-view bounds; actual-CU scalar/vector tests cover Bare and
Sv32, and a separate permission test faults before a denied physical read.
Existing image and storage CU compatibility checks passed. No test renders a
frame.

The explicit local `QualifyComputeUniformCapacity` fixture uses one complete
default sixteen-lane/eight-warp CU, SRAM-backed register/shared storage, the
enabled FPU backend and default cache capacities. One 128-item workgroup owns
twelve simultaneous 16 KiB block views and a 1024-component default view:
`12 * 16384 / 4 + 1024 = 50176` components. A permuted twelve-entry map selects
distinct slots throughout the 24-entry native table. Actual scalar/vector
instructions check each block's independent first and last words, including a
view ending at the exclusive 4 GiB boundary. Divergent vector offsets read
every default component exactly once across all eight warps.

The fixture supplies sparse byte memory and 51 real Sv32 leaf mappings per
task; descriptor, page-table and data accesses go through the actual CU
service and translator. It poisons live publication and SATP inputs after
admission, retains the lease through held traps/completion, rejects the first
out-of-view default word before data access, and denies a mapped block's final
page before its physical read. A subsequent full-capacity kernel uses fresh
page tables and different data in the same CU without reset. Uniform memory
remains unchanged. Other optional resource services are disabled; this is
neither aggregate resource coexistence nor API/physical qualification.

The local qualification passes one case containing four kernels, with no
skipped cases: full capacity, out-of-view default offset, denied final block
page, then full capacity again without reset. Each healthy kernel checks
96 scalar block-first results, 96 sixteen-lane block-last results and all
1024 distinct default values. Both fault kernels report all eight distinct
warp traps exactly once on acceptance, with no faulting destination writeback; forbidden physical data reads
remain absent. All descriptor/data bytes and held completions stay intact.

Run this qualification explicitly, outside regular PR/nightly lanes; it does
not render a frame or add a default CI case:

```sh
git submodule update --init depends/asap7_sram_0p0
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 \
  -Dopengpu.computeUniformCapacityQualification=1 -Dopengpu.productQualification=0 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit 'set Test / parallelExecution := false' \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "QualifyComputeUniformCapacity.scala").contains(f.getName))' \
  'Test/runMain opengpu.core.QualifyComputeUniformCapacity --reports .artifacts/compute-uniform-capacity'
```

Compute uniform translation now carries page-fault classification to both
scalar and vector traps. A vector failure identifies the lanes that shared the
failed block/offset request. Twelve local service and execution tests passed,
including denied mappings, PTE-read errors and a divergent-lane failure. A
translated CU success regression and four small CU fault cases also passed:
scalar and vector loads each distinguish invalid mappings from PTE-read errors
and retain the virtual fault address. This is not complete system fault or
cross-domain qualification.

A separate four-lane CU compatibility case passed with uniform and compute
texture services enabled: a scalar translated texture fault retained its trap
and completion behavior. It does not qualify texture fault classification.

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 -Dopengpu.simHierarchy=GpuComputeUnit \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.ComputeUniformCuServiceSpec opengpu.graphics.ShaderUniformExecuteUnitSpec' \
  'testOnly opengpu.core.GpuComputeUnitUniformSpec -- -z "Sv32=true"' \
  'testOnly opengpu.core.GpuComputeUnitUniformPageFaultSpec'
sbt -J-Xmx5G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 -Dopengpu.simHierarchy=GpuComputeUnit \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.ComputeTextureActualCuSpec -- -z "vector=false"'
```

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 -Dopengpu.productQualification=0 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.GpuSystemComputeUniformSpec opengpu.core.GpuComputeUnitUniformSpec opengpu.graphics.ComputeUniformCuServiceSpec opengpu.core.frontend.decode.ComputeUniformAvailabilityDecodeSpec'
```

[Native host publication](COMPUTE_UNIFORM_COMMANDS.md) supplies an opt-in
command transport and record encoders; the [two-default-CU host witness](NATIVE_HOST_RESOURCE_PUBLICATION.md)
executes the mandatory active set. [Linux direct-compute publication](COMPUTE_UNIFORM_SUBMISSION.md)
adds retained immutable uniform snapshots with C driver-function and real module
build evidence. Linux full-resource execution, API/compiler integration, broader
cross-domain visibility, four-CU coexistence and physical qualification remain
open. These tests do not close H7.
