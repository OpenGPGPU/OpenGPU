# Shared-CU isolines through fragment shading

`IsolineFragmentPipeline` connects the generated and rasterized isoline stream
to the existing fragment shader stage. A shared task router owns CU tags and
routes geometry and fragment traffic to the same physical pool. The pipeline
validates the fragment, raster and viewport descriptors as well as stage identity,
varying count, sample mode and centroid linkage before launch, forwards an EOF
with the accepted quad count, and waits for both shader
stages and router ownership to drain. Fragment outputs are provisional until
successful completion; an enclosing renderer must discard them on error or
cancellation.

The focused admission test passed locally and is suitable for CI. The 4×4
end-to-end qualification also passed locally: one shared-CU pool evaluated a
generated isoline and shaded its covered samples, then verified held-output
cancellation and invalid-descriptor rejection. It is local-only because it
compiles both full shader paths:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.IsolineFragmentAdmissionSpec'
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.IsolineFragmentPipelineQualificationSpec'
```

Attachment writes, binning, native submission, patch-data access, GLSL TES,
triangle and quad domains, and product timing and throughput remain open.
