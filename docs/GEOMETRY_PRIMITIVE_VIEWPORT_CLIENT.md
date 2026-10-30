# Geometry primitive viewport client

`GeometryPrimitiveViewportClient` connects the shared-CU geometry and clipping client to the existing FP32 viewport stage. It captures viewport state at task admission, retains the input primitive ID, invocation ID and layer through each prepared part, and waits for both the geometry source and viewport stage to drain before returning a held completion. Invalid viewport state is rejected without launching geometry work. Guard-band requirements remain explicit in the window primitive.

Cancellation stops the owned source and viewport work, drains unaccepted upstream offers and keeps an already offered window primitive stable until accepted. Status and per-stage counts are reported separately; accepted output is not rolled back.

`GeometryPrimitiveViewportClientSpec` runs a real CU shader through output mapping, clipping and a 4×4 viewport. It checks exact window coordinates and depth, shader primitive ID, nonzero layer, near/far culling, invalid viewport rejection and cancellation with a held output. Run:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.GeometryPrimitiveViewportClientSpec'
python3 scripts/test_test_selection.py
```

This internal client is not connected to production draw admission, rasterization, binning, transform feedback or layered framebuffer selection. Triangle intersections, broader viewport combinations, native API/compiler integration and physical qualification remain open. H8 remains open.
