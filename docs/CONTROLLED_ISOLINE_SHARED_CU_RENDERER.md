# Controlled isoline rendering on shared CUs

`ControlledIsolineSharedCuRenderer` connects retained control-patch evaluation
and primitive mapping to the existing shared-CU geometry executor. The owner
reserves the command ID and keeps shader resources alive through completion.
Assembled primitives remain provisional until completion succeeds; accepted
requests, held output and cancellation drain before the task is released.

The CU executor currently supplies tessellation coordinates through the
internal scalar geometry ABI. Shader access to arbitrary patch data, a native
TCS/TES ABI, other tessellation domains, full raster/image integration and
product qualification remain open.

The bounded admission test runs in CI; the real-CU qualification runs locally:

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.ControlledIsolineSharedCuRendererAdmissionSpec'
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.ControlledIsolineSharedCuRendererQualificationSpec'
```
