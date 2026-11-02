# Triangle tessellation boundary

`TessellationTriangleBoundary` streams the independently subdivided outer
edges in counter-clockwise barycentric order: w=0, u=0, v=0. These correspond
to GLES outer levels 2, 0 and 1; the fourth level is unused. Each edge emits
its starting corner and excludes its ending corner. Coordinates are unsigned
Q0.24 and sum exactly to one. The shared level-preparation and edge-coordinate
units implement equal, fractional-even and fractional-odd spacing. Accepted
outputs remain stable under backpressure and cancellation drains accepted work.

The directed test checks distinct and maximum edge levels, all spacing modes,
exact coordinates and barycentric sums, held output, cancellation, discard,
invalid spacing, the unused fourth level and reuse:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationTriangleBoundarySpec'
```

The [OpenGL ES 3.2 specification, §11.2.2.1](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf)
defines the triangle domain and its outer-level assignments. This boundary does
not generate interior vertices or primitives or execute shaders; it does not
establish triangle-domain conformance or complete H8.
