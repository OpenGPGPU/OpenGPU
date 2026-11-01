# Isoline evaluation through the shared CU

`IsolineGeometryCuExecutor` adapts one tagged tessellation evaluation request
to the existing single-invocation geometry worker and shared-CU scheduler. The
internal shader reads the three binary32 tessellation coordinates as input
vertex 0, components 0–2; writes linked output components using the geometry
scalar instructions; and emits exactly one point. A missing point, failed
kernel, invalid task, or cancellation returns a tagged failed response. The
owner must reserve the command ID and retain shader resources until response
acceptance, and must continue draining accepted work during cancellation.

`ControlledIsolineSharedCuRenderer` can also snapshot up to 61 selected
control-output words for each evaluation. The task selects a vertex component,
per-patch component or tessellation level for each word; the shader reads them
as input vertex 0 components 3 onward. Reads use the resident patch store's
bounds checks, and an invalid selection fails the evaluation before CU launch.
External inspections and their held replies retain ownership while internal
reads wait. An accepted read drains after cancellation; patch contents and
shader resources remain owned through the evaluation response. The selection
is fixed per task, not dynamic shader indexing, and the separate
`IsolinePatchSharedCu` interface still supplies coordinates only.
The bounded checks cover all 61 word positions, rejected selections and
cancelled read drain. A real-CU shader copies per-patch and per-vertex values
into both line endpoints, with a held external inspection and post-cancel
reuse. The typed-image shared-system regression retains the zero-selection
path. These checks do not establish composed timing or product throughput.

This is a narrow internal instruction ABI, not a GLSL ES tessellation shader
implementation. Dynamic patch access, TCS/TES shader compilation and
binding, native submission, other tessellation domains and product throughput
remain open. The real-CU test is intentionally local-only; CI runs the bounded
scheduler/ownership check. Both commands below passed locally; the real-CU
shader copied three distinct coordinate words through the shared CU.

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.IsolineGeometryCuExecutorSpec'
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.IsolineGeometryCuQualificationSpec'
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.IsolinePatchInputReaderSpec opengpu.system.IsolineGeometryCuExecutorSpec'
sbt -J-Xmx5G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.ControlledIsolineSharedCuRendererAdmissionSpec opengpu.system.ControlledIsolineSharedCuRendererQualificationSpec opengpu.graphics.ControlledIsolineWindowBinTypedImageSharedSystemSpec'
```
