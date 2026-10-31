# Geometry-to-fragment draw stream

`GeometryFragmentDrawStream` owns a draw descriptor across a stream of
assembled primitives. It serializes each primitive into the existing
one-primitive geometry/fragment interface, carries the returned geometry
sequence forward, and assigns draw-wide fragment-quad sequence numbers.
Completion retains the source report, primitive and quad counts, and the first
reported status until consumed.

An offered child draw remains stable until accepted even if cancellation
arrives; cancellation then reaches the child and the primitive source. The
source stream is drained, accepted child output is retained through
backpressure, and a source fault takes precedence over the resulting child
cancellation. A mismatched source identity or primitive count fails the draw.

Run the bounded controller tests:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 'testOnly opengpu.graphics.GeometryFragmentDrawStreamSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

`GeometryFragmentDrawPipeline` connects that controller to the real
geometry/fragment CU pipeline. Its local 4×4 qualification feeds two assembled
primitives through real geometry and fragment shaders, checks draw-wide output
sequence and shader-written builtins, and waits for memory and completion to
drain. Run it outside the PR/nightly lanes:

```sh
sbt -J-Xmx5G -J-XX:ActiveProcessorCount=2 -Dopengpu.productQualification=0 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GeometryFragmentDrawStreamSpec opengpu.graphics.GeometryFragmentDrawPipelineSpec'
```

Native draw submission, attachment ownership, broader topology and real-source
integration remain open. No product frame-rate or physical timing result is
implied; H8 remains open.
