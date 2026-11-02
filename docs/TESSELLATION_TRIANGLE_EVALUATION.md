# Triangle tessellation evaluation handoff

`TessellationTriangleEvaluationPipeline` combines triangle-domain primitive
generation with an external evaluation interface. For each generated vertex,
it converts all three Q0.24 barycentric coordinates to FP32 and sends them with
the task, primitive sequence and vertex index. Point primitives issue one
request; triangles issue three. Up to 64 returned component words per vertex
are retained until the evaluated primitive is accepted.

Each accepted request requires a response, including during cancellation.
Response tag mismatch and evaluator failure produce distinct fault codes; the
pipeline drains the generator and the accepted evaluation before reporting
completion. Completion distinguishes generated and published counts, discard,
invalid input, evaluation failure and cancellation. Outputs remain provisional
until successful completion.

The directed simulation checks exact corner coordinates, a subdivided patch,
point and triangle counts, output area, response tags, evaluator failure,
backpressure, held completion, cancellation, invalid/discarded patches and
reuse:

```sh
sbt -J-Xms512M -J-Xmx5G -J-Xss6M -Dopengpu.productQualification=0 \
  -Dopengpu.simJobs=2 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'testOnly opengpu.graphics.TessellationTriangleEvaluationPipelineSpec'
```

The interface uses an external evaluator. Shared-CU tessellation shader
execution, primitive mapping, clipping/binning, native submission and product
qualification remain open; this test does not establish GLES conformance.
