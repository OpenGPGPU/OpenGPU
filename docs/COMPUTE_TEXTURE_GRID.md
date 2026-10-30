# Compute texture ownership in grid dispatch

Compute texture descriptors and their work identity are captured with the
command and propagated to every workgroup and selected CU. The compact
`ComputeTextureDispatchSpec` checks ownership across three workgroups and a
held parent completion in CI.

The local `ComputeTextureSystemSpec` exercises all 16 texture bindings on two
CUs with direct and indirect grids while image, storage, uniform and geometry
capabilities are present. It remains local-only because the complete two-CU
model is substantially larger than the dispatch check. Run both with:

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simSeparateBuild=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.dispatch.ComputeTextureDispatchSpec opengpu.graphics.ComputeTextureSystemSpec'
```

Native host publication, broader shader operations and H7 qualification still
require their dependent changes.
