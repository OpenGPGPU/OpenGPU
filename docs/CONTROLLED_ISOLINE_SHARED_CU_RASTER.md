# Controlled isoline rasterization on shared CUs

`ControlledIsolineSharedCuRaster` connects controlled tessellation evaluation,
primitive mapping, clipping and viewport transformation to the existing point
and line raster engine. The owner retains the task until accepted CU and raster
work drains; quads remain provisional until successful completion. Raster and
viewport state are checked before launching the CU.

The independent raster admission and raster-stage checks run in CI. The full
wrapper admission and real-CU qualifications run locally; the latter uses a
4×4 frame:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.ControlledIsolineSharedCuRasterQualificationSpec opengpu.system.ControlledIsolineSharedCuRasterAdmissionSpec'
```

Arbitrary shader reads of patch data, the native TCS/TES ABI, other tessellation
domains, fragment/image integration and product qualification remain open.
