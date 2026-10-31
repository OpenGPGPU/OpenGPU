# Shared-CU isolines through viewport preparation

`IsolineGeometryCuViewport` maps clipped, shared-CU tessellated points and lines
through an owned viewport descriptor. It validates the descriptor before
launching the CU, tracks culled and guard-band outputs, and completes only after
the source and viewport stages drain. Window primitives are provisional until
successful patch completion; the enclosing renderer must discard them on error
or cancellation.

The bounded check passed locally for viewport coordinates, invalid descriptors, clip
faults, cancellation and reuse with a simulated CU interface. The real shared-CU
qualification also passed for a generated line and remains local-only to keep
the CI lane bounded:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.IsolineGeometryCuViewportSpec'
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.IsolineGeometryCuViewportQualificationSpec'
```

Raster, binning, native submission, patch-data access, GLSL TES, triangle and
quad domains, and product timing and throughput remain open.
