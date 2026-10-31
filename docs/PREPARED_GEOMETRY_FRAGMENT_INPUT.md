# Prepared geometry fragment input

The prepared-primitive profile of `VertexFragmentPipeline` can optionally
accept the internal geometry fragment ABI. It snapshots the per-primitive
layer together with the retained primitive at draw admission and supplies it
to each raster quad. Geometry ABI draws without a valid layer are rejected
before either child starts. Ordinary vertex draws and legacy fragment ABI
profiles retain their existing interfaces and checks.

The local 4×4 shared-CU qualification checks the fragment shader's primitive
ID and retained layer, a changed layer input after admission, invalid metadata
and held-output cancellation:

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.PreparedGeometryFragmentPipelineSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

The full prepared and legacy vertex/fragment real-CU suites run locally; CI
selects bounded fragment admission, geometry metadata and input-coalescing tests.

The production image owner must still connect the metadata and bind the
appropriate layer-specific attachment view. H8 remains open.
