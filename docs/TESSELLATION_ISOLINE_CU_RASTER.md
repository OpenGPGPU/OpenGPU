# Shared-CU isolines through rasterization

`IsolineGeometryCuRaster` connects generated, evaluated, clipped and
viewport-prepared isolines to the existing point/line raster and interpolation
engine. It validates raster and depth state before launching the CU, retains
patch ownership while raster work drains, and reports quads and coverage counts.
Rasterized quads remain provisional until successful patch completion; the
enclosing renderer must discard them on cancellation or error.

Both 4×4 local compositions passed: the second ran the generated line through
a real shared CU and produced covered samples. CI runs the existing
bounded raster-admission check instead of compiling the full raster composition:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.IsolineGeometryCuRasterSpec'
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.IsolineGeometryCuRasterQualificationSpec'
```

Fragment shading, attachments, binning, native submission, patch-data access,
GLSL TES, triangle and quad domains, and product qualification remain open.
