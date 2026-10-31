# Isoline evaluation through the shared CU

`IsolineGeometryCuExecutor` adapts one tagged tessellation evaluation request
to the existing single-invocation geometry worker and shared-CU scheduler. The
internal shader reads the three binary32 tessellation coordinates as input
vertex 0, components 0–2; writes linked output components using the geometry
scalar instructions; and emits exactly one point. A missing point, failed
kernel, invalid task, or cancellation returns a tagged failed response. The
owner must reserve the command ID and retain shader resources until response
acceptance, and must continue draining accepted work during cancellation.

This is a narrow internal instruction ABI, not a GLSL ES tessellation shader
implementation. Patch input/output access, TCS/TES shader compilation and
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
```
