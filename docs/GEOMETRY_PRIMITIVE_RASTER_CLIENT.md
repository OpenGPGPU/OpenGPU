# Geometry primitive raster client

`GeometryPrimitiveRasterClient` connects the shared-CU geometry, clipping and viewport client to the existing point, line and triangle raster/interpolation engine. It captures raster state, centroid qualifiers and depth format at admission and retains the input primitive ID, invocation ID and layer with every output quad. It holds task completion until geometry, viewport, raster and offered quads drain. Invalid raster state or qualifier bounds are rejected before geometry launches; cancellation preserves an already offered quad until accepted.

`GeometryRasterAdmissionSpec` is a compact CI check for raster state, qualifier and depth admission. The complete `GeometryPrimitiveRasterClientSpec` is local-only: it runs a real CU shader through clipping, viewport and a 4×4 raster, checks exact covered coordinates, depth, shader primitive ID and layer, and exercises near/far culling, invalid admission and cancellation with a held quad. The local fixture uses two interpolation contexts and took about 1 minute 40 seconds for a cold simulator build on this host; the client default remains eight. The eight-context version passed before admission was factored into the compact check; the revised two-context fixture is validated separately. Reproduce with:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.GeometryRasterAdmissionSpec opengpu.system.GeometryPrimitiveRasterClientSpec'
python3 scripts/test_test_selection.py
python3 scripts/test_ci_case_partitions.py
```

This internal client does not yet launch fragment shaders or connect to native draw admission, bins, transform feedback or layered framebuffer selection. Triangle intersections, broader coverage/qualifier combinations and physical qualification remain open. H8 remains open.
