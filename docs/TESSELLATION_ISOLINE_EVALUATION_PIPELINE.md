# Isoline generation and evaluation ownership

`TessellationIsolineEvaluationPipeline` connects the isoline domain generator
to the evaluation handoff and retains patch ownership until both have drained.
It forwards tagged evaluation requests to an external executor, holds evaluated
points or lines for the consumer, and reports generated and published counts.
After cancellation or an evaluation fault, already accepted work and held
offers are drained before completion. Output is provisional until completion;
the enclosing renderer must discard published output on any nonzero status.
The executor must respond to every accepted request even during cancellation.

The bounded integration test uses the actual generator and handoff with a fake
executor. It checks ordered coordinates and output components, backpressure,
cancellation during an offered request, executor failure, drain and reuse.
Discarded patches and invalid spacing complete without issuing evaluation work.

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationIsolineEvaluationPipelineSpec'
```

Shared-CU TES execution, patch-data reads, downstream primitive mapping,
triangle and quad domains, native submission and product timing/throughput
qualification remain open. A fake executor does not qualify a shader path.
