# Controlled isoline fragment shading

`ControlledIsolineFragmentPipeline` routes controlled patch evaluation and
fragment shading through the shared CU pool. Raster quads are sent to the
fragment stage with the linked identity, interpolation and sample contract;
end-of-input is issued after accepted raster work drains. Output is provisional
until both stages complete and the shared router drains.

Routine CI selects bounded admission and shared-router checks. The local
real-CU qualification uses a 4×4 frame, checks shader output coordinates,
held-output cancellation, invalid-draw rejection and invalid-patch retirement:

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.ControlledIsolineFragmentAdmissionSpec'
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.ControlledIsolineFragmentPipelineQualificationSpec'
```

Arbitrary shader patch reads, the native TCS/TES ABI, other tessellation
domains, image publication and product qualification remain open.
