# Geometry primitive output mapping

`GeometryPrimitiveOutput` converts a completed geometry output primitive into the existing `AssembledPrimitive` format. A linked component map selects the source words for clip position and active varyings; inactive mappings produce zero. For flat varying components it copies the provoking vertex's value to every vertex before clipping. The shader-written primitive ID is selected from that vertex, while the source primitive ID remains separately available for ownership. Layer is retained in a separate output field; when the linked shader does not declare either built-in, its corresponding field is zero.

The adapter checks the retained task and source primitive IDs, vertex and varying bounds, linked output component budget and active map indices. It holds a mapped primitive or a fault under backpressure. It copies at most 204 words for a three-vertex primitive with sixteen varying slots, excluding stalls. The caller supplies a unique output sequence and retains the original draw/instance metadata and linked layout.

`GeometryPrimitiveOutputSpec` checks point, line and triangle mapping, flat components, built-in defaults, held output and invalid maps/ownership. A composition with the geometry strip assembler verifies the selected primitive ID through both triangle windings. Run:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GeometryPrimitiveOutputSpec'
python3 scripts/test_test_selection.py
```

The adapter is internal and not connected to production clipping, binning, fragment input or layered framebuffer selection. Draw cancellation, transform feedback, point-size output and host/API integration remain open. This does not close H8.
