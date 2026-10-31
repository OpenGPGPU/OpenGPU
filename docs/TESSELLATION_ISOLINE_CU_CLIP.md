# Tessellated isolines through clip

`IsolineGeometryCuClip` feeds assembled points and lines from the shared-CU
isoline renderer into the existing clip stage. Patch completion waits for both
the CU source and all accepted clip work; clip failures stop younger work and
are reported as status 7. Published clipped primitives remain provisional
until the patch completes successfully, so the enclosing owner must discard
them on cancellation or failure.

The bounded test passed locally using a simulated CU event/completion interface
to cover a line, clip numeric failure, cancellation and drain. The local-only
real shared-CU qualification also passed for a generated, evaluated and clipped
line. It stays outside routine CI to keep resource usage bounded:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.IsolineGeometryCuClipSpec'
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.IsolineGeometryCuClipQualificationSpec'
```

Viewport, raster, binning, native submission, patch-data access, GLSL TES,
triangle and quad domains, and product qualification remain open.
