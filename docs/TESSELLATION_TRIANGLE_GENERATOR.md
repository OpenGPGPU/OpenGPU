# Triangle-domain primitive generation

`TessellationTriangleGenerator` combines the independently subdivided triangle
boundary, concentric interior rings and strip stitcher. It accepts equal,
fractional-even and fractional-odd spacing, three independent outer levels,
one inner level, either winding and point mode. Patch coordinates use unsigned
Q0.24 barycentrics. A discarded or invalid patch completes without primitives.

Two ring banks retain at most 64 vertices on each of three directed sides in
each bank. The generator joins adjacent rings, emits a final interior triangle
or center fan where needed, and streams points without triangulating them.
Each primitive carries its task ID and sequence number. Cancellation stops new
work, but an offered primitive remains valid and unchanged until accepted;
completion reports the accepted count. Outputs are provisional until successful
completion.

The directed test checks independent outer levels, all three spacing modes,
both windings, point-mode vertices, the 64-segment limit, primitive counts,
exact signed area, sampled coverage, backpressure, cancellation and reuse:

```sh
sbt -J-Xmx5G -Dopengpu.productQualification=0 -Dopengpu.simJobs=2 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'testOnly opengpu.graphics.TessellationTriangleGeneratorSpec'
```

This block does not yet connect triangle primitives to evaluation shaders,
clipping, binning, native submission or product qualification. The tests do not
establish complete API conformance or physical timing.
