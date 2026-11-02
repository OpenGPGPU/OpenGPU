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

## Control-CU execution

`TessellationCuQuadPatch` connects the control warp scheduler to six-level
quad generation and retains the patch through evaluation. The shared evaluator
routes control and evaluation launches and arbitrates patch inspection with
selected evaluator reads. The caller reserves the CU interfaces, command ID
and shader resources, drains accepted work on cancellation and treats outputs
as provisional until success.

A local-only four-lane, two-warp same-CU test executes a control shader that
writes all six levels and a per-patch word and crosses a barrier. A real
evaluation shader reads the patch word at all triangle vertices. The test also
checks an invalid selection, cancellation during evaluation and reuse. A
bounded admission test covers malformed launches, CU failure and cancellation.
Reproduce with:

```sh
sbt -J-Xmx4G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationCuQuadAdmissionSpec'
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationCuQuadSharedEvaluatorSpec'
```

## Primitive mapping and viewport

`TessellationQuadPrimitiveMapper` maps evaluated points and triangles to the
shared primitive format, including linked varyings and the last triangle
vertex as the flat-shading source. `TessellationSystemQuadViewport` connects
control and evaluation on the shared CU pool to that mapper, the existing clip
stage and viewport conversion. Outputs are provisional until completion; the
caller must reserve the tag and resources and drain accepted work.

The mapper test covers both shading modes, points, triangles, invalid links and
backpressure. A local-only one-CU system test runs real control and evaluation
shaders through the clip and viewport stages for triangles and points, checks
window output, backpressure,
cancellation, mapping faults, invalid viewport and reuse. Reproduce with:

```sh
sbt -J-Xmx4G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationQuadPrimitiveMapperSpec'
sbt -J-Xmx6G -Dopengpu.simJobs=4 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationSystemQuadViewportSpec'
```

The system test covers an inside-frustum patch and a patch crossing two clip
planes, with its clipped window-space area checked.

## Bin storage and replay

`TessellationSystemQuadBinPass` retains the quad viewport owner through
acknowledged bin writes and replay. It checks pass identity and sequence
capacity, holds task and pass reports until accepted, and drains the source and
bin engines on failure or cancellation. Replay records do not claim geometry
invocation or layer metadata.

A local-only one-CU test runs two control/evaluation patches into bin storage,
checks the four replayed triangles and sequence order under backpressure, and
checks identity rejection, a bin write fault and recovery. Reproduce with:

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=4 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationSystemQuadBinPassSpec'
```

## Pre-clip feedback

The optional viewport feedback owner captures the three evaluated vertices of
each quad triangle before clipping, preserving the original varying values
before flat shading. A local one-CU test checks two triangles and their six
captured vertices, raster discard, memory fault, cancellation and reset,
including fault/cancellation combinations and a healthy retry. It checks that a
CU trap follows cancellation or an acknowledged injected fault. Reproduce with:

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=2 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationSystemQuadFeedbackSpec'
```

## Typed image pipeline

`TessellationSystemQuadImagePipeline` connects bin replay to the shared typed
image engines, which rasterize and run fragment shaders through the shared CU
pool. The physical-address interface is internal and retains shader and memory
resources until the source, render engines and publication responses drain.
The 4×4 local qualification ran two control/evaluation patches and a fragment
shader against an RGBA8 attachment. Four primitives completed in one tile;
the exact 4×4 red-channel coverage, completion and memory/payload drain passed.
With pre-clip feedback enabled, the same two-CU image path also captured four
triangles and twelve vertices in order, checked exact capture words and image
coverage, and fenced a pending feedback end command until the frame drained.
With raster discard enabled, it captured the same vertices while the image
remained at its clear value and no primitive reached the rasterizer.
Separate local cases inject a feedback store fault, reset after an accepted
store, and reset racing a store fault; each checks task/frame status and drain,
then reuses the same owners for a healthy frame with exact capture and image.
The bounded admission test passed invalid-frame rejection, held completion and
reuse, and fencing while a feedback control reply is held. These checks do not
establish broader image or native API conformance. Run it and the bounded admission checks
with:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationSystemQuadImageSharedSystemSpec opengpu.graphics.TessellationSystemQuadImageAdmissionSpec'
```

`TessellationSystemQuadInputImage` adds the existing checked patch-input DMA
before control-shader launch. The enclosing owner retains the input allocation,
translation and memory transaction until the frame and accepted traffic drain.
The local 4×4 input-image test passed real shader reads, exact red-channel
coverage, input memory faults, cancellation and reuse; the admission test also
passed invalid-frame rejection. Run
those with the same sbt options and:

```sh
'testOnly opengpu.graphics.TessellationSystemQuadInputImageSharedSpec opengpu.graphics.TessellationSystemQuadInputImageAdmissionSpec'
```

These tests are not native descriptor or API submission evidence.

Broader image-level feedback fault/reset qualification, native driver/API integration, triangle-domain
generation, physical timing and product qualification remain open. These tests
do not establish general quad conformance or H8 completion.
