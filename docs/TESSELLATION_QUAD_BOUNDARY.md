# Quad-domain tessellation boundary

`TessellationQuadBoundary` streams the four independently subdivided outer
edges in counter-clockwise quad-domain order: bottom, right, top, left. It
uses the existing level-preparation and edge-coordinate units, including equal,
fractional-even and fractional-odd spacing. Each edge emits its starting
corner and omits its ending corner, so a corner is owned once by the next edge.
Coordinates use unsigned Q0.24. A held vertex stays stable through backpressure
and cancellation; accepted work drains before the completion is offered.
Discarded patches emit no vertices. The count can reach 256.

The [OpenGL ES 3.2 specification, §11.2.2](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf)
defines the quad edge assignments and spacing rules. This component does not
yet generate interior vertices or triangles, order triangle winding, invoke
shaders, or integrate with the native host. It is not quad tessellation or an
H8 capability claim; those steps and product qualification remain open.

The bounded test compares all three spacing modes against independent rational
coordinates, including different levels per edge and maximum levels. It also
checks held output, cancellation during output and coordinate computation,
discard, invalid spacing and reuse:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationQuadBoundarySpec opengpu.graphics.TessellationEdgeCoordinateSpec opengpu.graphics.TessellationLevelPrepareSpec'
```

## Interior grid

`TessellationQuadInterior` streams the inner grid in row-major order. The first
inner level controls the vertical subdivisions and the second controls the
horizontal subdivisions. When a nontrivial patch promotes an inner level of
one, the stream keeps grid indices as distinct identities even if fractional-odd
spacing produces coincident coordinates on the boundary. The maximum grid has
63 × 63 vertices. The all-one equal-spaced quad has no interior grid vertices.
Output and cancellation obey the same held-offer contract as the boundary.

The independent reference test checks all spacing modes, unequal inner levels,
promotion, the maximum grid, held output, cancellation, discard and reuse:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationQuadInteriorSpec opengpu.graphics.TessellationQuadBoundarySpec opengpu.graphics.TessellationEdgeCoordinateSpec opengpu.graphics.TessellationLevelPrepareSpec'
```

The grid is not connected to the boundary and emits no triangles. Stitching,
point mode, winding, shader execution and native integration remain required.
