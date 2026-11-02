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
owner must discard provisional output on an error. This remains an internal
execution interface, not a native submission or API contract.

Tests check exact coordinate conversion, three-vertex and point ownership,
backpressure, malformed tags, executor failures, cancellation, discard and
reuse. The combined test covers all-one and 2×2 quad patches, including
triangle area and point count:

```sh
sbt -J-Xmx4G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationQuadEvaluationSpec opengpu.graphics.TessellationQuadEvaluationPipelineSpec opengpu.graphics.TessellationQuadGeneratorSpec'
```

## Shared-CU execution

`TessellationQuadCuEvaluator` connects the tagged request to the existing
checked patch-input reader and geometry-CU executor. It retains the quad
vertex index independently while the isoline-width executor runs one request,
and latches cancellation until accepted work drains. The caller reserves the
CU command identity and holds patch data and shader resources until response.

A local-only test connects the quad pipeline to an actual one-CU `GpuSystem`
and runs a geometry-ABI shader that copies tessellation coordinates into its
output. All-one and 2×2 patches pass in triangle and point modes, including
all three triangle vertex tags and the expected 2×2 triangle area. This test
uses no patch-input selections; it does not validate real control-shader patch
storage, full native dispatch, API behavior or a rendered image. The bounded
bridge test separately checks patch-read data, malformed reads, cancellation
before and after launch, held responses and reuse. Reproduce with:

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=4 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationQuadCuEvaluatorSpec opengpu.system.TessellationQuadCuQualificationSpec'
```

## Controlled patch storage

`TessellationQuadPatchPipeline` reads all four outer and both inner levels
from a retained patch context. `TessellationQuadPatchCuEvaluation` connects
that context to the shared-CU evaluator and arbitrates evaluator reads with
external inspection. The patch remains owned until generation and evaluation
have drained, including on cancellation.

Bounded tests exercise uneven control-written levels, patch inspection,
selected patch inputs, malformed reads, cancellation and reuse. A separate
local-only qualification runs a shader on a real one-CU `GpuSystem` and
reads a manually written per-patch value, including invalid selection,
cancellation and reuse. It does not execute a control shader. Reproduce with:

```sh
sbt -J-Xmx4G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationQuadPatchPipelineSpec opengpu.graphics.TessellationQuadPatchEvaluationSpec opengpu.system.TessellationQuadPatchCuEvaluationSpec'
sbt -J-Xmx6G -Dopengpu.simJobs=4 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationQuadPatchCuQualificationSpec'
```

Control-shader execution, primitive mapping, clipping, rasterization, native
driver/API integration, triangle-domain generation, physical timing and
product qualification remain open. These
directed tests do not establish general quad conformance or H8 completion.
