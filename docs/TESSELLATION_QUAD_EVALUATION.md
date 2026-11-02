# Quad tessellation evaluation handoff

`TessellationQuadEvaluation` holds one generated triangle or point while an
external evaluator executes its vertices. Q0.24 u and v coordinates are
converted to binary32 and supplied with a zero third coordinate. Each request
carries the task, primitive sequence and vertex index; triangle mode issues
three serial requests, point mode one. Returned 64-word vertex payloads are
retained until accepted. A mismatched tag or executor failure produces a held
fault instead of a primitive.

`TessellationQuadEvaluationPipeline` connects the quad generator to this
handoff and retains ownership until generation and accepted evaluation drain.
Published primitives are provisional until successful completion. An executor
must return one response per accepted request even after cancellation; the
owner must discard provisional output on an error. This is an external
execution interface, not yet an actual shared-CU evaluation shader or a native
submission/API contract.

Tests check exact coordinate conversion, three-vertex and point ownership,
backpressure, malformed tags, executor failures, cancellation, discard and
reuse. The combined test covers all-one and 2×2 quad patches, including
triangle area and point count:

```sh
sbt -J-Xmx4G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationQuadEvaluationSpec opengpu.graphics.TessellationQuadEvaluationPipelineSpec opengpu.graphics.TessellationQuadGeneratorSpec'
```

Actual shader execution and patch storage, primitive mapping, clipping,
rasterization, native driver/API integration, triangle-domain generation,
physical timing and product qualification remain open. These directed tests do
not establish general quad tessellation conformance or H8 completion.
