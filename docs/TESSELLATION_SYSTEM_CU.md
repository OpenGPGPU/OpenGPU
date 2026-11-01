# Shared-system tessellation control

`GpuSystem` can optionally expose tessellation-control sidebands on its shared
CU graphics interface. A control launch supplies warp bases with the accepted
kernel; each CU's access, barrier and finish traffic is routed only while that
CU is owned by a graphics task. Geometry and tessellation-control modes are
mutually exclusive for a launch. The default system leaves the capability off.

`TessellationCuIsolineSystemClient` reserves a graphics command tag across a
control workgroup and its subsequent evaluation invocations. It identifies the
control CU from the pool's ownership and tag outputs, routes patch responses,
resume, cancellation and terminal indications to that CU, and uses the
existing geometry service for evaluation. The enclosing owner reserves the tag,
pins resources and supplies texture, trap and invalidation services until the
completion is accepted. The one-dimensional control workgroup must fit in one
CU and in the 32-invocation patch limit. Output remains provisional until the
final completion succeeds.

The bounded actual-system simulation runs real control and evaluation shader
programs through the shared dispatch, CU and L2 path, checking generated
coordinates, a control-written patch word, evaluation cancellation and reuse.
It uses one four-lane/two-warp CU and does not establish multi-CU contention, native submission, raster/image
integration, product throughput or physical timing.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationCuIsolineSystemClientSpec'
# With the same sbt options, also run:
# testOnly opengpu.system.GpuSystemSpec -- -z "execute graphics tasks and compute on the same 1 CUs"
python3 scripts/test_test_selection.py
```
