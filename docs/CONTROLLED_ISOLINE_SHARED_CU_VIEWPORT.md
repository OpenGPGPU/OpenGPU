# Controlled isoline viewport on shared CUs

`ControlledIsolineSharedCuViewport` transforms clipped control-patch output
into the existing window-primitive representation. It validates the viewport
before launching a patch, retains the task until CU, clip and viewport work
drains, and keeps window output provisional until successful completion.

The bounded admission check is selected for CI. The local real-CU test uses a
4×4 viewport and exercises clipped endpoint coordinates, cancellation and
reuse:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.ControlledIsolineSharedCuViewportQualificationSpec opengpu.system.ControlledIsolineSharedCuViewportAdmissionSpec'
```

Arbitrary patch reads by shader code, the native TCS/TES ABI, other domains,
full raster/image integration and product qualification remain open.
