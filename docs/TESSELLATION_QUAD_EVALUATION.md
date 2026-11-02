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
triangle area and point count. The bounded generator, evaluation, evaluation
pipeline and patch-pipeline tests, plus the changed-suite and CI catalog
checks, pass on the rebased integration:

```sh
sbt -J-Xmx4G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationQuadEvaluationSpec opengpu.graphics.TessellationQuadEvaluationPipelineSpec opengpu.graphics.TessellationQuadGeneratorSpec opengpu.graphics.TessellationQuadPatchPipelineSpec'
```

The rebased boundary, interior and strip tests also pass, including unequal
edge stitching, exact area and winding, and held-output behavior:

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationQuadBoundarySpec opengpu.graphics.TessellationQuadInteriorSpec opengpu.graphics.TessellationQuadStripSpec'
```

## Shared-CU execution

On the rebased integration, the bounded quad CU evaluator, patch-CU evaluator
and control-admission tests pass, including fault and cancellation drain:

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationQuadCuEvaluatorSpec opengpu.system.TessellationQuadPatchCuEvaluationSpec opengpu.graphics.TessellationCuQuadAdmissionSpec'
```

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
passed invalid-frame rejection. With feedback enabled, separate local cases
also pass exact four-triangle capture and image coverage, a feedback-store
fault, reset after an accepted feedback store, and a reset/fault race. Run
those with the same sbt options and:

```sh
'testOnly opengpu.graphics.TessellationSystemQuadInputImageSharedSpec opengpu.graphics.TessellationSystemQuadInputImageAdmissionSpec'
```

These tests are not native descriptor or API submission evidence.

The two focused admission suites pass three cases covering invalid-frame
rejection and reuse, plus feedback-reply fencing. Run them with:

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationSystemQuadImageAdmissionSpec opengpu.graphics.TessellationSystemQuadInputImageAdmissionSpec'
```

`TessellationQuadPatchSequence` issues multiple GPU-backed quad patches into an
already-open image frame. It checks the input allocation and task-ID range,
advances patch addresses and primitive identities, and retains held work and
completions across cancellation. Its directed test passed address and ID
bounds, ordered advancement, held offers, fault/cancel precedence and reuse:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'testOnly opengpu.system.TessellationQuadPatchSequenceSpec'
```

Driver-facing record ABI and host admission are still separate work.

The internal draft GNT3 sequence reader snapshots a fixed-size quad record,
checks field widths and frame identity, and rejects GNT1/GNT2 records. Its
directed tests passed malformed records, memory faults, cancellation and a
GPU-record-to-patch-sequence handoff:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'testOnly opengpu.system.TessellationNativeQuadSequenceReaderSpec'
```

The draft header and schema are generated from the same layout and pass the
byte-for-byte consistency check and C format, size and offset assertions:

```sh
sbt 'testOnly opengpu.system.TessellationNativeQuadSequenceAbiSpec'
```

These artifacts describe an internal draft; GNT3 is not yet connected to the
native host or exposed as a supported driver submission ABI.

The draft GNQ3 job reader validates job and frame snapshots, bounds draw views,
and drains held offers and memory replies across cancellation. Its directed
tests passed alias rejection, descriptor faults and recovery:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'testOnly opengpu.graphics.TessellationNativeQuadJobReaderSpec'
```

This reader is not yet connected to native host submission.

The draft GNQ3 job header and schema are generated from the reader's job
layout. Their consistency and C definitions pass with:

```sh
sbt 'testOnly opengpu.system.TessellationNativeQuadJobAbiSpec'
```

The quad sequence image owners connect the patch sequencer, input translation
and GNT3 record reader to the internal quad image pipeline. An internal GNQ3
job owner connects that pipeline to the job reader. These owners elaborate
with feedback both enabled and disabled:

```sh
sbt -J-Xmx5G 'testOnly opengpu.graphics.TessellationQuadSequenceImageElaborationSpec'
```

The GNQ3 job owner also rejects invalid frame descriptors before shader or
memory traffic, holds the rejection under backpressure and accepts a later
descriptor for the same check. Reproduce this bounded admission check with:

```sh
sbt -J-Xmx4G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simSeparateBuild=1 \
  'testOnly opengpu.graphics.TessellationNativeQuadJobImageAdmissionSpec'
```

The direct, translated and native sequence image owners pass the same bounded
invalid-frame and repeated-admission checks. Reproduce them with:

```sh
sbt -J-Xmx4G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simSeparateBuild=1 \
  'testOnly opengpu.graphics.TessellationQuadSequenceImageAdmissionSpec opengpu.graphics.TessellationQuadTranslatedSequenceImageAdmissionSpec opengpu.graphics.TessellationNativeQuadSequenceImageAdmissionSpec'
```

The GNQ3 job-image owner also passed a local two-CU translated 4×4 image using
real control, evaluation and fragment shaders. The test checks exact coverage,
job-read fault, cancellation with a held read, and successful reuse. Reproduce
with:

```sh
sbt -J-Xmx8G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simSeparateBuild=1 \
  'testOnly opengpu.graphics.TessellationNativeQuadJobImageSharedSpec -- -z "feedback mode=baseline"'
```

The same two-CU GNQ3 test passed with feedback capture enabled, checking exact
captured words and counters alongside image coverage. Run it with the same sbt
options and `-- -z "feedback mode=capture"` in place of the baseline filter.
Feedback fault/reset recovery and native-host integration remain to be
qualified.

The shared native job probe can optionally recognize GNQ3 as a distinct domain.
With the option disabled it still rejects GNQ3, so the existing two-domain
host cannot accidentally route a quad job to an isoline owner. The probe test
checks both modes, including its existing fault, cancellation and held-result
cases:

```sh
sbt 'testOnly opengpu.system.TessellationNativeJobDomainProbeSpec'
```

The opt-in job router also retains a quad request through backpressure and
delivers cancellation until that owner retires. The default two-domain host
keeps this route disabled. Exercise both routing modes, including a subsequent
isoline job, with:

```sh
sbt 'testOnly opengpu.system.TessellationNativeJobDomainRouterSpec'
```

A three-domain feedback admission gate retains the target job through an owner
switch, a canceled or failed switch, and poisoned feedback state. Its bounded
test also checks later reuse:

```sh
sbt 'testOnly opengpu.system.TessellationThreeDomainFeedbackAdmissionSpec'
```

The three-domain feedback transfer moves live objects between real exporters.
Directed tests preserve exact captured data and counters through isoline, quad,
triangle and isoline; they also check transfer failure, invalid owner, reset,
held commands and reuse. Reproduce with:

```sh
sbt 'testOnly opengpu.graphics.TransformFeedbackThreeDomainTransferSpec'
```

The three-domain bridge connects the transfer, admission gate and shared
feedback memory. Directed tests cover a live capture across all three domains,
memory routing, and failure that blocks all domains until reset:

```sh
sbt 'testOnly opengpu.graphics.TessellationThreeDomainFeedbackBridgeSpec'
```

The isolated three-domain image owner connects this bridge when feedback is
enabled. Its complete feedback behavior has not yet been qualified.

A three-domain payload handoff composes two existing owners and retires them
atomically. Its real-store test switches through isoline, triangle, quad and
isoline again, holding allocation, data and release replies while checking
that ownership cannot move early or leak a response to another domain:

```sh
sbt 'testOnly opengpu.system.TessellationThreeDomainPayloadOwnerSpec opengpu.system.TessellationHostPayloadOwnerSpec'
```

The corresponding three-domain physical memory fabric preserves distinct
transaction ownership across its two arbitration stages. A bounded test
issues the same local ID from all three domains, reorders replies, holds each
reply under backpressure and checks fault attribution and final drain:

```sh
sbt 'testOnly opengpu.system.TessellationThreeDomainMemoryFabricSpec opengpu.system.TessellationHostMemoryFabricSpec'
```

An isolated three-domain image owner connects the job router, shader pool,
payload and memory handoffs, feedback bridge, sample mux and GNZ1 completion
encoding. It elaborates with and without feedback in both local and
memory-backed payload configurations:

```sh
sbt -J-Xmx6G 'testOnly opengpu.system.TessellationNativeThreeDomainJobImageElaborationSpec'
```

The opt-in native host connects this owner, including shared feedback. Its
feedback-enabled two-CU configuration passes elaboration, and the isoline,
triangle and quad host record fixtures pass layout checks:

```sh
sbt -J-Xmx10G 'testOnly opengpu.system.TessellationNativeHostElaborationSpec -- -z "shared feedback with visibility and primitive queries"' \
  'testOnly opengpu.graphics.TessellationNativeHostDomainFixtureSpec'
```

The standalone three-domain owner, without feedback, also passes malformed
envelope and record rejection for all three job formats, exact read accounting
and subsequent reuse. Reproduce with:

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simSeparateBuild=1 \
  'testOnly opengpu.system.TessellationNativeThreeDomainJobImageAdmissionSpec'
```

These checks do not qualify host admission, rendering, faults or recovery. A
combined feedback, visibility and primitive-query host configuration also
passes elaboration. The query admission fence passes failed and poisoned
handoff tests while retaining the accepted context and translation root:

```sh
sbt 'testOnly opengpu.system.TessellationFeedbackAdmissionSpec'
```

The broader native submission and product gates remain open.

The sample mux forwards counts and fault status from all three domains while
retaining a stalled result. Its bounded directed test is:

```sh
sbt 'testOnly opengpu.system.TessellationThreeDomainSampleMuxSpec'
```

The mux connected to the visibility engine also passes multi-domain sample,
fault and address-space isolation checks, including quad samples. A held query
end remains unaccepted while rendering is active; samples accepted from all
three domains, including a stalled quad result, remain in the query before it
ends. This component check does not replace the full host query qualification:

```sh
sbt 'testOnly opengpu.graphics.TessellationThreeDomainVisibilityQuerySpec'
```
Quad job completions now encode reader, nested image and input-fault fields in
the existing GNZ1 layout; the ABI/decoder and all three domain encoders pass:

```sh
sbt 'testOnly opengpu.system.TessellationNativeCompletionInfoSpec'
```

The GNQ and GNT job and sequence record headers for all three domains compile
in userspace and kernel-only include environments with the same word sizes and
record alignment. Their generated layouts and both compilation modes pass:

```sh
sbt 'testOnly opengpu.system.TessellationNativeRecordKernelAbiSpec'
```

This header compatibility does not provide driver submission or validate
resource provenance.

An experimental CPU-snapshot decoder checks GNQ1, GNQ2 and GNQ3 headers,
canonical reserved words and frame/draw address arithmetic. It accepts an
exclusive limit of 2^32 and preserves the hardware's empty-draw behavior.
The bounded C and kernel-compile tests are:

```sh
sbt 'testOnly opengpu.system.TessellationNativeJobValidateSpec'
```

The snapshot checks also compare the shared frame header, tile-count bound,
version and four frame-owner identities against the job. A separate draw
snapshot precheck verifies the domain-specific header, draw index, owner
identity and unbound command ID. They accept the actual generated job, frame
and draw snapshots for all three domains:

```sh
sbt 'testOnly opengpu.graphics.TessellationNativeHostDomainFixtureSpec -- -z "decode actual"'
```

This is not a submission validator: the remaining frame and draw fields,
physical aliases, permissions, lifetime pinning and driver command
publication remain open.

Broader image-level feedback fault/reset qualification, native driver/API integration, triangle-domain
generation, physical timing and product qualification remain open. These tests
do not establish general quad conformance or H8 completion.
