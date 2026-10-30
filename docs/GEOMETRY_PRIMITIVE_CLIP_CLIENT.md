# Geometry primitive clipping client

`GeometryPrimitiveClipClient` connects the shared-CU geometry client to the existing serial primitive clipper. It holds the source task until both geometry execution and clipping drain, retains the input primitive ID, invocation ID and layer for each clipped fan part, and reports clipped input, output and cull counts. The downstream primitive carries the shader-written primitive ID and mapped flat values.

Cancellation drains unaccepted geometry outputs, asks the active CU and clipper to stop, and retains an already offered clipped primitive until accepted. A cancelled scalar geometry operation may report a failed-operation trap, which the enclosing renderer must consume. Clip faults cancel remaining geometry work; accepted output is not rolled back. Completion is held until consumed.

`GeometryPrimitiveClipClientSpec` runs a real CU shader through output mapping and clipping, checking an in-range point, near/far culling, a nonzero layer and primitive ID, held output and cancellation recovery. Run:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.GeometryPrimitiveClipClientSpec'
python3 scripts/test_test_selection.py
```

This is an internal client and has not been connected to production draw admission, viewport/raster, bins, transform feedback or layered framebuffer selection. Triangle intersections through this composed client, native API/compiler integration and physical qualification remain open. H8 remains open.
