# Shader-controlled isolines to window primitives

`TessellationSystemIsolineViewport` runs one control patch and its evaluation
invocations through shared GPU dispatch, then maps the evaluated components to
primitive positions and varyings, clips the primitive and applies the viewport.
The caller supplies the linked component mapping, stable graphics identity and
sequence base, a legal viewport, and the system client's reserved tag and
shader resources. Sequence or primitive-ID overflow and invalid component
mappings fail the task. Window primitives remain provisional until completion;
accepted outputs and all active shader and graphics stages must drain on
failure or cancellation.

The actual-system test uses real control and evaluation instructions on one
four-lane/two-warp CU. Control writes levels and a per-patch value, and the
evaluation shader passes the value to a varying. The test checks clipping and
window coordinates, held-output backpressure, evaluation cancellation, invalid
mapping and viewport, and reuse. It does not yet connect this path to binning, rasterization, native
submission, multiple contending CUs, or default-profile product qualification.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationSystemIsolineViewportSpec'
python3 scripts/test_test_selection.py
```
