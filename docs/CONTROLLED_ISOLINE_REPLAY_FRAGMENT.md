# Controlled isoline window-bin replay fragment shading

`ControlledIsolineReplayFragmentPipeline` binds one retained isoline
window-bin record to a tile-clipped raster task and a public-ABI fragment
draw. Geometry invocation/layer metadata is not required; identity, varying
count, sample mode and centroid qualifiers must match before either child
accepts the draw. The replay source, fragment client and held output drain
before the completion is released.

`GeometryReplayRasterClient(requireGeometry=false)` covers the isoline record
shape produced by
[CONTROLLED_ISOLINE_WINDOW_BIN_PASS.md](CONTROLLED_ISOLINE_WINDOW_BIN_PASS.md).

The focused raster check and bounded admission check run in CI. The local
4×4 real-CU qualification checks tile clipping, identity rejection and
held-output cancellation:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.ControlledIsolineReplayRasterClientSpec opengpu.graphics.ControlledIsolineReplayFragmentAdmissionSpec'
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.ControlledIsolineReplayFragmentPipelineSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

A native image owner, other tessellation domains, native draw submission and
product qualification remain open. This does not establish product frame rate
or physical timing; H8 remains open.
