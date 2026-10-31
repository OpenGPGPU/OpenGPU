# Geometry replay to fragment shader

`GeometryReplayFragmentPipeline` binds one stored geometry replay record to a
tile-clipped raster task and a geometry-aware fragment draw. The shader sees
the emitted primitive ID and the retained layer through internal fragment ABI
version 5. Identity, varying count, sample mode and centroid qualifiers must
match before either child accepts the draw. The replay source, fragment
client and held output drain before the completion is released.

The 4×4 real-CU qualification checks the two shader inputs, tile clipping,
identity rejection and held-output cancellation. Run it with the bounded
admission check:

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GeometryReplayFragmentAdmissionSpec opengpu.graphics.GeometryReplayFragmentPipelineSpec'
```

CI runs the admission check; the shared-CU composition runs locally. A native
image owner, layer-specific attachment storage, draw submission and
tessellation are still required. H8 remains open.
