# Geometry to fragment pipeline

`GeometryFragmentPipeline` admits one linked geometry/raster task and fragment
draw together, routes both shader clients through the shared CU pool, and
retains ownership until raster and fragment completion and CU drain. It checks
work identity, varying count, sample mode and centroid qualifiers before
launch; cancellation propagates to both clients while offered output drains.
Local shader payload routing is supported through the shared payload ports.

The local full-system test uses a 4×4 frame and a real CU to run a geometry
shader through clipping, viewport and rasterization into a fragment shader.
It checks point, line-strip and triangle-strip coverage, shader-written
primitive ID and layer (including the provoking vertex), invalid admission,
and cancellation with held fragment output. The compact `GeometryFragmentAdmissionSpec`
checks cross-stage admission in CI; the full pipeline test is local-only:

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simSeparateBuild=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.GeometryFragmentAdmissionSpec opengpu.system.GeometryFragmentPipelineSpec'
```

This is a one-input-primitive client. Native draw submission, complete shader
compiler/public ABI integration, full primitive/topology qualification,
attachment ownership and H8 completion remain open.
