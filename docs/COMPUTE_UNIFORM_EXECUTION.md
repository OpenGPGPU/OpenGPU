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

An additional compact two-CU test executes all three resource classes in the
same direct and indirect kernels: twelve uniform blocks, four storage bindings
and four image units. It checks exact results, guarded storage and image data,
and publication leases while completion is held. A local-only variant uses
the mobile execution configuration with four CUs and 128-thread workgroups;
all four CUs are observed busy at once. Both variants read the last word of
each 16 KiB block and the last component of the 1,024-component default view.
These are compute-system checks; host and cross-domain visibility qualification
remain open.

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 -Dopengpu.productQualification=0 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.GpuSystemComputeUniformSpec opengpu.core.GpuComputeUnitUniformSpec opengpu.graphics.ComputeUniformCuServiceSpec opengpu.core.frontend.decode.ComputeUniformAvailabilityDecodeSpec'
sbt 'testOnly opengpu.system.GpuSystemResourceCoexistenceSpec'
sbt 'testOnly opengpu.system.GpuSystemFullResourceCoexistenceSpec'
```

Native host publication, driver/API and compiler integration, broader
resource coexistence, cross-domain visibility, host capacity and
physical qualification remain open. These tests do not close H7.
