# Triangle tessellation interior


`TessellationTriangleInterior` streams the concentric inner-ring vertices used
by the triangle domain. It temporarily subdivides an outer edge using the first
inner level, projects its interior vertices onto each successive inner edge,
and removes two segments per ring. Directed sides exclude their ending corner;
a final two-segment ring becomes one center vertex. Ring, side and index retain
topological identity when fractional-odd promotion produces coincident points.

Coordinates are Q0.24. Perpendicular projections round locally, and the center
uses the nearest common Q0.24 value to one third; its three coordinates sum to
one minus one LSB. The directed test compares representative equal and
fractional levels and the maximum level against an independent real-valued
construction within 64 Q0.24 LSB, and checks counts, coordinate sums, discard,
backpressure, cancellation and reuse. This is a directed error check, not a
proof of the error bound for every level.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationTriangleInteriorSpec'
```

Adjacent rings and the independently subdivided outer boundary still need to be
joined into non-overlapping primitives. Evaluation, native submission and full
triangle-domain and product qualification remain open.

The [OpenGL ES 3.2 specification, §11.2.2.1](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf) defines the concentric triangle construction.
