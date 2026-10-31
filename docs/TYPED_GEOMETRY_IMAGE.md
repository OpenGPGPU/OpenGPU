# Geometry builtins in a typed image draw

The prepared-primitive profiles of the typed tile renderer and image pipeline
can carry a geometry layer to the fragment stage. The renderer snapshots the
layer with the primitive at draw admission, while the image pipeline forwards
the value on the same handshake. Default profiles keep their existing ports.

The local 4×4 real-CU test shades a prepared triangle into an RGBA32UI image,
changes the input layer after admission, and checks the stored primitive ID and
original layer as well as a pixel outside the scissor:

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TypedGeometryImageQualificationSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

The enclosing owner must bind the correct single-layer image view. Automatic
layer selection, bin-to-image pass integration and native submission remain
open; this result does not complete H8 or establish product frame rate.
