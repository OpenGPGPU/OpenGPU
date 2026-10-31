# Isoline renderer handoff

`TessellationIsolineRendererHandoff` connects domain generation, an external
evaluation executor and TES output mapping to the renderer's assembled-primitive
interface. It assigns ordered sequence and primitive IDs from caller-supplied
bases, rejects overflow, and waits for both evaluation and mapping to drain
before completing the patch. Accepted primitives are provisional until a
successful completion; the caller must discard them on fault or cancellation.
An executor must answer every accepted request, including during cancellation.

The integration check uses the real generator and mapper with a fake TES
executor. It verifies mapped positions and flat varyings, identity and ordering,
backpressure, mapping errors, sequence overflow, executor errors, cancellation,
draining and reuse:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationIsolineRendererHandoffSpec'
```

This internal handoff does not connect a real shared-CU TES or patch storage to
the executor, wire native submission or binning, qualify other tessellation
domains, or establish physical timing and product throughput.
