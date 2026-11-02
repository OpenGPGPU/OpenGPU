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

## Edge-strip triangulation

`TessellationQuadStrip` joins one counter-clockwise outer side to the
corresponding ordered inner side using one triangle per edge advance. The
caller supplies both endpoint vertices and monotone non-crossing sides, with
the inner side to the left of the outer side. Zero inner segments represent a
single point and produce a fan. The module compares normalized topological
progress to choose which side advances; both windings, output backpressure,
cancellation and reuse are supported. Inputs are streamed, so the module does
not allocate a full patch-sized point buffer.

The strip test covers unequal subdivisions up to 64 segments, point-like
inner edges, all four orientations, both windings, edge coverage, signed area,
held output and cancellation. Reproduce it with:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationQuadStripSpec opengpu.graphics.TessellationQuadInteriorSpec opengpu.graphics.TessellationQuadBoundarySpec'
```

A patch-level owner must still supply the matching strips, fill inner grid
cells, handle the all-one quad and point mode, and integrate the evaluator and
native host. The isolated strip test does not establish complete quad coverage
or product qualification.
