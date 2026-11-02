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

`TessellationTrianglePatchEvaluation` retains a controlled patch through
generation and evaluation. It reads only the three triangle outer levels and
first inner level, keeps patch inspection available until all accepted work
drains, and releases ownership after the evaluator is idle. A directed test
checks patch data, generated and published counts, held output, cancellation,
evaluation failure and invalid-patch handling. Run the same bounded command
with `testOnly opengpu.graphics.TessellationTrianglePatchEvaluationSpec`.

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

`TessellationTrianglePatchCuEvaluation` combines retained patch ownership with
shared-CU evaluation. Internal patch reads and external inspections share the
inspection port, with stalled offers held and replies routed to their owner.
Outputs remain provisional until successful patch completion. The bounded test
covers retained data, external inspection, an invalid patch input, cancellation
and reuse; run the first command above with
`testOnly opengpu.system.TessellationTrianglePatchCuEvaluationSpec`.

The local `TessellationTrianglePatchCuQualificationSpec` executes a shader on
one shared CU that reads both a triangle coordinate and a controlled patch
output. It checks emitted vertices, invalid input, cancellation and reuse. Run
the local qualification command above with that suite name. This validates a
controlled patch and evaluation shader together; the patch control operations
are driven by the test, not a control shader.

`TessellationCuTrianglePatch` runs the control stage before reading the three
triangle outer levels and first inner level; `TessellationCuTriangleSharedEvaluator`
connects its retained patch to the CU evaluator. The bounded admission suite
checks invalid launches, CU failure, held launches and cancellation. Run the
first command above with `testOnly opengpu.graphics.TessellationCuTriangleAdmissionSpec`.

The local `TessellationCuTriangleSharedEvaluatorSpec` executes real control and
evaluation instructions sequentially on one CU. It checks a level-one triangle,
patch data, a barrier, invalid selected inputs, cancellation and reuse. Run the
local qualification command above with that suite name. This is a direct CU
harness, not the full GPU system or a native rendering submission.

`TessellationCuTriangleSystemClient` routes the control and evaluation kernels
through the tagged shared GPU system. `TessellationSystemTriangleViewport`
maps evaluation output to primitives and forwards them through clipping and the
viewport. Its outputs are provisional until successful completion. The bounded
`TessellationTrianglePrimitiveMapperSpec` checks triangles, points, flat
varyings, invalid links and held output; run the first command above with that
suite name.

The local `TessellationSystemTriangleViewportSpec` executes both shaders on a
one-CU `GpuSystem` and checks triangle and point output, clipped triangle area,
patch data, output backpressure, invalid links, cancellation and reuse. Run the
local qualification command above with that suite name. The viewport fixture is
4 by 4 pixels.

`TessellationSystemTriangleBinPass` stores and replays viewport primitives in
order, reserving the patch and CU resources until the source and bin engines
drain. The local `TessellationSystemTriangleBinPassSpec` runs two real
control/evaluation patches through a one-CU `GpuSystem` into acknowledged bin
storage, then checks ordered replay, held outputs, an identity mismatch, a
storage fault and recovery. It uses a 4 by 4 pixel fixture. Run the local
qualification command above with that suite name.

`TessellationSystemTriangleImagePipeline` connects triangle bin replay to typed
image engines with shared control, evaluation and fragment CU ownership. Its
physical-address interface requires the caller to retain the command and memory
resources through completion. The local `TessellationSystemTriangleImageSharedSystemSpec`
passed baseline and transform-feedback cases for two patches, exact 4 by 4 pixel
coverage and ownership drain. Reproduce it with:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationSystemTriangleImageSharedSystemSpec'
```

`TessellationSystemTriangleInputImage` adds checked patch-input DMA before the
control shader. The local `TessellationSystemTriangleInputImageSharedSpec`
baseline passed two GPU-backed patches, exact 4 by 4 pixel coverage, input
memory fault, cancellation of a held request and recovery. The one-engine
admission test passed invalid-descriptor rejection, held completion and reuse.
The transform-feedback capture case also passed for two GPU-backed patches.
Run the command above with
`testOnly opengpu.graphics.TessellationSystemTriangleInputImageSharedSpec`
to reproduce both cases.

`TessellationTrianglePatchSequence` issues a bounded series of input-backed
patches while retaining task offers and advancing input addresses, task IDs,
primitive IDs and sequences. The directed test passed boundary addresses,
overflow rejection, held offers, cancellation, failures and reuse. Run the first
command above with
`testOnly opengpu.system.TessellationTrianglePatchSequenceSpec`.

`TessellationTriangleSequenceImage` owns a sequence of input-backed patches
within a single frame. Its local two-CU baseline passed exact 4 by 4 pixel
coverage, ordered primitive IDs, input fault, held-request cancellation and
recovery. Its transform-feedback capture case also passed for two patches,
including six captured vertices and exact 4 by 4 pixel coverage. Run the image
qualification command above with
`testOnly opengpu.graphics.TessellationTriangleSequenceImageSharedSpec -- -z baseline`
or `testOnly opengpu.graphics.TessellationTriangleSequenceImageSharedSpec -- -z capture`.

`TessellationNativeTriangleSequenceReader` snapshots a private, fixed-width
GNT2 triangle draw record before issuing a checked patch sequence. It checks
record bounds, canonical fields, identity, reserved words and unsupported
configuration; accepted requests and child work drain through cancellation. GNT2
uses magic `0x474e5402` and version 2, separate from the isoline GNT1 format.
The reader tests passed snapshot, invalid-record, fault, cancellation and
sequencer handoff cases; both readers reject the other domain's record. The
generated header and schema are checked by `TessellationNativeTriangleSequenceAbiSpec`:

```sh
sbt 'runMain opengpu.elaboration.EmitTessellationNativeTriangleSequenceAbi --check' \
  'testOnly opengpu.system.TessellationNativeTriangleSequenceReaderSpec opengpu.system.TessellationNativeTriangleSequenceAbiSpec opengpu.system.TessellationNativeSequenceReaderSpec'
```

`TessellationNativeTriangleJobReader` snapshots a GNQ2 job and GNF1 frame,
checks bounded GNT2 draw views and rejects descriptor/image aliases. The GNQ2
magic is `0x474e5102`; its job header version remains 1. Job-reader tests passed
bounds, alias, fault, cancellation, recovery and reciprocal GNQ1/GNQ2 rejection.
The generated header and schema are checked by `TessellationNativeTriangleJobAbiSpec`:

```sh
sbt 'runMain opengpu.elaboration.EmitTessellationNativeTriangleJobAbi --check' \
  'testOnly opengpu.graphics.TessellationNativeTriangleJobReaderSpec opengpu.graphics.TessellationNativeJobReaderSpec opengpu.system.TessellationNativeTriangleJobAbiSpec'
```

`TessellationNativeTriangleSequenceImage` connects GNT2 draw snapshots to the
translated patch sequence and typed image engines. Its local two-CU baseline
passed exact 4 by 4 pixel coverage, primitive ordering, translated record and
input reads, input and record faults, held-request cancellation and recovery.
It retains mappings and allocations through drain. Reproduce with:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationNativeTriangleSequenceImageSharedSpec -- -z baseline'
```

The baseline does not qualify transform-feedback modes or native host dispatch.

The existing GNZ1 completion layout also encodes triangle job, image and input
faults. `TessellationNativeCompletionInfoSpec` passed the software ABI check,
isoline completion fields and triangle fault validity and field encoding. Run:

```sh
sbt 'testOnly opengpu.system.TessellationNativeCompletionInfoSpec'
```

`TessellationNativeJobDomainProbe` selects GNQ1 or GNQ2 from a translated
job-header read. The caller must retain the descriptor and translation through
job completion; the selected reader still validates the entire record. The
probe test passed both formats, invalid envelopes and magic, memory faults,
held offers, same-cycle replies, cancellation, read-byte accounting and reuse:

```sh
sbt 'testOnly opengpu.system.TessellationNativeJobDomainProbeSpec'
```

`TessellationNativeJobDomainRouter` retains the selected job through child
acceptance and retirement, deferring a pending cancellation until the selected
child owns it. `TessellationNativeJobDomainRouterSpec` passed both domains,
held offers, cancellation, failure reporting and reuse:

```sh
sbt 'testOnly opengpu.system.TessellationNativeJobDomainRouterSpec'
```

`TessellationNativeTriangleJobImage` connects the GNQ2 job reader to translated
GNT2 image sequences. Its local two-CU baseline passed two patches, exact 4 by 4
pixel coverage, descriptor memory fault, held-request cancellation and recovery.
The one-engine admission test passed invalid-envelope rejection, held
completion and reuse. Reproduce with the image qualification command above and
`testOnly opengpu.graphics.TessellationNativeTriangleJobImageSharedSpec -- -z baseline`;
run `testOnly opengpu.graphics.TessellationNativeTriangleJobImageAdmissionSpec`
for the bounded admission check. Feedback modes are not covered by these runs.

`TessellationNativeDomainJobImage` joins the domain router to both native job
owners with shared translated memory, shader and optional payload ownership.
Its one-engine admission test passed invalid envelopes and unknown records,
held completion, exact read accounting and reuse. It also elaborated with and
without a local payload pool. Run:

```sh
sbt 'testOnly opengpu.system.TessellationNativeDomainJobImageAdmissionSpec opengpu.system.TessellationNativeDomainJobImageElaborationSpec'
```

`TessellationDomainFeedbackBridge` selects the feedback owner while transferring
live capture objects between drained domains. Its directed tests passed memory
and command routing, held reset, and a capture across two real exporters with
exact counters and stored values. Run:

```sh
sbt 'testOnly opengpu.graphics.TessellationDomainFeedbackBridgeSpec'
```

`TessellationDomainFeedbackAdmission` holds a classified job until its feedback
owner is selected. Its directed test passed successful switching, cancellation
while a switch was outstanding, transfer failure and reuse. Run:

```sh
sbt 'testOnly opengpu.system.TessellationDomainFeedbackAdmissionSpec'
```

The bridge and admission controller are not yet connected to the domain owner
or host admission.

Full host execution and domain switching are not yet qualified.

The probe and router are not yet connected to the native host command path.

These draft readers are not connected to the native host command path. Native
submission and product qualification remain open; these tests do not establish
GLES conformance.
