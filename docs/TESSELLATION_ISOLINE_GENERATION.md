# Tessellation isoline primitive generation

`TessellationIsolineGenerator` combines the existing level preparation and
edge-coordinate units to generate one patch's isolines. Outer level 0 selects
1–64 equally spaced lines; outer level 1 and the requested spacing determine
1–64 segments per line. Line mode emits ordered endpoint pairs; point mode
emits each generated coordinate once. Coordinates are unsigned Q0.24, and the
third tessellation coordinate is zero for this domain. The generator retains
held output through backpressure and cancellation and reports the accepted
primitive count at completion. A discarded patch emits no primitives.

The bounded test checks line and point output against an independent rational
reference across spacing modes, fractional levels and line counts, as well as
64-line and 64-segment boundaries, invalid input, cancellation and reuse:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationIsolineGeneratorSpec'
```

This serial implementation uses the existing iterative edge-coordinate unit;
product throughput and physical timing are not qualified. Evaluation-shader
execution, triangle and quad domains, native submission and full H8 remain
open. The [OpenGL ES 3.2 specification §11.2.2](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf)
defines isoline subdivision and spacing.
