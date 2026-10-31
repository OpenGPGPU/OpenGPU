# Shaded isolines in a resident typed tile

`IsolineTypedTilePipeline` connects the shared-CU isoline and fragment path to
the existing typed color and late depth/stencil backend for one resident tile.
It validates linked shader, viewport, raster, tile and blend state before
launch; drains accepted shader and pixel work on failure; and exposes tile
maintenance only after successful shader and pixel retirement. The owner must
complete backing-image publication before closing the tile and must discard a
failed or cancelled tile. External writes must remain transactional or invisible
until successful completion, since maintenance faults or cancellation can occur
after earlier words were read.

The bounded admission test passed locally. The 4×4 real shared-CU tile test
is local-only to keep routine CI resource use bounded:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.IsolineTypedTileAdmissionSpec'
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.IsolineTypedTileQualificationSpec'
```

This single-tile interface does not publish backing images itself. Multi-tile
binning, native submission, patch-data access, GLSL TES, triangle and quad
domains, and product qualification remain open.
