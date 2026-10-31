# Geometry window bin pass

`GeometryWindowBinPass` sends shared-CU geometry through clipping and viewport
preparation into acknowledged window-primitive bin storage. It snapshots each
task's raster state, state handle and token; assigns pass-wide 32-bit bin
sequences; and preserves the geometry input primitive ID, invocation and layer
in each replayed record. A task owns the source until its completion is
consumed; the pass owns its arenas until both the source and bin engines drain.
Mismatched pass identities are rejected before source launch, and a bin fault
cancels the source while retaining the bin failure at pass completion.

The local 4×4 real-CU test covers two ordered tasks, replay backpressure,
identity rejection, a record-store fault and a clean pass after the fault:

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GeometryWindowBinPassSpec'
```

CI runs `GeometryWindowBinAdmissionSpec` for identity and sequence-boundary
admission; the real-CU qualification above stays local.

Native draw submission, replay into fragment builtins, layered attachment
ownership and tessellation remain open. This test does not establish product
frame rate or physical timing; H8 remains open.
