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

`TessellationTriangleCuEvaluator` adapts that request to the checked patch-input
reader and geometry-CU executor. A bounded directed test exercises all three
coordinate words, all three vertex tags, the full 14-bit sequence range,
patch-read failure, cancellation and reuse. It drives the CU-facing interfaces
with a test harness; it does not by itself establish real shader execution.
Run the same command above with
`testOnly opengpu.system.TessellationTriangleCuEvaluatorSpec`.

The local `TessellationTriangleCuQualificationSpec` connects the generator,
handoff and adapter to one real shared CU. Actual evaluation instructions read
and export all three coordinates for triangle and point patches at levels one
and two; exact corner values, vertex sets, coverage area and ownership drain
pass. This qualification runs outside routine CI:

```sh
sbt -J-Xms512M -J-Xmx10G -J-Xss6M -Dopengpu.productQualification=0 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simSeparateBuild=1 \
  'testOnly opengpu.system.TessellationTriangleCuQualificationSpec'
```

Full triangle shader integration, primitive mapping, clipping/binning, native
submission and product qualification remain open; these tests do not establish
GLES conformance.
