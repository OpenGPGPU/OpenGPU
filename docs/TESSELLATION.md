# Tessellation

GLES 3.2 geometry and tessellation run on the shared CUs: patch control,
evaluation, primitive generation, window bins and native host transport.
Each subsection below is one bounded contract; the capability rows stay in
`MOBILE_GPU_CAPABILITY_MATRIX.md` (E32-01 to E32-04) and
`GLES32_HARDWARE_GAPS.md`.

## Tessellation control barrier

`TessellationBarrier` tracks one patch with up to 32 control invocations. It
releases invocations only after all have arrived at the same barrier ID, retains
stalled releases, and reports duplicate, out-of-range or divergent arrivals.
Repeated barriers use increasing epochs; completion and cancellation retain
ownership until the result is consumed. A cancellation while a release is held
preserves that offer until it is accepted.

The enclosing shader scheduler must drain each invocation's preceding patch
writes before presenting its barrier arrival and propagate cancellation to the
invocations. This component does not yet connect the patch store or implement
shader execution. The [OpenGL ES 3.2 specification, §11.2.1.2](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf)
requires the control-shader barrier and output visibility behavior.

The compact test covers 32 invocations, two barriers, held releases, divergent
IDs, early completion, invalid IDs, cancellation and task reuse:

```sh
sbt 'testOnly opengpu.graphics.TessellationBarrierSpec'
```

Patch-store integration, shared-CU control shader execution and H8 remain open.

## Experimental tessellation completion snapshot

The opt-in native tessellation host writes a GNZ1 snapshot to the same twelve
read-only MMIO words used for native binned completions, beginning at `0x180`.
Read the words while the corresponding completion is held: POP or reset clears
its visibility. Check the magic before interpreting the snapshot; GNZ1 and GNX1
are distinct formats. This layout and decoder do not advertise a product
capability or implement driver submission, allocation pinning, or alias checks.

The generated [C layout](../driver/opengpu_native_tessellation_completion_abi.h)
and [schema](abi/native-tessellation-completion-v1.json) describe the fields.
The [decoder](../driver/opengpu_tessellation_completion.h) accepts host-order
MMIO register values, rejects wrong magic and reserved or inconsistent fields,
and leaves its output unchanged on failure. Reader address and draw are raw
owner fields; only interpret them with the reported reader stage. The tile and
input fault fields are meaningful only when their respective valid bits are set.
The legacy completion counters are not a qualified tessellation diagnostic ABI.

The unified completion drain captures a valid fault snapshot before POP when
the reported opcode is tessellation and the register aperture contains the
whole window. It retains the decoded fields in the internal fault snapshot only
when the command ID and opcode match the waiting fence. Other faults clear that
internal detail. IRQ and polling drains serialize the status check, snapshot
reads and POP under the fence lock; abort uses the same lock when discarding a
completion. A pthread-based mock runs the actual drain concurrently with a
held completion window for IRQ/poll and abort/drain races: the pre-fix code
popped twice and read after POP, while
the locked code retains one pop and the matching snapshot. Reproduce with
`python3 scripts/test_unified_completion_race.py`; it does not exercise Linux
IRQ scheduling or real MMIO. A bad magic, invalid field or status mismatch is
ignored; the existing public fault ioctl is unchanged. Runtime tessellation
submission and public reporting of these details are not implemented.

Reproduce the hardware-layout, generated-file and C decoder checks with:

```sh
sbt 'testOnly opengpu.system.TessellationNativeCompletionInfoSpec'
```

The decoder and generated definitions support both userspace and kernel builds.
A small test module using the decoder also built against Ubuntu Linux 6.8
headers. With a configured kernel headers tree, reproduce that check with:

```sh
bash scripts/test_tessellation_completion_kernel.sh /path/to/linux-headers
```

The affected driver objects compile against Ubuntu 24.04 Linux 6.8 headers:

```sh
make -C /path/to/linux-headers M="$PWD/driver" opengpu_hw.o opengpu_compute.o
```

The full driver module also builds with this change against Ubuntu Linux
7.0.0-42 arm64 headers (`make -C /path/to/linux-headers M="$PWD/driver"
modules`) and the configured Linux 7.2.0 arm64 tree:

```sh
make -C /path/to/linux-source O=/path/to/linux-build M="$PWD/driver" \
  ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- modules
```

A full module build on Linux 6.8 remains unqualified; that kernel also
requires DRM scheduler and vblank API adaptation. Full driver integration,
physical alias provenance and product qualification remain open.

## Controlled isoline primitive mapping

`TessellationControlledIsolineRenderer` maps evaluated control-patch outputs into
assembled point or line primitives for the existing graphics path. The owner
supplies the output linkage and unique sequence and primitive-ID bases. Sequence
or primitive-ID overflow and invalid linkage report a mapping failure.

Evaluation requests, inspection replies and mapped output are drained during
cancellation. Mapped output remains provisional until successful completion;
completion waits for any held mapping result. Patch storage can be released
once evaluation and inspection drain because mapping uses copied output words.
The external evaluator, native control/evaluation shader ABI, remaining domains,
whole renderer integration and product qualification are separate requirements.

Reproduce the bounded test:

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationControlledIsolineRendererSpec'
```

## Tessellation control vector access

`TessellationControlVectorAccess` converts a decoded vector-issue record into
the control warp scheduler's checked patch-access request and vector-commit
record. It accepts a task-owned warp base supplied by its enclosing dispatcher.
The internal operation selector captures store values, loads or stores; it is
not an instruction encoding or a public ABI. Loads use `vs1` for the vertex
index and `vs2` for an address word: bits 0, 1 and 2 select output, per-patch
and tessellation-level storage, and bits 9:3 select the component. Higher
address bits and vertex bits above bit 5 are rejected before patch traffic.
The patch context checks the actual layout and access permissions.

Store values are captured per lane and warp slot by the parameter operation.
`kernelStart` and `warpStart` invalidate them. Stores select the execution and
side-effect masks; loads and parameter capture select the execution mask.
Inactive load lanes retain old destination data. Empty masks complete without
patch traffic. An error suppresses vector and scalar writeback and exposes a
nonzero status; earlier patch writes are not rolled back. An offered request is
held across cancellation and its result must drain before the commit. When the
context reports a terminal error, the owner asserts `terminal`; that retires a
still-unaccepted offer, while an accepted request still drains its result. The owner
must convert failed status to an attributed shader fault, retain the patch,
warp mapping and instruction until commit is accepted, and prevent that warp
from issuing a barrier or another instruction before the commit. The scheduler
releases its access ownership when this bridge accepts the patch result.

The bounded vector checks cover masks, store-value lifetime, held requests and
commits, malformed operands, partial/faulted replies, cancellation and reuse.
The composed test writes four control outputs, synchronizes at the patch
barrier, then reads them in reverse order through vector issue and commit.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.productQualification=0 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationControlVectorAccessSpec opengpu.graphics.TessellationControlVectorContextSpec opengpu.graphics.TessellationControlWarpSchedulerSpec'
```

The [opt-in CU path](#cu-tessellation-control-patch-access) connects this bridge
to task-scoped decode and the vector fault owner for bounded real-CU execution.
CU barrier and finish routing, general warp-base ownership, native submission,
TCS/TES compiler and driver support, other tessellation domains and physical
and product qualification remain open. H8 remains open.

## Tessellation control warp ownership

`TessellationControlWarpScheduler` composes one patch context with the warp
patch-access and barrier adapters. A warp owns its accepted access until the
result is consumed, or its accepted barrier until its resume is consumed.
Another operation for that warp cannot overtake it. A different warp may park
or make progress while an access is outstanding. The scheduler blocks finish
events for invocations in parked warps and blocks all finishes while the
single access owner is busy, including on the access-acceptance cycle. This
conservative rule covers predicated operations whose access mask omits other
live lanes. On a simultaneous park and finish, a finish belonging to that park
waits.

The enclosing dispatcher must provide stable, disjoint invocation-to-warp
mappings. It must stop issuing and cancel the patch after an access error or a
failed barrier resume; these adapter errors are returned to the dispatcher and
do not themselves finish the patch context. It must consume accepted
access results, barrier resumes, control results and external patch replies,
including during cancellation. Patch closure waits for these owned warp results
and resumes; a context error terminates unaccepted adapter offers while accepted
patch accesses drain. The context retains its checked per-invocation permissions
and barrier-divergence rules. Stores completed before a later error are not
rolled back.

The composed test covers two 16-lane warps and 32 control invocations, exact
patch reads and writes around a barrier, held results, predicated finish
ordering, divergent barrier IDs, cancellation, close drain and reuse.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.productQualification=0 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationControlWarpSchedulerSpec opengpu.graphics.TessellationWarpPatchAccessSpec opengpu.graphics.TessellationWarpBarrierSpec'
```

This internal ownership layer does not yet connect CU issue, instruction
execution or warp finish to the context. The TCS/TES ABI and compiler, native
submission, other tessellation domains and physical and product qualification
remain open; it does not close H8.

## CU tessellation barrier and finish ownership

For an enabled tessellation control task, CU barrier instructions are routed to
the patch scheduler. The adapter snapshots each blocked warp's next PC, mask
and task-owned patch base, waits for CU shared memory to be idle before
offering a park, and retains the path until the matching resume. Barrier IDs
use the post-barrier PC, so divergent program locations are detected by the
patch context. Cancellation and invalid resumes route an attributed trap through
the existing finish-on-trap owner: cancellation reports shader interruption,
and an invalid resume reports an illegal barrier instruction. An offered park remains held until accepted
or until the context's terminal error is accepted.

Normal CU warp completion is delayed until each active invocation's finish is
accepted by the patch context. The original launch mask and base remain owned,
and a held finish prevents the CU kernel completion. Faulted warps retire
without reporting successful patch finishes; the enclosing owner must cancel
and drain the patch. The owner also accepts context errors and forwards the
terminal pulse to the CU. Finished stores are not rolled back. Ordinary
non-tessellation tasks retain the existing CU-local barrier and finish path.

The bounded adapter tests cover held offers and responses, mismatched resumes,
cancellation, terminal drain and faulted warp retirement. The real-CU test
executes patch stores, a shader barrier and loads across two and eight
four-lane warps, including a 32-invocation patch. It holds a finish handshake
and verifies that kernel completion waits, checks patch barrier and completion
counts, and exercises fault and cancellation drain. This is not a default-lane
product workload, GLSL or native submission ABI, nor physical timing or product
throughput qualification. A two-warp ordinary kernel also exercises the local
barrier with the capability built in but disabled for its task.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationCuBarrierSpec opengpu.graphics.TessellationCuFinishSpec opengpu.core.GpuComputeUnitTessellationSpec opengpu.core.frontend.decode.TessellationExecutionAvailabilitySpec opengpu.core.GpuComputeUnitBarrierEpochSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

The enclosing task still supplies and retains warp-base mappings, shader and
patch resources, completion and cancellation ownership. Full driver/compiler,
native submission, other tessellation domains, complete resource and fault
semantics, physical timing and product workloads remain H8 requirements.

## CU tessellation control patch access

`GpuComputeUnit(tessellationAvailable=true, finishOnTrap=true)` can execute
task-owned control patch word operations through its vector issue and commit
path. The feature is absent by default. `tessellationEnabled` and the physical
warp-slot to patch-invocation base table are captured on kernel acceptance;
the patch lease remains asserted until the kernel completion is consumed. The
enclosing owner must supply correct, disjoint warp mappings, retain the patch,
route requests and results, and propagate cancellation and terminal context
errors until all accepted work drains.

The opt-in internal CUSTOM-1 functions 20, 21 and 22 implement parameter
capture, load and store. They use the operand format in
[the vector bridge](#tessellation-control-vector-access). Decode rejects the
operations when this capability is unavailable or disabled, even if texture
sampling is enabled. The CU asserts that an enabled patch task has one fully
resident, one-dimensional workgroup of 1–32 invocations. A failed patch access
suppresses its destination write and uses the existing vector fault path: the
fault records the instruction PC and load/store cause, with the active warp
mask and a zero address. It does not identify an individual failed lane.
Earlier lane stores can already be visible.

The bounded real-CU test executes parameter capture, store and load against
four- and eight-invocation patches, verifies task enable and warp bases are
snapshotted, checks a store access fault and instruction PC, and executes a
healthy successor. The eight-invocation case uses two distinct physical warps
with checked patch bases. Decoder checks exercise both vector mask-bit states
and disabled capability profiles. A four-lane/eight-warp CU also executes a
32-invocation patch through the shared barrier and finish owner; this does not
establish full default-profile or product throughput.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.core.GpuComputeUnitTessellationSpec opengpu.core.frontend.decode.TessellationExecutionAvailabilitySpec opengpu.graphics.TessellationControlVectorContextSpec'
```

This is an internal CU interface, not a GLSL or driver ABI. CU barrier and
finish events route to the control warp scheduler when the task capability is
enabled. The CU holds the original warp path while the patch barrier completes
and waits for per-invocation finish handshakes before retiring a normal warp.
An accepted context error must be forwarded on `tessellationTerminal`; a
faulted warp retires without reporting successful patch finishes, and the owner
must cancel and drain the patch. Unavailable or disabled tasks retain the
ordinary CU workgroup barrier. A barrier resume already offered to the CU
remains owned through backpressure; the owner must also use CU interruption
when cancellation requires stopping already resumed work.

Fault lane attribution, compiler and driver
admission, native submission, other tessellation domains, physical timing and
product throughput remain open; H8 remains open.

## Control-CU isoline patch ownership

`TessellationCuIsolinePatch` retains one patch context from input loading and
sealing through a control CU workgroup, isoline generation, evaluation and
close. It accepts a single 1D workgroup whose size equals the patch output
vertex count and fits in the configured CU and the 32-invocation patch limit.
The task snapshots the kernel and warp-base mapping. The caller owns CU
reservation, shader resources and correct warp-base mappings for the duration.
After control completion, the owner reads the retained outer levels and sends
generated coordinates to the external evaluation interface. Evaluated outputs
are provisional until successful task completion.

A held kernel offer remains stable even on cancellation. The caller must
interrupt the CU when `cancelCu` is asserted, forward `terminalCu`, and return
its completion; accepted work and evaluator responses must drain before the
patch closes. Completion distinguishes admission/control failure, CU failure,
evaluation failure and cancellation. No rollback of accepted patch stores or
provisional output is implied.

The small real-CU test executes a control program that writes both outer
levels, synchronizes, and finishes, then checks the generated endpoints using
a backpressured synthetic evaluation responder. The admission test exercises
invalid launches, CU failure, cancellation of a held launch and reuse. This
does not yet connect a real evaluation shader, renderer, driver or compiler,
and is not default-profile timing or 720p product qualification.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationCuIsolineAdmissionSpec opengpu.graphics.TessellationCuIsolinePatchSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

## Control and evaluation shaders for retained isolines

`TessellationCuIsolineSharedEvaluator` connects the control-CU patch owner to
`IsolinePatchInputReader` and `IsolineGeometryCuExecutor`. After control
completion, each generated coordinate receives an ordered, checked snapshot of
up to 61 selected control-patch words before the evaluation shader runs. The
shader sees tessellation coordinates in input vertex 0 components 0–2, followed
by the selected words, using the existing scalar geometry instruction ABI. A
bad selection fails evaluation. External inspection and internal reads share the
retained context, with held offers and replies routed to their issuer.

The caller reserves the control CU interface, shared evaluation command ID and
shader resources through completion. The two launch interfaces can be routed to
one CU after control completion; the enclosing owner must provide that routing,
forward cancellation and drain accepted launches, responses and outputs.
Outputs are provisional until successful completion. This is an internal
one-patch isoline path; native scheduling and submission, other tessellation
domains, compiler integration and product qualification remain open.

The four-lane/two-warp same-CU simulation executes a control shader that writes
both tessellation levels and a per-patch word and crosses a barrier. A real
evaluation shader reads the generated coordinate and the selected patch word;
the test checks both endpoints, invalid-selection failure, cancellation while
the evaluation CU is owned and subsequent reuse. It does not render an image or qualify the default product profile.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationCuIsolineSharedEvaluatorSpec'
```

## Tessellation edge coordinates

`TessellationEdgeCoordinate` converts a positive prepared FP32 level, spacing,
index and optional inner-level promotion into a deterministic Q0.24 edge
coordinate. Equal spacing uses equal segments. Fractional spacing places the
two shorter segments at opposite ends, with the other segments equal; the
clamped FP32 level determines their lengths. A bounded iterative divider rounds
to nearest, ties to even. Endpoints are exact, and a held response survives
backpressure. Cancellation is reported after accepted work drains.

The unit test compares more than 700 directed and randomized coordinates to an
independent exact-rational reference, including all indices of boundary levels,
inner-level promotion, invalid requests and cancellation:

```sh
sbt 'testOnly opengpu.graphics.TessellationEdgeCoordinateSpec'
```

The implementation uses 24 divider cycles for an interior coordinate, in
addition to level preparation. It is an internal building block: patch-domain
primitive construction, FP32 shader input conversion, throughput qualification
and H8 completion remain open. The [OpenGL ES 3.2 specification, §11.2.2](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf)
defines tessellation edge spacing and invariance requirements.

## Owned fragment code for native tessellation

The internal `opengpu_tessellation_resources_sched_submit` entry requires a
fragment-code plan for every GFS state referenced by a nonempty rasterized
sequence. Missing, duplicate or conflicting plans reject before enqueue or
fence publication. There is no caller-owned code fallback in this entry.
Zero-count sequences, unreferenced states and raster-discard jobs require no
fragment program. The older trusted and uniform-only entries retain their
existing producer obligations.

Plans contain stable kernel code bytes, a byte length, an entry offset and the
expected compilation base VA. Referenced programs are packed in state-handle
order at 64-byte boundaries within the existing 4 MiB private fragment-code
window, slot 1. The expected base must equal the planned address; lengths and
entries must be word aligned, the entry must lie inside its program, and the
complete rounded mapping must fit its window and the 32-bit physical address
space. An exclusive physical end at 4 GiB is valid. No product resource or RTL
capacity changes.

The scheduler copies each program, zeroes allocation padding and rewrites only
the copied GFS entry PC using the generated ABI field. Caller code and plans
may be released after submit returns. Code pages are private, read/execute and
not writable; descriptor pages remain read-only and non-executable. The actual
transport verifies exact backing, permissions and absence of writable aliases
in that VM under the submission lock, then invalidates code and descriptors
before publishing the command.

The run worker reserves tracking capacity before mapping either kind of
snapshot, so a full tracking array cannot leave an untracked mapping.
Retirement revokes every recorded window before freeing backing. Failed
revocation wedges submission and retains code, descriptors, context and
dependencies; it is not automatic recovery.

This is an ownership contract for trusted compiled code. The producer must
compile or relocate embedded addresses for the declared VA, or supply code
that is position independent with respect to its own address. Copying and
rewriting an entry does not relocate instructions or validate arbitrary
control flow. Control/evaluation programs, other resource operands,
cross-context data writers and the remaining admission obligations stay with
the trusted producer. No userspace ioctl, GLES capability or H8 completion is
introduced.

The production C scheduler, MMU and command functions pass strict Clang and
ASan/UBSan checks for all three domains, exact capture after caller-byte
replacement, required-plan and VA failures, the full code window, physical
address boundaries, occupied mappings, writable aliases, abort, failed
revocation and healthy reuse. Existing descriptor, uniform, 32-unit texture
and three-domain export checks also pass. These are CPU tests with mocked
device completion; they do not establish native GPU execution or timing.

Reproduce the focused ownership checks:

```sh
python3 scripts/test_tessellation_schedule.py --owned-code
CC='cc -fsanitize=address,undefined -fno-omit-frame-pointer' \
  python3 scripts/test_tessellation_schedule.py
python3 scripts/test_tessellation_canonical.py
python3 scripts/test_tessellation_transport_submit.py
cc -std=c11 -O2 -Wall -Wextra -Werror \
  -fsanitize=address,undefined -fno-omit-frame-pointer \
  -Idriver/tests/mmu_stubs driver/tests/opengpu_mmu_test.c -o /tmp/opengpu-mmu-test
/tmp/opengpu-mmu-test
```

## Internal tessellation command routing

The opt-in `TileBinHostCommandPort` route reserves opcode 15 for a GNQ1 job.
It uses the existing 512-byte command envelope and 33-bit exclusive limit,
rejecting unsupported dependency and feedback flags through the envelope-valid
bit. The job is offered only when the shared render and memory dependencies are
idle. A submitted command retains its ID until its tessellation completion is
accepted, and prevents another render or memory command from entering during
that ownership; ordinary kernels retain the existing overlap policy.

The route is disabled by default. The enclosing host must include the actual
tessellation engine and all its memory in `renderIdle`, keep the VM and
allocations pinned, connect cancellation and completion details, and establish
physical alias provenance. No host capability is advertised here; wiring the
real job-to-image owner, system-level MMIO/AXI submission, complete reset/drain semantics,
other tessellation domains and product qualification remain open.

The optional `GpuHostAxi` transport exposes this submit/completion pair to its
enclosing system and includes the external tessellation drain in graphics and
VM ownership. A focused AXI test submits a job, verifies the pinned VM through
the live and held-completion phases, and checks reset cancellation. This is a
stubbed owner test: the production system still has to attach the actual job
engine and its memory and shared-CU paths before the command is available.
The focused host test and existing AXI ownership/native DMA regressions pass
six tests; test-selection checks pass as well.

The focused route and existing query, feedback, image and completion-port
regressions pass 21 tests; test-selection checks also pass.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationHostCommandPortSpec opengpu.graphics.TileBinHostCommandPortSpec opengpu.graphics.TileBinHostPrimitiveQuerySpec opengpu.graphics.TileBinHostQueryCommandPortSpec opengpu.graphics.TileBinHostTransformFeedbackSpec opengpu.graphics.TileBinHostImageCommandPortSpec opengpu.graphics.NativeBinnedCompletionInfoSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationHostAxiCommandSpec opengpu.system.GpuHostAxiOwnershipSpec opengpu.system.NativeControlHostDmaSpec'
```

## Internal host payload-producer handoff

`TessellationHostPayloadOwner` selects either the legacy or tessellation
renderer for the shared pool's existing producer ports. It retains that choice
until accepted control and data replies are consumed and every successful
allocation has a successful release reply. It counts allocations and releases
rather than tracking just slot bits: the pool may reuse a freed slot while its
previous release acknowledgment is still held. Spill and cancel replies do not
release a resident lease. Data and control response ownership is checked against
the payload store's per-port reply capacities.

The owner must have exclusive use of these producer ports from reset. The
enclosing system may request handoff only after the outgoing renderer and its
shared-CU router have stopped producing work; the owner itself checks its
producer offers, replies and lease count before accepting handoff. The module
does not wire the renderers, pin the VM, qualify aliasing, or establish full
host reset behavior, product throughput or physical timing.

Two tests pass: one uses the real banked store and reuses its sole slot while
an older release reply is held; the other exercises reordered replies, spill,
cancel, held data faults and isolation between the two producers.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationHostPayloadOwnerSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

## Internal host shader-pool adapter

`TessellationHostShaderRouter` adapts the existing native renderer's ordinary
graphics port and the tessellation job owner's geometry/tessellation port to
one tagged shared-CU pool. The existing router retains task IDs, traps, texture
traffic, invalidations and optional payload leases. Legacy launches always
carry disabled geometry and tessellation sidebands; unexpected patch or
geometry traffic from a legacy-owned CU asserts. Tessellation traffic follows
the accepted task owner, including held responses and patch bases.

The focused test exercises two simultaneous clients with equal local IDs,
distinct payload leases, held launches, tessellation patch traffic and
reordered completions. This is an internal component, not a connected host:
the full system must still wire the job owner, memory bridge, payload pins,
VM/reset drain and command path and qualify their combined behavior and timing.
The focused adapter and existing tessellation/payload-router suites pass five
tests, including one-, two- and four-CU payload configurations.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationHostShaderRouterSpec opengpu.graphics.SharedShaderTessellationRouterSpec opengpu.graphics.SharedShaderPayloadRouterSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

## Translated tessellation input memory

`TessellationInputMemoryFabric` combines the renderer's line requests with the
read-only patch-input DMA port through the existing `TileBinHostMemoryFabric`.
The ports use independent Sv32 translation clients, response ownership and page
walker IDs. Patch input uses local transaction ID zero; render IDs retain their
original values at the renderer port. The shared physical port uses the host
fabric's disjoint ID ranges and buffered request arbitration.

The enclosing owner retains the address space, mappings and allocations until
`drained` and all frame work complete. A held response keeps the fabric
undrained; translation faults return to the requesting client. Input allocation
bounds are checked by `TessellationPatchInputDma` before traffic. The fabric
does not establish native submission, physical alias provenance or reset
integration, and it does not replace the caller's ownership rules.

The focused tests cover concurrent Sv32 translation, independent page walks,
reordered replies, a stalled input response, input-page faults and drain. They
also connect the actual patch-input DMA and verify a GPU virtual input word is
loaded into the patch-access port through the translated physical request.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationInputMemoryFabricSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

## Isoline evaluation handoff

`TessellationIsolineEvaluation` holds one generated point or line while an
external evaluation executor processes its vertices. It translates the
generator's Q0.24 coordinates into exact IEEE binary32 `gl_TessCoord` values,
issues one tagged request at a time, and retains the 64 component words returned
for each vertex until the downstream consumer accepts the primitive. Point
mode issues one request; line mode issues two. A mismatched response tag or
execution failure produces a held fault instead of a primitive.

The executor must return exactly one response per accepted request, including
while an enclosing task is being cancelled. The enclosing owner must drain this
handoff before releasing patch storage or shader resources. Inputs must be
coordinates from the bounded isoline generator (0 through 1 in Q0.24). This
handoff is an internal execution contract, not a shared-CU shader implementation
or a native API. It does not establish shader invocation reuse, overall
tessellation conformance, physical timing, or product throughput.

The focused check covers exact coordinate conversion, ordered point and line
requests, payload retention under backpressure, tag and execution faults, and
reuse after completion:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationIsolineEvaluationSpec'
```

## Isoline generation and evaluation ownership

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

## Tessellation isoline primitive generation

`TessellationIsolineGenerator` combines the existing level preparation and
edge-coordinate units to generate one patch's isolines. Outer level 0 selects
1–64 equally spaced lines; outer level 1 and the requested spacing determine
1–64 segments per line. Line mode emits ordered endpoint pairs; point mode
emits each generated coordinate once. Coordinates are unsigned Q0.24, and the
third tessellation coordinate is zero for this domain. The generator retains
held output through backpressure and cancellation and reports the accepted
primitive count at completion. A discarded patch emits no primitives.

The bounded test checks line and point output against an independent rational
reference across spacing modes, fractional levels and line counts, as well as
64-line and 64-segment boundaries, invalid input, cancellation and reuse:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationIsolineGeneratorSpec'
```

This serial implementation uses the existing iterative edge-coordinate unit;
product throughput and physical timing are not qualified. Evaluation-shader
execution, triangle and quad domains, native submission and full H8 remain
open. The [OpenGL ES 3.2 specification §11.2.2](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf)
defines isoline subdivision and spacing.

## Isoline evaluation through the shared CU

`IsolineGeometryCuExecutor` adapts one tagged tessellation evaluation request
to the existing single-invocation geometry worker and shared-CU scheduler. The
internal shader reads the three binary32 tessellation coordinates as input
vertex 0, components 0–2; writes linked output components using the geometry
scalar instructions; and emits exactly one point. A missing point, failed
kernel, invalid task, or cancellation returns a tagged failed response. The
owner must reserve the command ID and retain shader resources until response
acceptance, and must continue draining accepted work during cancellation.

`ControlledIsolineSharedCuRenderer` can also snapshot up to 61 selected
control-output words for each evaluation. The task selects a vertex component,
per-patch component or tessellation level for each word; the shader reads them
as input vertex 0 components 3 onward. Reads use the resident patch store's
bounds checks, and an invalid selection fails the evaluation before CU launch.
External inspections and their held replies retain ownership while internal
reads wait. An accepted read drains after cancellation; patch contents and
shader resources remain owned through the evaluation response. The selection
is fixed per task, not dynamic shader indexing, and the separate
`IsolinePatchSharedCu` interface still supplies coordinates only.
The bounded checks cover all 61 word positions, rejected selections and
cancelled read drain. A real-CU shader copies per-patch and per-vertex values
into both line endpoints, with a held external inspection and post-cancel
reuse. The typed-image shared-system regression retains the zero-selection
path. These checks do not establish composed timing or product throughput.

This is a narrow internal instruction ABI, not a GLSL ES tessellation shader
implementation. Dynamic patch access, TCS/TES shader compilation and
binding, native submission, other tessellation domains and product throughput
remain open. The real-CU test is intentionally local-only; CI runs the bounded
scheduler/ownership check. Both commands below passed locally; the real-CU
shader copied three distinct coordinate words through the shared CU.

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.IsolineGeometryCuExecutorSpec'
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.IsolineGeometryCuQualificationSpec'
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.IsolinePatchInputReaderSpec opengpu.system.IsolineGeometryCuExecutorSpec'
sbt -J-Xmx5G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.ControlledIsolineSharedCuRendererAdmissionSpec opengpu.system.ControlledIsolineSharedCuRendererQualificationSpec opengpu.graphics.ControlledIsolineWindowBinTypedImageSharedSystemSpec'
```

## Controlled isoline patch evaluation

`TessellationIsolinePatchEvaluation` connects controlled patch generation to
serial evaluation requests. The patch remains resident until accepted evaluator
requests, held evaluated outputs, and accepted patch inspections and replies
have drained. Generated and evaluated results are provisional until the final
completion succeeds. The evaluator must answer accepted requests during
cancellation; the consumer must drain a held output.

The bounded test checks successful evaluation, reading patch data while an
executor response is outstanding, cancellation and drain, and evaluation
failure:

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationIsolinePatchEvaluationSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

The external evaluator still owns shader execution and resource binding. This
handoff does not provide a complete TCS/TES instruction ABI, triangle or quad
domains, native submission or product throughput qualification; H8 remains open.

## Control patch to isoline generation

`TessellationIsolinePatchPipeline` owns a control patch through its barrier
result, reads outer levels 0 and 1 from the completed patch, and passes them to
the isoline generator. The patch remains available for read-only output
inspection until the downstream evaluator releases it. Accepted inspection
requests and replies drain before close; primitives must be treated as
provisional until the patch completes successfully.

The control issuer must initialize the levels and all components that the
evaluator will read, issue each invocation's operations in program order and
consume opened, reply, barrier and control results. The evaluator must release
the patch only after its dependent work drains, including after cancellation or
a generation fault. Control-stage cancellation and invalid layouts report
failure after their accepted work drains.

The bounded tests cover level transfer, held output, retained patch inspection,
concurrent inspection and release, cancellation with held output, divergent
barriers, held completion and reuse:

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationIsolinePatchPipelineSpec opengpu.graphics.TessellationIsolinePatchCancellationSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

This is an internal handoff. It does not execute a control shader, supply a
complete tessellation-evaluation shader ABI, implement triangle or quad domains,
or establish native/API integration or product throughput. H8 remains open.

## Mapping evaluated isolines to renderer primitives

`TessellationIsolinePrimitiveMapper` maps an evaluated point or line's linked
TES output words to the existing `AssembledPrimitive` format. It copies clip
position and active varying components, applies flat values from the provoking
vertex, and optionally maps a point-size word. The caller supplies draw
identity, instance, primitive ID and a unique draw sequence. Invalid output
indices, point-size indices or varying masks produce a held fault instead of a
primitive. Outputs remain provisional until the enclosing patch reports
success; its owner must discard them on cancellation or failure.

The focused test checks line and point metadata, flat and non-flat varyings,
point size, held output and invalid linkage:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationIsolinePrimitiveMapperSpec'
```

This mapper does not run a shader, allocate unique draw IDs, implement native
submission, or prove TES-to-raster integration. Shared-CU evaluation, patch
access, remaining tessellation domains and product qualification remain open.

## Isoline renderer handoff

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

## Tessellation level preparation

`TessellationLevelPrepare` prepares FP32 outer and inner levels for the three
GLES 3.2 tessellation domains. It implements equal, fractional-even and
fractional-odd clamp and segment-count rules, the isoline first-outer-level
equal-spacing rule, relevant-outer zero/negative/NaN patch discard, and the
inner `1 + ε` promotion needed for nontrivial triangle and quad subdivision.
It retains clamped FP32 values and promotion flags for the later edge-coordinate
generator. Invalid domain or spacing encodings are reported, and a held result
blocks a new request. Inner NaNs are deterministically clamped to the minimum.

The implementation targets the GLES minimum maximum level of 64 and records the
minimum 32 patch vertices and 2,048 total control-output components. These are
limits for later patch and shader integration, not an API capability claim.
The [OpenGL ES 3.2 specification, §11.2.2 and Table 21.45](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf)
defines the level rules and minimum limits.

The compact CI test checks 900 directed and randomized FP32 cases across all
three domains and spacing modes, invalid encodings and output backpressure:

```sh
sbt 'testOnly opengpu.graphics.TessellationLevelPrepareSpec'
```

Fractional edge coordinates, primitive generation, patch storage, shader
execution and barriers, native API/compiler integration and H8 remain open.

## Native tessellation host integration (experimental)

The opt-in `tessellationNative` host connects GNQ1 tessellation jobs to the
native binned host's translated AXI memory path, shared shader pool and payload
ownership. It requires a native binned configuration. This is an internal
integration and is not a product capability claim.

A two-CU, two-engine, local-payload host passed malformed-command admission
and a 4x4 translated image using actual tessellation-control, isoline-evaluation
and fragment shaders. The image contained both expected shaded and clear
pixels. Reproduce the admission run with:

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=4 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  'testOnly opengpu.graphics.TessellationNativeHostAxiAdmissionSpec'
```

The host also passed local and spilled-payload compatibility cases that place
a real native binned image between two successful tessellation images, check
malformed commands and check payload release. Reproduce those cases with the same sbt options and
`testOnly opengpu.graphics.TessellationNativeHostLegacyCompatibilitySpec`.
Both payload modes also passed a translated descriptor-read fault, reset with
an accepted read outstanding, and two subsequent successful 4x4 image jobs.
Run those cases with the same options and
`testOnly opengpu.graphics.TessellationNativeHostAxiImageSpec`.

Visibility queries can span tessellation jobs and native binned jobs in this
profile. The host attributes samples and failures to the command's query
context and pinned translation root, and keeps begin/end boundaries behind
an active tessellation job. Local and spilled-payload host cases passed exact
query-record and completion checks for successful images, malformed jobs and
an end queued while a descriptor read was outstanding. Reproduce with the
same sbt options and
`testOnly opengpu.graphics.TessellationNativeVisibilityQueryHostAxiSpec`.

The opt-in host can route transform-feedback control and stores through the
same checkpoint-transfer owner used by native binned and tessellation jobs.
It snapshots each accepted command's feedback scope, drains both renderers
before moving all live objects, and reports a failed transfer in the normal
GNX1 or GNZ1 completion format. Local two-CU host tests run one live feedback
object through legacy, tessellation and legacy jobs, with exact captured words
and counters, in both local and spilled-payload modes. The same cases exercise
a feedback-store fault and checkpoint migration, reset while a write response
is outstanding, and a simultaneous fault/reset, followed by successful reuse
with exact captured data, counters and memory guards. Reproduce with the same
sbt options and
`testOnly opengpu.graphics.TessellationFeedbackHostAxiSpec -- -z "migrate live feedback checkpoints"`.
The large shared host suite remains local-only.

Both features can also run together in this opt-in host. Local and spilled
payload tests keep one feedback object live across a discarded binned job and a
tessellation job, checking empty and positive visibility results in separate
query scopes and exact feedback words, counters and guards. With both features
active, the same tests cover a feedback-store fault, reset with an outstanding
write and simultaneous fault/reset; each is followed by a successful query and
feedback capture with exact counters and guards. Reproduce with the same sbt
options and
`testOnly opengpu.graphics.TessellationFeedbackVisibilityHostAxiSpec -- -z "keep visibility and feedback live"`.
This large host suite remains local-only.

Primitive queries can count acknowledged feedback captures from both renderer
owners in the same scope. In local and spilled-payload tests, a live query
counts four source primitives across a binned job and a tessellation job while
a peer context remains zero. The tests also check scoped capture faults, reset
with an outstanding write, simultaneous fault/reset and successful reuse with
visibility queries enabled. Run the same sbt options with
`testOnly opengpu.graphics.TessellationPrimitiveFeedbackVisibilityHostAxiSpec -- -z "keep visibility and feedback live"`.
This two-CU suite remains local-only. Its query end is also queued while the
tessellation job is owned, and must complete after the job. Query boundaries
remain fenced from the acceptance cycle through feedback-owner admission; a
focused admission test checks that boundary, forces an owner-switch failure and
checks the accepted query context and root in the synthetic fault. After this
fence change, both the local and spilled combined-query host cases passed with
the command above. Reproduce the admission check with
`testOnly opengpu.system.TessellationFeedbackAdmissionSpec`. After rebasing
onto the GNZ1 ABI changes, this admission suite and
`testOnly opengpu.system.TessellationNativeCompletionInfoSpec` also pass
together.
The experimental GNZ1 completion snapshot records reader status, stage,
address and draw, nested image/bin statuses, work identity, and optional tile
and input-fault details. It uses the existing twelve-word completion window;
software must distinguish it from GNX1 by its magic. The legacy counters are
not a qualified tessellation diagnostic ABI. Translated
physical alias provenance remains the caller's responsibility. Broader host
query/feedback fault and reset coverage and product-profile validation are
required before
this integration can be advertised; the broader mobile GPU alignment goals
remain open.

GNZ1 uses magic `0x474e5a01` in word 0. Word 1 holds the job status in bits
2:0, reader stage in 4:3, image stage in 6:5, input-fault validity in bit 7,
and tile validity in bit 8. Words 2–4 hold the reader address, draw index and
valid tile. Word 5 packs input/image/bin status values in three-bit fields;
words 6–8 hold context, pass and generation. Words 9–10 hold input-fault address
and task ID when valid, and word 11 is reserved zero. These are recorded
snapshots, not a guarantee that every failure has an address.

#### Owned fragment texture chains at default capacity

An opt-in 4x4 quad test now executes C-prepared, nonidentity Sv32 snapshots
through the real host with two CUs, sixteen lanes and eight warps per CU,
native SRAM and FPU models, sixteen varying slots, four color attachments and
the 262,144-byte payload pool. It checks fragment texture mip chains and
owned uniform data across a successful job, a GXL allocation-bounds fault and
a fresh successful job on the same initialized host.

| Job | Observed host status | Service cycles | Result |
| --- | --- | --- | --- |
| Initial generation 9 | Success | 21,652 | Exact four RGBA8 images, depth, stencil and guards |
| Allocation-bounds fault | Input fault | 9,591 | No attachment writes; previous contents preserved |
| Fresh generation 10 | Success | 21,472 | Exact changed colors, depth, stencil and guards |

Both successful jobs check all 336 attachment bytes and 24,624 guard bytes.
The fault changes only the returned 33-bit GXL allocation limit; the backing
descriptor stays unchanged, AXI responses remain successful and rejected
texels receive no read. Each job checks completion identity, read-only inputs,
translated physical reads, memory drain and the final SATP-lock/IRQ sequence.
The first successful job establishes actual cold code reads. Later reuse
requires identical code, all nine VM pages, context/root/ASID and SATP/TLB
state; the fresh job still requires all 36 non-code physical read lines.
Missing external code reads alone do not establish an internal cache hit.

The selected native case passed, including its final assertions. Its input
preparation also passed 66 CPU rejection checks. Reproduce the CPU-only case
with:

```sh
sbt 'testOnly opengpu.graphics.TessellationNativeHostDomainFixtureSpec -- -z "prepare default-capacity owned texture snapshots without simulation"'
```

Run the native case manually, outside regular CI, with:

```sh
env -u VERILATOR_TEST_FLAGS sbt -J-Xmx12G -J-XX:ActiveProcessorCount=2 \
  -J-XX:+ExitOnOutOfMemoryError -Dopengpu.tessellationOwnedTexture=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simCcache=off \
  'testOnly opengpu.graphics.TessellationNativeQuadHostAxiImageSpec -- -z "execute owned fragment texture mip chains and recover a GXL bounds fault at default capacity"'
```

The final local run reused a previously compiled, input-qualified model;
earlier failed attempts remain failures. Porting to current main preserved all
3,476 compiled Main resources and the reached test classes; CPU preparation
reproduced all 50 C input/output files exactly. This is a bounded quad host
qualification. Full H8 feature/API coverage, other tessellation spacing modes,
geometry coverage and product frame-rate/physical-clock qualification remain
open.

## Draft tessellation job ABI

GNQ1 is a 512-byte little-endian job record with the same field locations and
capacity header as GNJ1, but distinct magic `0x474e5101`. Its frame reference
uses GNF1 and its draw array contains GNT1 records. The generated header and
schema describe the exact 14 fields, including the 33-bit exclusive limits;
unassigned words and unused high bits must be zero. The consuming job reader
also requires the GNT1 array bounds and stride to be 64-byte aligned.

The generated files are draft descriptions of an internal interface. They do
not advertise a host command or hardware capability, or establish a driver,
compiler, alias-provenance or product qualification contract. The generated
header and schema match the hardware layout; the C11 compile and CI selection
checks pass.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 \
  'runMain opengpu.elaboration.EmitTessellationNativeJobAbi --check' \
  'testOnly opengpu.system.TessellationNativeJobAbiSpec'
cc -std=c11 -Wall -Wextra -Werror -fsyntax-only driver/tests/opengpu_tessellation_job_abi_test.c
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

## Native tessellation job-to-image owner

`TessellationNativeJobImage` connects the GNQ1 job/GNF1 frame reader, GNT1
draw snapshots, sequenced tessellation image owner and translated descriptor
fabrics. A single physical port carries Sv32-translated job, draw, input and
image traffic. Its completion waits for the job, child frame and fabric to
drain; cancellation is delayed across held child offers.

This is an internal job interface. The caller must retain `satp`, mappings,
code, state and all allocations, and establish physical alias provenance until
completion. The GNQ1 job format is a draft; host MMIO/AXI integration, public
capability discovery, driver/compiler support, other tessellation domains,
full-size workloads and physical timing qualification remain open.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationNativeJobImageAdmissionSpec opengpu.graphics.TessellationNativeJobImageSharedSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

## Translated tessellation job memory

`TessellationNativeJobMemoryFabric` shares a physical memory port between the
already-translated image owner and the four-tag GNQ1/GNF1 job reader. Image IDs
are unchanged; four disjoint IDs carry translated job data and one carries its
Sv32 page walker. Both clients retain their IDs through held requests and
responses, including translation faults. The caller retains `satp`, mappings,
and physical allocations until the clients and fabric drain.

This component does not yet connect the complete job reader, image pipeline,
shared CUs and host command interface into a single native submission path.
Physical alias provenance remains the caller's responsibility.

`TessellationHostMemoryFabric` provides the next physical-memory boundary: it
merges the existing graphics-host L2 client and an already-translated
tessellation client into disjoint ID ranges. It preserves request fields and
returns each response to its owner, including under backpressure; an ID cannot
be reused before the client consumes its reply. Per-client drain indications
allow one completed owner to retire while the other remains active; the global
drain also covers queued requests. This bridge is an internal component: host
command admission, actual host wiring, reset/VM ownership and physical alias
provenance still need integration and end-to-end qualification.

Focused simulations cover same-numbered IDs from both clients, out-of-order
responses, held requests and fault replies, ID reuse, single-ID clients, an
asymmetric range requiring an ID carry bit, and independent client drains.
All three focused bridge tests pass.
No timing or product-capacity qualification is implied.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationHostMemoryFabricSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

Three tests pass: concurrent image and four-tag job traffic with successful and
faulting translations, held request/response ownership, and an actual job
reader fetching a complete job and frame through nonidentity Sv32 mapping.
The CI test-selection checks also pass.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationNativeJobMemoryFabricSpec'
python3 scripts/test_test_selection.py
```

## Tessellation native job reader

`TessellationNativeJobReader` snapshots a draft GNQ1 job and a GNF1 frame via
four independently owned descriptor tags, then offers a bounded sequence of GNT1
views to `TessellationNativeSequenceImage`. GNQ1 has the same fixed word layout
and capacity header as GNJ1, but uses distinct magic `0x474e5101`; the draw
array contains GNT1 records rather than GND1 vertex draws. The job's frame and
draw ranges are exclusive-ended, and nonempty draw arrays require aligned
bounds and strides of at least 2048 bytes.

The owner rejects identity mismatches and writable virtual frame spans that
overlap job, frame, draw or active image storage before offering the frame. It
also prevents writable image spans from overlapping descriptors or fragment
state. Offers, outstanding reads, child results and frame completion remain
owned across cancellation and faults. The caller must pin address translation
and enforce physical allocation provenance: virtual overlap checks cannot
prove that two different mappings do not alias. Draw input and shader resource
aliasing, an attached translated job-memory fabric, host command submission,
other tessellation domains and product qualification remain open.

Two directed simulation tests pass: reordered descriptor replies and bounded
multi-draw views; malformed headers, bounds and virtual aliases; held offers,
cancellation, descriptor and child faults, terminal-frame flushing and reuse.
The changed-test selection checks also pass.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationNativeJobReaderSpec'
python3 scripts/test_changed_test_suites.py
```

## Native tessellation record memory routing

`TessellationNativeRecordMemoryFabric` merges the translated image owner's
physical line port with a separate Sv32 client for native draw-record reads.
Image transaction IDs retain their original range; record data and the record
page walker use two distinct IDs above it. A buffered round-robin request
arbiter preserves held offers. Image IDs and the record request stay owned until
their responses are consumed, including held responses and page faults.

The enclosing owner keeps `satp`, page tables and allocations stable until the
frame, record reader and this fabric drain. Record requests are read-only and
use local ID zero. The image input is already translated; this fabric does not
translate it again. The reader and frame owner still require descriptor and
writable-storage alias validation, reset integration, and native host ownership.

The bounded tests exercise concurrent physical image and non-identity translated
record requests, independent IDs, held requests and both held response ports,
page faults and drain. A composition test connects `TessellationNativeSequenceReader` and
fetches all 32 lines of a draw record through Sv32 while an image request shares
the physical port. This is not a complete native job or driver submission.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationNativeRecordMemoryFabricSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

## Native sequenced tessellation image integration

`TessellationNativeSequenceImage` connects the draft GNT1 draw-record reader to
an Sv32-translated sequenced image frame. The accepted frame supplies the
identity checked against each record; the submitter supplies the command tag and
record view. Record reads, image traffic and patch inputs share one physical
memory port. A held draw survives cancellation, and frame completion waits for
both the reader and memory fabric to drain.

This is a trusted internal interface. Its caller must pin mappings and resources
and verify physical aliasing until completion. It does not implement the native
job ABI, driver submission, other tessellation domains, or product-size and
physical-timing qualification.

Validation uses a bounded admission check and a local 4×4, two-CU shared
simulation covering translated descriptor and input reads, shaded output,
backpressure, cancellation, descriptor and input faults, and reuse:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationNativeSequenceImageAdmissionSpec opengpu.graphics.TessellationNativeSequenceImageSharedSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

## Tessellation native draw-record reader

`TessellationNativeSequenceReader` reads a fixed GNT1 draw snapshot from a
bounded GPU-memory view and offers it to a sequenced tessellation frame owner.
The draft record has 512 little-endian words (2,048 bytes), a magic, size and
version in words 0–2, and 466 named fields with fixed maximum capacities of
16 lanes, eight warps, 16 varying slots and a 12-bit task tag. The generated
[C header](../driver/opengpu_native_tessellation_sequence_abi.h) and
[JSON schema](abi/native-tessellation-sequence-v1.json) specify the offsets.
All reserved words and unsigned padding must be zero; signed padding is sign
extended. The encoded task tag must be zero: the enclosing command owner
supplies the retained tag and frame identity separately.

The reader rejects malformed encodings, wrong frame identity, unsupported
nonzero fields and values that would be truncated by the configured lanes,
warps, varyings or tag width. Helper masks retain the native 16-lane-per-warp
layout in the record and are repacked per warp for smaller CUs; bits outside
the configured lanes or warps are rejected. It checks the complete aligned record view and
allows an exclusive end at 4 GiB. Each of 32 uncached 64-byte reads is retired
before the next; memory faults identify the line address. Offered reads and
draws remain held through cancellation. A terminal `frameStopped` signal may
flush an unaccepted draw; an offered memory read still drains. The completion
marks terminal-frame flush separately from descriptor rejection and identifies
whether a child result is present. Accepted child
work must complete before the held reader completion can be consumed. `readBytes` counts the
64-byte responses retired, including a fault response. Child semantic and
resource validation remains with the sequence and frame owners.

The enclosing command owner must reserve descriptor-memory transaction ID zero,
pin the descriptor, mappings and resources,
check descriptor aliases against writable storage, retain the frame and tag,
forward `childCancel`, assert `frameStopped` only when the frame can no longer
accept a draw, and drain the frame independently. This is a draft
record reader, not a complete native job reader, host/MMIO submission, driver
or compiler interface. It does not claim other tessellation domains, product
throughput or physical timing.

The local tests cover a complete snapshot, final record at 4 GiB, bad bounds,
reserved and narrow padding, frame identity and tag violations, terminal-frame
flush, capacity
truncation, memory faults, child failure, cancellation with held offers,
completion backpressure and reuse. A composition test reads the actual record
and drives two tasks through `TessellationPatchSequence`, checking their input
addresses, task IDs, assigned tag and primitive numbering. The schema test checks generated outputs
byte-for-byte; a C11 probe checks record size, magic and selected offsets.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationNativeSequenceReaderSpec opengpu.system.TessellationNativeSequenceAbiSpec'
cc -std=c11 -Wall -Wextra -Werror driver/tests/opengpu_tessellation_sequence_abi_test.c -o /tmp/opengpu_tessellation_sequence_abi_test
/tmp/opengpu_tessellation_sequence_abi_test
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

## Native tessellation command transport

The opt-in three-domain native host reports its internal GNQ1/GNQ2/GNQ3
command transport in GCR1 bit 11. Bit 12 additionally reports transform
feedback for that transport. The bits require the protected native host and
are clear on the default product profile. They do not advertise a userspace
submission interface or GLES conformance. The driver rejects feedback without
the command bit and rejects either advertised transport on an unprotected or
non-unified host. When the command bit is present, it also validates the GNC
native-host record and records its actual engine count. All current GNQ1,
GNQ2 and GNQ3 image views use the context VM. The shared GNC1/GNC4 physical
image setting applies to GNJ jobs; it does not change tessellation image
translation. An absent command bit does not probe the optional record.

`GpuHostSystemAxi` publishes the GNJ image mode from
`NativeBinnedGraphicsConfig.translateImageViews`. Its three-domain GNQ owner
always routes renderer requests through `TessellationInputMemoryFabric` and
the `TileBinHostMemoryFabric` Sv32 render client. The driver therefore checks
GNQ image allocations against binding VAs for all four GNC profiles while
retaining the shared record and engine-count validation.

`opengpu_hw_tessellation_async` requires driver-owned GNQ, GNF1 and, for a
nonempty job, GNT draw-array snapshots. It can also verify a supplied
driver-owned fragment-state arena. It checks the job and frame headers,
identities and descriptor bounds, and rejects overlap of descriptor VAs with
the arena, pool, directory and active-engine scratch ranges. Every frame and
draw word must have the canonical padding defined by the checked hardware ABI
schemas; each draw also passes the patch-input arithmetic
precheck. The driver uses the discovered engine count for the scratch-alias
check. Private VMs can map snapshots with read-only, non-executable leaves;
the mapping operation refuses to replace an existing leaf and uses the normal
TLB-shootdown and rollback path. Before submission, the driver checks that
every descriptor VA resolves to the corresponding DMA page and scans the same
VM for writable mappings of those DMA pages. The check includes the full
strided draw-array span and, when supplied, the exact frame state range. It
fails closed on unowned page tables. Bare/ASID-0
mode only checks address identity and is suitable solely for trusted callers.

The caller must still validate remaining frame, draw and resource semantics
and permissions, prevent CPU writes and physical or IOMMU aliases (including
different DMA addresses for the same backing), and retain immutable snapshots,
backing allocations, VM mappings and context until the returned fence retires.
It must also protect page tables from CPU or GPU writes and reject aliases in
other contexts. Translated image backing still needs physical-alias protection.
These checks do not establish ownership of arbitrary GEM resources. No
userspace ioctl calls this internal transport yet.

`opengpu_tessellation_sched_submit` connects that transport to the DRM
scheduler for a trusted kernel producer. It copies GNQ, GNF, the complete
strided GNT span and any nonempty fragment-state arena into job-owned DMA
buffers, replaces their addresses with separate private VM windows, and
rejects malformed or wrong-context snapshots before enqueue. At execution it
maps those buffers read-only, verifies their exact backing, submits the command and keeps the
mappings, snapshots, context and declared GEM dependencies until retirement.
The producer supplies stable kernel bytes for the full state range; a missing
or short source is rejected. An empty state range needs no copy. The private
state window supports up to 256 MiB, subject to backing memory and available
per-context page tables; its exclusive limit may be 2^32. Empty jobs need no
draw mapping.
For each nonempty draw that reads patch inputs, the scheduler also requires
the full 64-byte-aligned input allocation to lie within one mapped vertex
buffer binding in that context. It checks the copied draw snapshot and requires
the binding's GEM object as a dependency that waits for prior writes and
publishes at least a read fence. The context binding cannot be replaced or
unbound until the job's fence completes. Empty sequences and patches with no
input components need no input binding.

The scheduler also checks nonempty bin arena, pool, directory and per-engine
scratch ranges, and active image-view and resolve allocations from
the copied GNF frame. Each complete 64-byte bus-line range must fit a mapped
compute-storage binding in that context. Image allocations and the other
ranges use context virtual addresses. Read-only ranges require a dependency
that waits for prior writes and publishes at least a read fence. Writable
ranges require a dependency that waits for prior reads and writes and
publishes a write fence.
The same checks require patch inputs and frame resources to use locally
allocated GEM objects; imported DMA-BUF backing is currently rejected because
its physical aliases cannot be established here. The scheduler conservatively
rejects overlap whenever either checked range can be written, including GEM
offset and DMA-address aliases, and rejects overlap with descriptor snapshots.
Some otherwise valid sharing is therefore not accepted yet.

When a state table is supplied, each nonempty, non-discard draw's referenced
GFS1 entry is checked in the copied snapshot before enqueue. Its generated
canonical word encoding, header, handle, context/pass/generation, engine count
and per-engine scratch views must match the frame's bounded per-state slots.
All table slots must fit the active scratch arenas, including an exclusive
limit at 4 GiB. Unreferenced state entries may remain uninitialized. A nonempty
draw without a copied table is rejected before enqueue, including a draw with
no patch-input components. This matches the image owner's required-state
admission before the scheduler publishes work or a completion fence.
Zero-count sequences do not reference a state, and raster-discard jobs retain
their existing trusted-producer contract.

Each referenced GFS1 state's texture and uniform root pointers are also checked,
including draws with no patch-input components. Version zero requires address
zero. Version one requires a complete, 64-byte-aligned GXR1 root of 128 bytes or
GUR1 root of 64 bytes; an exclusive end at 2^32 is accepted. These are context
VAs and must fit one locally allocated, mapped compute-storage binding with a
dependency that waits for prior writes and publishes at least a read fence.
Kernarg and other binding placements are not admitted by this path. The
canonical generator derives the field positions and root sizes from the checked
ABI schemas without including userspace-only headers in the kernel.

The root references reject DMA overlap with the copied descriptor buffers and
DMA or same-GEM offset overlap with writable frame resources. Shared read-only
roots and read-only patch/image aliases are allowed. The existing context fence
keeps the bindings stable until retirement, and the dependency retains the GEM
backing. This checks the root envelope and dependency; it does not copy or
decode root bytes, protect them from arbitrary shader or CPU writes, validate
their indirect tables, or qualify the underlying resource data or code. Storage
PTE permissions are unchanged. Unreferenced states, zero-count sequences and
discard-only jobs do not add root dependencies.

The internal `opengpu_tessellation_uniform_sched_submit` entry additionally
constructs immutable fragment-uniform metadata from stable kernel plans in
[`opengpu_tessellation_uniform.h`](../driver/opengpu_tessellation_uniform.h).
For every referenced version-one GFS uniform state, the sorted, unique-handle
plan supplies twelve active block mappings, twenty-four binding views and an
optional default view. A view identifies a one-based context binding slot,
offset and logical byte count. Caller GUR/GUB pointers are not followed.
Version-zero/address-zero, unreferenced, zero-count and discard contracts remain
unchanged. The original trusted entry retains its existing producer contract.

The driver generates each complete GUR, active twenty-four-entry GUB table and
default descriptor after the 512-byte GNQ record in its private job allocation,
then rewrites the copied GFS root pointer. Unused GUB entries are zero. Identity,
fragment stage, read-only flags, alignment, padding and 33-bit exclusive limits
come from the checked job and typed views. Each state requires 64–1664 bytes;
the complete allocation and DMA offset must fit the existing 4 MiB command VA
window. GNQ's record limit and submitted `BYTES` remain 512. Mapping,
read-only/non-executable verification, writable-alias rejection and publication
cover the entire allocation, including metadata tail pages.

Every reachable block/default data view must fit a local, nonimported mapped
compute-storage binding and retain its object read dependency. Block views have
256-byte alignment and at most 16 KiB; default views have four-byte alignment
and at most 4096 bytes. The complete rounded 64-byte read footprint is checked
against backing, actual uncached Sv32 leaves and physical/GEM aliases of
writable frame resources, snapshots and known shader allocations. A readable
leaf must already have its accessed bit set; read-only data does not require
the dirty bit. Read-only views may share backing and one object dependency.
Invalid plans, backing or dependencies fail before enqueue without publishing
a fence. A later mapping/alias failure returns through normal scheduler
retirement after the fence exists.

This entry owns the full reachable uniform **metadata** chain and checks its
retained data views. It does not admit arbitrary code: the tessellation input
does not identify every executable backing extent, so anonymous or otherwise
unlisted code aliases remain a trusted-producer obligation. Ordinary storage
PTEs are unchanged and may be writable/executable. For this uniform-only entry,
texture tables/data, other
GFS semantics, CPU/other-context writers, resource publication and full native
Linux/API/compiler admission retain their stated requirements.

The combined internal `opengpu_tessellation_resources_sched_submit` entry in
[`opengpu_tessellation_texture.h`](../driver/opengpu_tessellation_texture.h)
also requires [job-owned fragment executable plans](#owned-fragment-code-for-native-tessellation)
for every draw-referenced state. It copies code, checks the expected compilation
VA, rewrites copied GFS entry PCs and maps code read/execute without write
permission. The transport verifies backing and permissions under the submission
lock before invalidation and command publication. There is no caller-code
fallback in this entry; control/evaluation code and instruction legality
remain trusted-producer obligations.

The entry owns the complete fragment texture metadata chain. Each draw-referenced
version-one texture state requires a stable, sorted, unique-handle kernel plan.
The driver generates GXR1 → GXB1 → GXI1/GXS1 → GXL1 records alongside the GUR/GUB
metadata in one allocation and rewrites both copied GFS pointers. No caller
metadata address is followed. Identity, fragment stage, handles, table addresses
and reserved bytes are generated; shapes, formats, sampler state, pitches and
relative level offsets come from the typed plan.

`binding_count` is the addressable unit extent, 1–32, rather than a popcount:
unit *i* retains slot *i*, so sparse unit31 requires extent32. Every unit reserves
one binding, image and sampler record plus all sixteen level slots; absent units
and levels remain zero. Texture metadata needs `128 + 2304 * binding_count`
bytes per referenced state, at most 73856 bytes, within the same 4 MiB command
window. Every present level must obey the canonical target/format/dimension,
pitch, range and overlap rules. A mipmapped minification filter requires the
complete selectable mip chain. Selected opcode/sampler legality is still
checked by hardware; the kernel does not infer arbitrary shader instructions.

Every declared present level, including its final row, slice, array layer or
cube face and rounded 64-byte read footprint, must fit retained local storage
with a read dependency and actual readable private uncached Sv32 leaves. The
same DMA/same-GEM frame and known-code alias checks, and snapshot DMA checks,
apply. The whole
metadata allocation is mapped read-only/non-executable and checked before MMIO,
including tail pages. Invalid plans, data mappings or dependencies reject
before fence publication; later alias failures retire through the existing
scheduler path. Version-zero/address-zero, unused states, zero-count and
discard behavior stay unchanged, as do the old trusted and uniform-only
interfaces. This closes ownership of the declared fragment texture metadata
and retained data views, not arbitrary code/coordinate-packet provenance,
CPU or other-context writers, cached publication, other shader stages or the
remaining Linux/API/compiler admission requirements.

These checks do not validate all GFS1 semantics, shader code or records
referenced by other bin data, and do not establish complete resource ownership
or isolation. The trusted producer still has to make input data visible to the
GPU and prevent CPU writes or aliases through other contexts and IOMMU mappings. It must also
validate the full frame and draw semantics and all indirect resources, such as
shader state and code and feedback buffers, including their physical aliases.
The producer must hold the context and dependency reservation locks through
enqueue, supply stable kernel snapshots, and validate, map and synchronize all
referenced resources. The scheduler does not infer the entire resource set from
raw descriptors. There is no userspace entry point until those obligations can
be enforced.
Draw arrays have a separate private VA region below the kernarg windows;
allocation and the available per-context page tables still bound their size.

If temporary VM revocation fails during retirement, the scheduler wedges the
device and retains the job, context and all dependency references instead of
freeing backing that the device might still reach. That failed job is retained
for the life of the kernel; automatic recovery and reclamation are not yet
implemented.

Under the submission lock, the driver checks the execution owner and waits for
idle before reading the snapshots. It then verifies their mappings, orders CPU
writes, invalidates the GNQ, frame, nonempty draw array and other supplied
snapshots, selects the VM and publishes the unified command. The command uses
these fields:

| Register | Tessellation meaning |
|---|---|
| `SOURCE` | 64-byte-aligned GNQ virtual address |
| `DESTINATION`, `PATTERN[0]` | Low 32 bits and bit 32 of its exclusive limit |
| `BYTES` | 512 |
| `WIDTH` | Context decoded from the GNQ snapshot |
| `HEIGHT`, `SOURCE_STRIDE` | Feedback program and cookie |
| `FLAGS` | Optional raster discard and feedback capture only |

Wait/signal events are rejected for this command. A limit at 2^32 is encoded
without truncation; the currently integrated host reports zero completed bytes
for tessellation. Feedback flags and scope values are rejected when that
transport is absent. Failure before publication returns no fence and does not
ring the tessellation doorbell; prior cache invalidation commands may have run.

The bounded AXI tests cover discovery with and without feedback, read-only
flags, protected ownership, the 2^32 limit, query context and feedback scope.
The C tests exercise every generated canonical word rule and the production
command writer for all three GNQ formats, two strided draw snapshots, invalid
frame/draw encodings, all four GNC profiles with one through four engines and
virtual GNQ image views, unchanged outputs after failed discovery, ordering, the
same boundary, unavailable features and invalidation/busy/idle failures. MMU
tests cover snapshot permissions, exact backing, writable aliases, existing
mappings, rollback and page boundaries. A small RTL page-walker test checks
that the emitted leaf permits reads and faults stores and instruction fetches.
The scheduler integration test runs the production scheduler, MMU and transport
against a fake command device, including copied records, context/dependency
retention, window conflicts, writable aliases, abort and failed revocation. It
checks active inputs in two strided draws for each domain, including missing
dependencies, incorrect fence usages, wrong binding types and short ranges.
It checks frame ranges and all six image and resolve allocations in both image
addressing modes, with missing fences, imported backing, short bindings and
physical or object-offset aliases rejected. It checks state-copy lifetime,
read-only mappings, writable aliases, short sources and the 2^32 limit. The
GFS1 tests reject absent tables, bad headers, padding, identities, handles and
scratch slots, including a second strided draw; unused table entries are left
untouched. Missing-table checks cover all three domains and both strided draw
positions, verify no enqueue, MMIO or leaked references, and reuse the context
with zero-count work. Source-only discard jobs and copied-state jobs retain
their existing submission and retirement checks.
The rooted C admission witnesses cover both resource kinds in both strided draw
positions for all three domains. They reject missing or unsuitable dependencies,
unmapped binding metadata, wrong binding types, imported backing, short ranges,
invalid pointer versions, address overflow and writable DMA/GEM aliases before
enqueue, fence publication or MMIO, with no leaked references. Healthy reuse,
shared read-only roots, patch/image aliases and complete admission at the 2^32
boundary pass. These witnesses use constructed GFS snapshots and a fake command
device; they do not execute shader resource instructions or inspect root bytes.
The fake scheduler checks its completion-fence pool bounds, prevents reuse of
referenced slots and releases the final context fence at teardown.
The typed-uniform cases construct all three domains with active and default
views, wipe the input plans after enqueue and inspect generated records,
retained references, reservation usages and full-tail mappings. They reject
invalid plans, missing dependencies, short/imported/wrong-type backing, bad
real PTEs (including an unset accessed bit), frame/code/snapshot DMA or GEM
aliases and a writable alias of the second metadata page. Allocation failures,
healthy reuse, default-only and shared read-only views, unused states and the
exclusive 2^32 boundary are covered. Run:

```sh
python3 scripts/test_tessellation_schedule.py
CC='cc -fsanitize=address,undefined -fno-omit-frame-pointer' python3 scripts/test_tessellation_schedule.py
```

The sanitizer command runs on macOS and Linux.
The typed-texture cases use the same real scheduler/MMU harness for all three
domains, wipe caller plans after enqueue, and inspect all 32 bindings and
sixteen level slots. They cover sparse unit31/level15, complete unordered mip
chains and bad later-page PTEs, array/cube/3D/ETC2 footprints, the exclusive
2^32 limit, dependency/backing/alias failures, every scratch allocation failure,
tail-page alias retirement and healthy reuse. `--owned-texture` runs this
bounded subset alone. These are C admission/lifetime tests, not shader execution.
Records generated by the existing three-domain host fixtures, including their
GFS1 states, pass with patch inputs, frame scratch and image views relocated to
mapped driver VAs. Their original variants disable texture and uniform roots.
The test-only [rooted fixture adapter](../scripts/tessellation_rooted_host_fixture.py)
also builds GXR1/GUR1 records from the checked ABI layouts and replaces the
fragment shader with explicit texture-to-red and uniform-to-green instructions.
The existing domain-fixture suite passes these variants through the production
C scheduler, private MMU and tessellation transport. It exports the queued
copies after wiping the caller's record buffers, while the context and three
GEM dependencies remain retained. The export includes backing bytes, actual
Sv32 root/leaf tables, both writes from the real SATP helper, real ASID-shootdown
words, observed snapshot-invalidation spans and unified-submit call arguments.
Command register staging and completion fences remain mocked at that boundary.
An independent serialized page-table walk checks nonidentity data and executable
mappings, read-only/non-executable snapshots and exact root/shader bytes.
The copied snapshots keep the production scheduler's uncached leaf policy.
The generated exports match byte-for-byte on macOS and Linux with ASan/UBSan.
These bridge checks do not execute a GPU shader.
The typed export also follows GFS → relocated owned GUR → complete GUB table
and default descriptor → data through an independent serialized Sv32 walk.
Its code, patch and resource-data values match the trusted-entry exports.
The combined typed export additionally follows the copied GFS through the
generated GXR/GXB/GXI/GXS/GXL chain and independently walks its complete metadata
and texel footprints. It preserves those same code, patch and data values.
A kernel-safe word alias preserves the generated texture ABI layout; the
existing kernel ABI suite checks its sizes/types and generator consistency.
The complete driver also builds against prepared AArch64 Linux 7.2 headers:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 'runMain opengpu.elaboration.EmitNativeTextureAbi --check'
### Copy driver/*.c, driver/*.h and driver/Makefile to an independent module-stage.
gmake -C /path/to/prepared/linux-build M=/absolute/module-stage ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- -j1 gpu_drv.ko
```

A pure canonical-layout quad serializer is checked against all seven original
Echo-generated input SHA-256s, including every field and padding byte. It
requires no Echo or GPU model; the default fixture tests retain their Echo
comparison:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 'testOnly opengpu.graphics.TessellationNativeHostDomainFixtureSpec -- -z "without simulation"'
```

The rooted fixture explicitly maps its single 4 KiB resource page uncached:
VA `0xd8000000` maps to PA `0x35000000`, including roots, tables, coordinate
packet, one R32F texel and one uniform word. Code uses a separate uncached
nonidentity executable mapping. Reusing these physical resource addresses does
not qualify publication of cached resources. The host adapter separately
publishes copied snapshots and CPU-replaced frame/patch backing, draining any
old backing before switching byte images. On each fragment draw the existing
stage invalidates typed image/level/sampler descriptor caches; the uniform unit
keeps no descriptor or data cache, and resource line reads are uncached.
General indirect-resource ownership, cached-resource publication, arbitrary
executable admission and product-capacity qualification remain outside this
fixture's scope.

The [composed native host test](../src/test/scala/opengpu/graphics/TessellationNativeQuadHostAxiImageSpec.scala)
passes those C-prepared byte images and captured VM/command arguments through
one actual GNC2 host. Quad, isoline and triangle shaders execute both resource
instructions under nonidentity instruction/data Sv32 mappings. All 16 RGBA
pixels, including clear pixels, match; isoline coverage is independently derived
from the perturbed-diamond line rule. Clear RGBA8 is `(26, 51, 77, 102)`;
quad, isoline and triangle cover four, two and three pixels respectively.
Texture and uniform values independently control red and green:

| Execution | Texture / uniform | Covered-pixel RGBA8 |
|---|---|---|
| Quad | 0.5 / 0.25 | 128, 64, 0, 255 |
| Isoline | 0.75 / 0.25 | 191, 64, 0, 255 |
| Triangle | 0.75 / 0.5 | 191, 128, 0, 255 |
| Quad after the resource fault | 0.25 / 0.75 | 64, 191, 0, 255 |

Required descriptor, code, page-table, root, table, texel and uniform physical
reads are observed; resource/code VAs must not escape translation. A real AXI
error on the GUR1 root read after a texture-data fetch completes with status 3
and GNZ1 low-nine-bit flags `0x17b`. Both SATP updates remain rejected until
completion is consumed, then the fresh-value quad passes the same full-pixel
and physical-read checks. Descriptor-read fault and outstanding-read reset
checks also pass in that session.

The owned-uniform mode (`-Dopengpu.tessellationOwnedUniform=1`) passes three
quad jobs on the same host: healthy, a GUR read fault, then fresh-value reuse.
The copied GFS points to the generated GUR at job VA + 512 and its private GUB
table. All 16 pixels match: four covered pixels are `(128, 64, 0, 255)` before
the fault and `(64, 191, 0, 255)` after it; twelve clear pixels remain exact.
Required root, table, data, code and descriptor physical reads are observed
without VA escape. The relocated GUR fault returns status 3 / flags `0x17b`,
and both SATP changes remain rejected until completion is consumed. The default
view is admitted and independently walked, but this shader does not load it.

These host executions use the frozen hardware at `63f2cf77`, before the
returned-credit Stage change `44712d2d`. Current-driver C exports reproduce both
owned-uniform byte images exactly, and the current driver builds as a real
AArch64 Linux 7.2 module. This does not qualify the changed current-main Stage
through the host model. The default composed mode retains the three-domain
and reset sequence above; the owned-uniform mode uses the byte-checked pure
quad serializer without an Echo model.

This local profile is 4x4, two CUs with four lanes/two warps each, two render
engines with behavioral 32 KiB tile SRAM each, a 4 KiB local payload pool and
256-bit AXI. Payloads are at most 1 KiB, with three owners/six ports. It uses
two vertex attributes/bindings/varyings, four color slots, a four-sample cap,
128-byte bin pages, two write credits, one tile slot, eight paged records,
4-set/1-way instruction/vector caches, 16 KiB shared memory per CU and an
8-set/2-way/4-bank L2 (1 KiB data across the banks). Performance
counters, the FPU backend, fragment images and compute-resource flags are off.
It qualifies these bounded domains and uncached resources, not GNC1/GNC4 host
execution, general tessellation conformance, product capacities or complete H8.
The C command/fence boundary described above remains mocked; this is not a
running Linux kernel/UAPI qualification. Keep the composed suite local-only.

The resource helper retains its separate synthetic physical/virtual range
checks. The existing [4×4 native host test](../src/test/scala/opengpu/graphics/TessellationNativeHostAxiImageSpec.scala) checks the translated image
mapping from VA `0x600000` to PA `0xa00000` in the default GNC2 profile; it does
not qualify GNC1/GNC4 whole-host execution.
Separate fragment-stage RTL component cases pass exact-word uniform loads and
all 32 texture binding selections, including resources at the exclusive address
limit. The address-translator component also passes a nonidentity read and a
cached read-only permission fault. These exercise resource instructions and
translation independently, without combining them with tessellation submission.
Actual generated host snapshots for all three domains pass the combined
precheck. The Linux 7.2 guest driver also builds. Run:

The host driver suite runs the C job validator and the kernel-header ABI syntax
check directly. Changes limited to those C validator files therefore do not
compile Scala just to repeat the same checks; generated ABI changes retain
their existing Scala selection.

```sh
python3 scripts/test_tessellation_canonical.py
python3 scripts/test_tessellation_transport_submit.py
python3 scripts/test_tessellation_schedule.py
CC='cc -fsanitize=address,undefined -fno-omit-frame-pointer' \
  python3 scripts/test_tessellation_schedule.py
python3 scripts/test_driver.py
bash scripts/test_scripts.sh
sbt -J-Xmx5G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=2 \
  -Dopengpu.simLowMem=1 -Dopengpu.productQualification=0 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.core.memory.Sv32PageTableWalkerSpec -- -z "read-only non-executable snapshot"' \
  'testOnly opengpu.core.memory.GraphicsAddressTranslatorSpec -- -z "translate a nonidentity"' \
  'testOnly opengpu.core.memory.VectorTlbSpec -- -z "reject a store without write"'
sbt 'testOnly opengpu.graphics.TessellationNativeHostDomainFixtureSpec -- -z "decode actual"'
H8_EVIDENCE="$PWD/.artifacts/rooted-tessellation-host"
sbt -J-Xmx6G -J-XX:ActiveProcessorCount=2 -J-XX:+ExitOnOutOfMemoryError \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simCcache=off \
  -Dopengpu.simCache="$H8_EVIDENCE/models" \
  -Dopengpu.rootedHostEvidence="$H8_EVIDENCE/runs" \
  -Dopengpu.tessellationOwnedUniform=1 \
  -Dopengpu.productQualification=0 -batch \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationNativeQuadHostAxiImageSpec'
sbt 'testOnly opengpu.system.TessellationNativeJobValidateSpec opengpu.system.TessellationNativeRecordKernelAbiSpec'
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.productQualification=0 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.ShaderUniformExecuteUnitSpec -- -z "retire exact words and coalesce identical active operands stage=1 lanes=4"' \
  'testOnly opengpu.graphics.NativeShaderTextureUnitSpec -- -z "fetch all 32 resident bindings at the exclusive address limit stage=1"' \
  'testOnly opengpu.core.memory.GraphicsAddressTranslatorSpec -- -z "translate a nonidentity page and enforce cached write permission"'
sbt -J-Xmx5G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=2 \
  -Dopengpu.simLowMem=1 -Dopengpu.productQualification=0 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.NativeWideTileGridEncodingSpec opengpu.system.TessellationHostAxiCommandSpec opengpu.system.ComputeResourceCapabilitiesAxiSpec'
```

With the ARTI Linux build paths configured, reproduce the guest driver build
with:

```sh
ARTI_DIR=/path/to/arti ARTI_WORK=/path/to/arti-work \
  LINUX_BUILD=/path/to/arti-work/arti-linux-build \
  bash scripts/build_arti_guest_driver.sh /tmp/opengpu-tessellation-driver
```

Fresh default 720p workload and whole-host emissions match the `7617dcf4`
qualification baseline after normalizing source-location comments. The six
workload fixtures and their manifest match byte for byte. Reproduction commands
are in the [qualification notes](#native-fragment-image-coexistence).

These checks do not establish userspace submission, full resource validation,
cross-domain ordering, product timing, or H8 completion.

## Tessellation patch context

`TessellationPatchContext` owns a patch from input loading through control
invocation completion and output inspection. It connects the bounded patch
store to the invocation barrier and waits for accepted accesses and their replies
to drain before accepting a barrier arrival or invocation completion. The issuer
must submit each invocation's requests in program order.

During control execution, input is read-only, per-vertex output writes are
limited to the issuing invocation's vertex, and per-patch output writes are
shared. An invocation blocked at a barrier cannot access patch data until its
release. Invalid operations return a checked error. On cancellation, the
issuer must stop issuing new work; already offered loading and control accesses
are accepted as checked errors, while offered barrier and finish events are
discarded. Previously accepted accesses and replies drain before cancellation
is reported. If a completion result was already offered, it remains stable and
the consumer must withdraw unaccepted work when accepting that result. Output
inspection and patch release remain explicit after control completion.

The compact test covers held write replies before a barrier, cross-invocation
reads after release, rejected writes, divergent barriers, cancellation with an
outstanding reply and reuse:

```sh
sbt 'testOnly opengpu.graphics.TessellationPatchContextSpec opengpu.graphics.TessellationPatchContextCancelSpec'
```

Shared-CU instruction execution, tessellation level/primitive generation, native
submission and full H8 qualification remain open.

## GPU-memory tessellation patch inputs

`TessellationPatchInputDma` loads raw 32-bit per-vertex input components from a
strided GPU-memory view into an already opened patch context. It checks the
vertex/component limits, widened final address, logical extent, four-byte
alignment and complete cache-line allocation bounds before issuing traffic.
Zero stride repeats an element; zero components complete without a memory
request. A line is retained for subsequent words in that line.

The enclosing owner supplies the patch layout, pins translation and the
published allocation, reserves memory transaction ID zero, and routes only
input-load replies to the DMA until completion. It starts the control shader
only after successful loading. Offered requests and patch writes remain held
through cancellation; accepted memory and patch responses must drain before a
completion can be consumed. Earlier patch writes are not rolled back after a
fault or cancellation. Completion distinguishes invalid descriptors, memory
faults, patch faults and cancellation, and reports the acknowledged word count.

`TessellationSystemIsolineInput` owns the loader and the shared-system shader
task. It opens the patch, drains loading, and seals the patch only after a
successful load; input failures cancel and drain the source before task
completion. The enclosing owner must drain provisional output and retain the
input allocation and translation throughout the task.

The bounded tests cover unaligned and out-of-range descriptors, the last word
at the 4 GiB boundary, strided input spanning two cache lines, stalled offers,
fault and cancellation drain, and reuse. A patch-context test reads the loaded
components through the control-access interface. A real shared-CU test runs a
control shader that loads the GPU-supplied value and drives isoline evaluation,
then checks invalid-descriptor, memory-fault and held-request cancellation
recovery. Native submission and compiler-generated descriptors remain open.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.productQualification=0 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationPatchInputDmaSpec opengpu.system.TessellationPatchInputDmaContextSpec opengpu.system.TessellationSystemIsolineInputSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

`TessellationSystemIsolineInputImage` combines this loader with the image
frame owner. Each task loads its input before control-shader execution; the
frame owner reports input faults and drains the source and accepted input
traffic before completion. The enclosing system must reserve input memory ID
zero on the separate input port and retain the translation and allocation until
frame completion. Provisional output must be drained on a failed or cancelled
frame. The bounded image tests cover descriptor admission, real two-CU shader
execution and image output, input faults, cancellation and subsequent reuse.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationSystemIsolineInputImageAdmissionSpec opengpu.graphics.TessellationSystemIsolineInputImageSharedSpec'
```

## Input-backed tessellation patch sequence

`TessellationPatchSequence` issues serial input-backed patch tasks from one
caller-supplied template and a patch count. It advances the input base by a
byte stride and the patch task ID by one, retaining the draw token. It advances
the sequence and primitive-ID bases by the number of published primitives
after each successful patch and stops on overflow before issuing another patch. Before
issuing any task, it checks layout limits, the complete final input extent,
allocation and alignment, and task ID and address overflow. A zero count is
an empty sequence. A zero patch stride repeats the same input.

A task offer remains stable until accepted, including during cancellation.
The frame owner forwards `childCancel` to the image frame and keeps translation
and allocations alive until both sequence and frame complete. When the image
frame has reached terminal completion and cannot accept further tasks, the
owner asserts `frameStopped`; this explicitly flushes an unaccepted offer and
reports status 1 (or 4 if cancelled). An accepted child must instead report
completion before the sequence completes. Completion records the number of
successful patches, the failed index, and the most recent child result; status
1 denotes invalid sequence or a stopped frame, while other nonzero child
statuses and cancellation propagate. The enclosing frame completion retains
the detailed frame failure. Provisional output must be drained.

The bounded sequencer test covers multi-patch strides, the last word at 4 GiB,
overflow and cross-patch primitive numbering, empty draws, held offers, cancellation, child failure, terminal-frame
flush, completion backpressure and reuse. This is a task producer, not yet a
native command ABI or an integrated native draw submission path. Other domains,
compiler integration and product qualification remain open.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationPatchSequenceSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

## Controlled isoline patch on the shared CU

`IsolinePatchSharedCu` connects a retained control patch and isoline evaluation
to the existing shared geometry CU service. It keeps the task's command ID and
kernel descriptor through completion and drains accepted evaluator requests
and patch inspections on cancellation. Outputs are provisional until the
completion succeeds; the enclosing owner must pin shader resources and reserve
the command ID throughout that interval.

The bounded admission test covers invalid layouts, cancellation before launch,
held completion and reuse. CI also runs the bounded patch evaluator and CU
executor tests. The local real-CU qualification executes the control-provided
isoline coordinates, checks both line endpoints, and reads retained patch data
while the evaluated output is held. It also cancels after a CU takes ownership,
drains the geometry worker's failed-operation trap, and checks cancellation
completion and release of CU ownership. The same CU then runs a healthy patch
and produces both expected endpoints:

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.IsolinePatchSharedCuAdmissionSpec'
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.IsolinePatchSharedCuQualificationSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

This uses the internal geometry scalar instruction ABI for coordinates. The
shader cannot yet read arbitrary patch data; control shader execution, full
TCS/TES ABI and compiler support, other tessellation domains, native submission
and product qualification remain open.

## Tessellation patch storage

`TessellationPatchStore` owns one input patch and one control-output patch at a
time. It accepts up to 32 input and output vertices, 64 components per vertex,
120 per-patch components, and 2,048 total control-output components. Input and
output each have 2,048 logical 32-bit words (16 KiB combined). Six tessellation
levels have separate 32-bit registers: GLES excludes those built-ins from the
per-patch component limit. This is logical capacity, not a measured SRAM area.

The store checks layout and access bounds, serializes reads and writes, holds
replies under backpressure, and blocks release until outstanding replies drain.
As with uninitialized shader outputs, unwritten words have undefined contents;
the enclosing producer must apply initialization and barrier rules. The
[OpenGL ES 3.2 specification, §11.2.1 and Table 21.45](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf)
defines the component limits and the separate tessellation-level accounting.

The compact test covers maximum layouts, a 2,049-component rejection, separate
input/output address spaces, all six levels at full output capacity, invalid
accesses, held replies and reuse:

```sh
sbt 'testOnly opengpu.graphics.TessellationPatchStoreSpec'
```

Control shader scheduling, invocation barriers, native patch submission,
physical SRAM mapping and full tessellation qualification remain open.

## Tessellation pre-clip feedback owner

`TessellationSystemIsolineViewport` can optionally place the existing feedback
export owner between TES primitive mapping and clipping. The mapper retains
raw per-vertex varyings for capture; the export owner applies the requested
flat shading only to the downstream primitive. The task's feedback scope stays
pinned through acknowledged stores and completion. Raster discard suppresses
raster output while retaining successful capture. Feedback faults use viewport
status 5 and retain their original status in `feedbackStatus`.

A real shared-CU control/evaluation test checks exact raw capture before flat
shading and clipping, held store acknowledgements, scope retention, raster
discard, memory faults, task cancellation and object reset. Simultaneous fault
and cancellation/reset retain the primary memory fault; each interrupted or
faulted case completes a healthy retry. The existing mapper and viewport tests
also pass with feedback disabled. Reproduce with:

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=4 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  'testOnly opengpu.graphics.TessellationIsolinePrimitiveMapperSpec opengpu.system.TessellationSystemIsolineViewportSpec opengpu.system.TessellationSystemIsolineFeedbackSpec'
```

The opt-in bin pass forwards feedback scope and memory ownership through each
task and fences object commands until the pass drains. Real shared-CU bin tests
pass capture, raster discard, memory fault, task cancellation and global reset,
including simultaneous faults and a healthy retry after each failed pass. They
also check exact captured words, replay behavior, held acknowledgements and
feedback-control fencing. Reproduce with the same sbt options and
`testOnly opengpu.system.TessellationSystemIsolineBinPassSpec opengpu.system.TessellationSystemIsolineFeedbackBinSpec`.

The opt-in image pipeline also pins each task's feedback scope and fences
feedback object commands until the frame drains. Cancellation-induced bin
errors do not replace the original task fault, and feedback control remains
usable after a failed frame. The local shared-system image test exercises
capture, discard, memory fault, reset, and simultaneous reset/fault, while the
bounded admission test checks a held feedback-control reply. Reproduce with the
same sbt options and
`testOnly opengpu.graphics.TessellationSystemIsolineImageSharedSystemSpec opengpu.graphics.TessellationSystemIsolineImageAdmissionSpec`.
The shared-system image suite remains local-only because of its full GPU model.

The opt-in GPU-backed patch input owner pins the feedback scope as the task is
accepted, fences feedback commands for the frame, and propagates feedback reset
through its input DMA. A primary feedback memory fault retains status 5 over a
simultaneous reset. The local two-CU input-image test exercises GPU input reads,
feedback capture, fault and reset, held control commands and scope retention;
the existing input-fault and cancellation recovery cases remain in the same
suite. Run it with the options above and
`testOnly opengpu.graphics.TessellationSystemIsolineInputImageSharedSpec opengpu.graphics.TessellationSystemIsolineInputImageAdmissionSpec`.
The shared-system suite remains local-only because of its full GPU model.

The opt-in patch sequence owner retains the draw's feedback scope across all
patches and fences object commands until the frame ends. A feedback reset is
held until a pending patch offer has been accepted or the sequence has stopped;
the sequence reports a primary feedback memory fault ahead of cancellation.
The local two-CU sequence test checks exact capture across two GPU-backed
patches, feedback fault and reset races, and reset during a held patch offer.
Reproduce with the same sbt options and
`testOnly opengpu.system.TessellationPatchSequenceSpec opengpu.graphics.TessellationSequenceImageSharedSpec opengpu.graphics.TessellationSequenceImageAdmissionSpec`.
The shared-system sequence suite remains local-only.

The translated native sequence owner pins feedback scope at submit, before
reading the draw record, and defers reset until the reader's held draw can
retire. The reader preserves a primary feedback fault over cancellation. A
local two-CU test checks capture and fault/reset races while record, patch input
and image traffic use Sv32 translation. Feedback stores remain a separate
client carrying their pinned root to the enclosing translator; the test checks
that root and the captured words, not translation of that separate client.
Reproduce with the options above and
`testOnly opengpu.system.TessellationNativeSequenceReaderSpec opengpu.graphics.TessellationNativeSequenceImageSharedSpec`.
The shared-system suite remains local-only.

The opt-in native job owner pins feedback scope when a job is submitted and
fences feedback commands while the job owns the pipeline. Reset waits for
outstanding job offers and feedback ownership to drain; a primary feedback
memory fault survives cancellation, including simultaneous child and frame
completions. The reader tests and local two-CU job tests cover translated job,
draw, input and image traffic, exact capture, feedback fault/reset races and
reset during a held job read. Feedback stores still carry a separate root for
the enclosing translator. Reproduce with the options above and
`testOnly opengpu.graphics.TessellationNativeJobReaderSpec opengpu.graphics.TessellationNativeJobImageSharedSpec opengpu.graphics.TessellationNativeJobImageAdmissionSpec`.
The shared-system job suite remains local-only.

The opt-in native host now routes feedback control and stores between the
legacy and tessellation owners using the checkpoint-transfer coordinator. See
[host integration](#native-tessellation-host-integration-experimental) for its tested
scope and remaining limits. Driver integration, physical alias provenance,
query coexistence and product performance qualification remain open, as do the
broader mobile GPU alignment requirements.

## Quad-domain tessellation boundary

`TessellationQuadBoundary` streams the four independently subdivided outer
edges in counter-clockwise quad-domain order: bottom, right, top, left. It
uses the existing level-preparation and edge-coordinate units, including equal,
fractional-even and fractional-odd spacing. Each edge emits its starting
corner and omits its ending corner, so a corner is owned once by the next edge.
Coordinates use unsigned Q0.24. A held vertex stays stable through backpressure
and cancellation; accepted work drains before the completion is offered.
Discarded patches emit no vertices. The count can reach 256.

The [OpenGL ES 3.2 specification, §11.2.2](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf)
defines the quad edge assignments and spacing rules. This component does not
yet generate interior vertices or triangles, order triangle winding, invoke
shaders, or integrate with the native host. It is not quad tessellation or an
H8 capability claim; those steps and product qualification remain open.

The bounded test compares all three spacing modes against independent rational
coordinates, including different levels per edge and maximum levels. It also
checks held output, cancellation during output and coordinate computation,
discard, invalid spacing and reuse:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationQuadBoundarySpec opengpu.graphics.TessellationEdgeCoordinateSpec opengpu.graphics.TessellationLevelPrepareSpec'
```

#### Interior grid

`TessellationQuadInterior` streams the inner grid in row-major order. The first
inner level controls the vertical subdivisions and the second controls the
horizontal subdivisions. When a nontrivial patch promotes an inner level of
one, the stream keeps grid indices as distinct identities even if fractional-odd
spacing produces coincident coordinates on the boundary. The maximum grid has
63 × 63 vertices. The all-one equal-spaced quad has no interior grid vertices.
Output and cancellation obey the same held-offer contract as the boundary.

The independent reference test checks all spacing modes, unequal inner levels,
promotion, the maximum grid, held output, cancellation, discard and reuse:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationQuadInteriorSpec opengpu.graphics.TessellationQuadBoundarySpec opengpu.graphics.TessellationEdgeCoordinateSpec opengpu.graphics.TessellationLevelPrepareSpec'
```

The grid unit alone does not connect to the boundary or emit triangles; the
patch-level generator described below owns those operations.

#### Edge-strip triangulation

`TessellationQuadStrip` joins one counter-clockwise outer side to the
corresponding ordered inner side using one triangle per edge advance. The
caller supplies both endpoint vertices and monotone non-crossing sides, with
the inner side to the left of the outer side. Zero inner segments represent a
single point and produce a fan. The module compares normalized topological
progress to choose which side advances; both windings, output backpressure,
cancellation and reuse are supported. Inputs are streamed, so the module does
not allocate a full patch-sized point buffer.

The strip test covers unequal subdivisions up to 64 segments, point-like
inner edges, all four orientations, both windings, edge coverage, signed area,
held output and cancellation. Reproduce it with:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationQuadStripSpec opengpu.graphics.TessellationQuadInteriorSpec opengpu.graphics.TessellationQuadBoundarySpec'
```

The patch-level generator below supplies the matching strips and inner cells.
The isolated strip test alone does not establish complete quad coverage or
product qualification.


#### Patch-level generator

`TessellationQuadGenerator` composes boundary capture, interior-axis capture,
inner-cell triangle pairs and four streamed edge strips. It emits either
clockwise or counter-clockwise triangles, or one point per boundary/grid vertex;
coincident fractional-odd grid vertices remain distinct. The all-one quad has
two triangles or four points. A 64-by-64 patch with maximum outer levels emits
8,192 triangles or 4,225 points. The owner retains at most four sets of 64
boundary coordinates and two sets of 64 interior-axis coordinates, not a
patch-sized vertex array. Output remains held during cancellation.

The standalone bounded test covers distinct edge levels, all spacing modes,
both windings, degenerate inner rectangles, point mode, maximum levels,
outer-edge coverage, signed area, sampled non-overlap, cancellation and reuse:

```sh
sbt -J-Xmx4G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationQuadGeneratorSpec opengpu.graphics.TessellationQuadStripSpec opengpu.graphics.TessellationQuadInteriorSpec opengpu.graphics.TessellationQuadBoundarySpec'
```

These directed checks do not prove every tessellation configuration. The
generator is connected to an evaluation shader and primitive mapping in the
internal path described in `TESSELLATION.md`; it is not yet
connected to the native host. Triangle-domain tessellation, runtime/API integration, timing and
product qualification remain open; this is not an H8 capability claim.

## Quad tessellation evaluation handoff

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

#### Shared-CU execution

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

#### Controlled patch storage

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

#### Control-CU execution

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

#### Primitive mapping and viewport

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

#### Bin storage and replay

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

#### Pre-clip feedback

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

#### Typed image pipeline

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

The three-domain host baseline before query/feedback composition passed a
local two-CU translated 4×4 test. It covered a quad descriptor read fault,
reset with an outstanding reply, and successful quad, isoline, triangle and
quad reuse, with exact quad and triangle pixel checks:

```sh
sbt -J-Xmx10G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationNativeQuadHostAxiImageSpec'
```

The opt-in native host connects this owner, including shared feedback. Its
feedback-enabled two-CU configuration passes Chisel elaboration, and the isoline,
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

Full-host FIRRTL compilation exposed a combinational cycle between feedback
admission and payload ownership. The owner selection now comes from the
admission's registered domain; a small composed simulation passes all three
domains with backpressure and retirement, including a later job waiting for
the previous payload owner to release:

```sh
sbt 'testOnly opengpu.system.TessellationFeedbackPayloadAdmissionSpec'
```

The corrected local two-CU host simulation passes a live point-capture object
across isoline, triangle, quad and isoline jobs, checking all 22 captured
values, counters and buffer guards. The fixtures use distinct control-program
addresses so executable snapshots do not alias cached lines. Reproduce with:

```sh
sbt -J-Xmx10G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationNativeThreeDomainFeedbackHostAxiSpec'
```

The local two-CU combined host case also passes with live visibility and
primitive queries and feedback across isoline, triangle, quad and isoline
jobs. It checks 22 primitive captures and buffer guards, a false visibility
result for the feedback sequence, and a true result for a subsequent quad job
whose queued query end cannot pass the accepted job. The query fixture also
uses distinct executable addresses for each domain. Reproduce with the same
options above and:

```sh
sbt -J-Xmx10G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationThreeDomainVisibilityQuerySpec opengpu.system.TessellationFeedbackPayloadAdmissionSpec' \
  'testOnly opengpu.graphics.TessellationThreeDomainFeedbackQueryHostAxiSpec'
```

This case does not qualify broader native submission or product gates.

A second local two-CU run replaces the three different control programs at
the same physical address. After each external-memory update it completes a
256-byte physical invalidate and flushes the idle instruction context before
the next job; the same 22 values, counters and guards pass. The fixture writes
external memory directly and does not model CPU cache cleaning or establish
general system coherence. Run the reuse case with the same options above and:

```sh
sbt -J-Xmx10G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationNativeThreeDomainFeedbackHostAxiSpec -- -z "reused executable=true"'
```

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
version and four frame-owner identities against the job. Generated word rules
check canonical padding and reserved words throughout the frame and each draw
for all three domains. A separate draw snapshot precheck verifies the
domain-specific header, draw index, owner identity and unbound command ID.
The patch-input span precheck checks vertex and component limits, task-ID and
address overflow, alignment, and allocation bounds while preserving
empty-sequence and inactive-input behavior.

The virtual descriptor-alias precheck also rejects writable arena, pool,
directory and active-engine scratch ranges that overlap the job, frame or draw
array. It checks half-open ranges, including an empty draw array and a limit
at 2^32, and rejects limits above that address boundary. Physical aliases still
require separate proof. The standalone C boundary check can also be run with:

```sh
cc -std=c11 -Wall -Wextra -Werror driver/tests/opengpu_tessellation_job_validate_test.c -o /tmp/opengpu-tess-job-validate
/tmp/opengpu-tess-job-validate
```

These prechecks accept the actual generated job, frame and draw snapshots for
all three domains:

```sh
sbt 'testOnly opengpu.graphics.TessellationNativeHostDomainFixtureSpec -- -z "decode actual"'
```

These checks do not validate all frame and draw semantics or resource
provenance: physical aliases, permissions and lifetime pinning remain open.
The [internal driver transport](#native-tessellation-command-transport) now requires
and invalidates the checked snapshots, but no userspace submission path is
provided.

#### Native fragment image coexistence

The opt-in host can enable native binned fragment images and native
tessellation together. The host shader router snapshots each legacy task's
image publication with its launch and supplies a disabled image task to every
tessellation job. Tessellation-stage image bindings are not admitted by this
path. General cross-domain image ordering and driver admission remain open.

The local two-CU host check passes an invalid tessellation command, a 4×4
tessellation job, a 17×9 fragment-image frame, another tessellation job, a
second image frame and another invalid command. It checks the stored image
components, framebuffer contents and unchanged retired image across the
handoff. The small router checks cover held launches and task-context reuse.
The full-host case remains outside CI.

For the default product profile, fresh 720p workload and whole-host emissions
match the `7617dcf4` qualification baseline after ignoring source-location
comments; all six workload fixtures and their manifest match byte for byte.
This does not establish a timing or frame-rate result. Re-emit both profiles
with:

```sh
python3 scripts/qualify_native_product_compiled.py --product --stage prepare \
  --jobs 2 --hierarchy-profile fp32 --output /tmp/opengpu-product-inputs
sbt -J-Xmx6G -Dopengpu.productQualification=1 \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-native-four-engine-product-host /tmp/opengpu-product-host'
```

Run the bounded router and host checks locally with:

```sh
sbt -J-Xmx10G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=8 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.productQualification=0 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationHostShaderRouterSpec' \
  'testOnly opengpu.graphics.TessellationNativeFragmentImageHostAxiSpec'
```

Quad generation also retains its vertex set, triangle count and signed area
under 90/180/270-degree outer-level rotation and reflection (30 directed
checks); the emitted winding follows the explicit clockwise input and the
interior diagonal choice is deterministic but not rotation-covariant. Triangle
generation retains its point set, triangle vertices, primitive count and
signed area under 120/240/360-degree barycentric edge rotation and edge-0/1
reflection across five symmetric and asymmetric level sets, three spacings,
points and triangles (40 directed checks); the mirrored point set may differ by
one Q0.24 unit where an asymmetric edge's integer remainder is distributed.
Broader image-level feedback fault/reset qualification, native driver/API
integration, complete triangle spacing variants, physical timing and product
qualification remain open. These tests do not establish general quad
conformance or H8 completion.

## Sequenced tessellation image frames

`TessellationSequenceImage` connects an input-backed patch sequence to the
shared-CU tessellation image frame owner. A draw template supplies the patch
count, GPU input view and patch stride. Each accepted draw produces a held
completion; frame completion waits for that report and all frame work to drain.
Multiple draws may use an open frame. The enclosing owner retains the input
allocation, translation and input-memory transaction ID zero until frame
completion and drains provisional outputs on fault or cancellation.

A cancellation does not withdraw an offered patch: the frame sees cancellation
only after the offer is accepted, or after the sequence completes. If the
frame reaches a terminal result first, it flushes a pending unaccepted patch.
A sequence admission failure cancels and drains the frame; the draw completion
contains the cause. Child input and shader failures retain the image owner's
reported fault. Native descriptor reading and driver submission, other
primitive domains, and product timing and throughput qualification remain open.

The bounded admission test checks invalid frame rejection and reuse. The local
4×4 two-CU integration uses one draw to load two patches from GPU memory, run
control, evaluation and fragment shaders, and publish shaded and clear RGBA8
pixels. It also exercises input faults, cancellation with a held request,
sequence admission failure and a recovery frame.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationSequenceImageAdmissionSpec opengpu.graphics.TessellationSequenceImageSharedSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

## Tessellation control in the shared shader router

The optional tessellation sideband on `SharedShaderTaskRouter` snapshots warp
bases with the accepted local task, retains them across queued and stalled
launches, and selects them with the translated pool tag. Each CU's patch
accesses, parks, finishes, responses, resumes, cancellation and terminal signals
follow its current graphics owner. Clients retain their local tags, so equal
local tags from different clients do not alias. Capability-disabled routers
retain their existing interfaces.

`GeometryFragmentShaderRouter` can enable the sideband for its geometry client
while leaving the ordinary fragment interface unchanged. Its fragment branch
never offers tessellation bases or responses and asserts if control traffic is
attributed to a fragment task. The enclosing client must retain patch and
shader resources until the routed task completes and drain accepted traffic.
This routing capability does not by itself connect the system tessellation
producer to the fragment or image pipeline.

The tests cover queued launches with held bases, same local tags on two CUs,
access and response backpressure, barrier and finish traffic, cancellation,
terminal routing, reuse, and ordinary fragment isolation. Existing shared
router and geometry/fragment tests run with the capability disabled.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.productQualification=0 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.SharedShaderTessellationRouterSpec opengpu.graphics.GeometryFragmentTessellationRouterSpec opengpu.graphics.SharedShaderTaskRouterSpec opengpu.graphics.GeometryFragmentShaderRouterSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

## Shared-system tessellation control

`GpuSystem` can optionally expose tessellation-control sidebands on its shared
CU graphics interface. A control launch supplies warp bases with the accepted
kernel; each CU's access, barrier and finish traffic is routed only while that
CU is owned by a graphics task. Geometry and tessellation-control modes are
mutually exclusive for a launch. The default system leaves the capability off.

`TessellationCuIsolineSystemClient` reserves a graphics command tag across a
control workgroup and its subsequent evaluation invocations. It identifies the
control CU from the pool's ownership and tag outputs, routes patch responses,
resume, cancellation and terminal indications to that CU, and uses the
existing geometry service for evaluation. The enclosing owner reserves the tag,
pins resources and supplies texture, trap and invalidation services until the
completion is accepted. The one-dimensional control workgroup must fit in one
CU and in the 32-invocation patch limit. Output remains provisional until the
final completion succeeds.

The bounded actual-system simulation runs real control and evaluation shader
programs through the shared dispatch, CU and L2 path, checking generated
coordinates, a control-written patch word, evaluation cancellation and reuse.
It uses one four-lane/two-warp CU and does not establish multi-CU contention, native submission, raster/image
integration, product throughput or physical timing.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationCuIsolineSystemClientSpec'
### With the same sbt options, also run:
### testOnly opengpu.system.GpuSystemSpec -- -z "execute graphics tasks and compute on the same 1 CUs"
python3 scripts/test_test_selection.py
```

## Shader-controlled isolines in window bins

`TessellationSystemIsolineBinPass` retains a pass across shared-system control
and evaluation shaders, primitive mapping, clipping, viewport conversion and
acknowledged window-record bin storage. It replays complete window records in
pass sequence order to independent tile engines. The task identity must match
the pass, the tag and shader resources must remain reserved through task
completion, and all accepted writes, reads and offered replay records must
drain before pass completion. Outputs remain provisional until successful pass
completion. Geometry invocation and layer metadata are not supplied by this
isoline path.

The bounded actual-system test runs two real control/evaluation shader pairs,
checks clipped window coordinates and a control-written varying after storage
and replay, holds replay output under backpressure, and checks identity
rejection, a failed record write and subsequent reuse. It uses one four-lane,
two-warp CU and a 4×4 frame with two replay engines. This does not establish
native submission, fragment shading or image publication, multi-CU contention,
default-profile performance, physical timing or product qualification.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationSystemIsolineBinPassSpec'
python3 scripts/test_test_selection.py
```

## Shader-controlled isoline image path

`TessellationSystemIsolineImagePipeline` connects the shared-system control
and evaluation shaders, clipped window-bin storage and replay to the typed
fragment and image engines. The tessellation-aware shader router retains the
control task's translated ownership while fragment workers share the same CU
pool. The pass retains shader, patch, bin, tile, payload and image ownership
until all accepted work and publication responses drain. Its physical-address
interfaces and shader instruction encodings are internal, not a native
submission or compiler ABI.

The local 4×4 qualification uses two four-lane/two-warp CUs and two image
engines. Two control shaders write tessellation levels and a patch value; real
evaluation and fragment shaders carry that value to an RGBA8 attachment. The
test checks shaded and clear image bytes, completion, memory drain and payload
reuse. It does not establish native submission, all tessellation domains,
multi-CU contention under sustained load, physical timing or product
qualification. The full shared-system qualification remains local; CI selects
the bounded admission, shared-router, system-bin and typed-image tests listed in
`scripts/changed_test_suites.py`.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationSystemIsolineImageSharedSystemSpec'
### With the same sbt options: testOnly opengpu.graphics.TessellationSystemIsolineImageAdmissionSpec
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

## Shader-controlled isolines to window primitives

`TessellationSystemIsolineViewport` runs one control patch and its evaluation
invocations through shared GPU dispatch, then maps the evaluated components to
primitive positions and varyings, clips the primitive and applies the viewport.
The caller supplies the linked component mapping, stable graphics identity and
sequence base, a legal viewport, and the system client's reserved tag and
shader resources. Sequence or primitive-ID overflow and invalid component
mappings fail the task. Window primitives remain provisional until completion;
accepted outputs and all active shader and graphics stages must drain on
failure or cancellation.

The actual-system test uses real control and evaluation instructions on one
four-lane/two-warp CU. Control writes levels and a per-patch value, and the
evaluation shader passes the value to a varying. The test checks clipping and
window coordinates, held-output backpressure, evaluation cancellation, invalid
mapping and viewport, and reuse. It does not yet connect this path to binning, rasterization, native
submission, multiple contending CUs, or default-profile product qualification.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationSystemIsolineViewportSpec'
python3 scripts/test_test_selection.py
```

## Translated sequenced tessellation images

`TessellationTranslatedSequenceImage` connects the sequenced image frame owner
to `TessellationInputMemoryFabric`. Renderer and patch-input requests share
one physical memory port after independent Sv32 translation. Frame completion
is held until both the image owner and memory fabric have drained. The caller
retains `satp`, mappings, shader resources and allocations until completion;
physical alias provenance remains the caller's responsibility.

The bounded admission test checks malformed frames before memory or shader
traffic. The local two-CU, 4×4 test uses Sv32 mappings for frame and input
traffic, including non-identity mappings for inputs and image output. It loads
two patches, runs control, evaluation and fragment shaders, and
checks shaded and clear RGBA8 pixels plus stored primitive IDs. Physical requests
are checked to ensure virtual input and image addresses do not escape translation. It exercises
input-memory faults, a held input request during cancellation, invalid draw
admission, and subsequent reuse. Native command records, driver submission,
other tessellation domains, product-sized workloads and physical timing remain
open.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationTranslatedSequenceImageAdmissionSpec opengpu.graphics.TessellationTranslatedSequenceImageSharedSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

## Triangle tessellation boundary

`TessellationTriangleBoundary` streams the independently subdivided outer
edges in counter-clockwise barycentric order: w=0, u=0, v=0. These correspond
to GLES outer levels 2, 0 and 1; the fourth level is unused. Each edge emits
its starting corner and excludes its ending corner. Coordinates are unsigned
Q0.24 and sum exactly to one. The shared level-preparation and edge-coordinate
units implement equal, fractional-even and fractional-odd spacing. Accepted
outputs remain stable under backpressure and cancellation drains accepted work.

The directed test checks distinct and maximum edge levels, all spacing modes,
exact coordinates and barycentric sums, held output, cancellation, discard,
invalid spacing, the unused fourth level and reuse:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationTriangleBoundarySpec'
```

The [OpenGL ES 3.2 specification, §11.2.2.1](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf)
defines the triangle domain and its outer-level assignments. This boundary does
not generate interior vertices or primitives or execute shaders; it does not
establish triangle-domain conformance or complete H8.

## Triangle tessellation evaluation handoff

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
and command routing, reset with a feedback reply outstanding, and a capture
across two real exporters with
exact counters and stored values. The admission-and-bridge test also passed a
live capture transfer before accepting the next domain job, and confirmed that
an injected transfer failure blocks both domains until feedback reset. Run:

```sh
sbt 'testOnly opengpu.graphics.TessellationDomainFeedbackBridgeSpec'
```

`TessellationDomainFeedbackAdmission` holds a classified job until its feedback
owner is selected. Its directed test passed successful switching, cancellation
while a switch was outstanding, transfer failure, rejection while ownership is
poisoned, and reuse after recovery. Run:

```sh
sbt 'testOnly opengpu.system.TessellationDomainFeedbackAdmissionSpec'
```

The domain owner connects this bridge and admission controller when native
feedback is enabled. The host uses the combined isoline and triangle owner;
its feedback-enabled configuration passes elaboration. The combined owner also
passes elaboration with local and memory-backed payloads:

```sh
sbt 'testOnly opengpu.system.TessellationNativeDomainFeedbackElaborationSpec'
```

The host allows primitive and visibility queries with native feedback. Query
and fault combinations across domain switches remain to be qualified.

`TessellationDomainSampleMux` forwards the selected domain's sample completion
without dropping held results. `TessellationDomainSampleMuxSpec` passed both
domains, status and passing-sample preservation, and backpressure:

```sh
sbt 'testOnly opengpu.system.TessellationDomainSampleMuxSpec'
```

`TessellationDomainVisibilityQuerySpec` also connects the mux to the visibility
query engine. It passed sample accumulation across both domains, fault
propagation and address-space isolation:

```sh
sbt 'testOnly opengpu.graphics.TessellationDomainVisibilityQuerySpec'
```

Together the feedback admission, bridge, sample-mux and visibility-query
suites pass seven bounded cases. Domain-owner elaboration also passes with
local and memory-backed payloads. These checks can be reproduced with:

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationDomainFeedbackAdmissionSpec opengpu.graphics.TessellationDomainFeedbackBridgeSpec opengpu.system.TessellationDomainSampleMuxSpec opengpu.graphics.TessellationDomainVisibilityQuerySpec opengpu.system.TessellationNativeDomainFeedbackElaborationSpec'
```

The host job fixtures for both domain formats passed construction and layout
checks. A local two-CU host test keeps one feedback object live through
isoline, triangle and isoline point jobs, checking the exact captured words,
counters and memory guards. Reproduce with:

```sh
sbt -J-Xmx8G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simSeparateBuild=1 \
  'testOnly opengpu.graphics.TessellationNativeDomainFeedbackHostAxiSpec'
```

An earlier no-feedback host version passed descriptor fault and reset recovery
followed by triangle, isoline and triangle jobs. Broader feedback host fault
and reset coverage, native submission product qualification and GLES
conformance remain open.

## Triangle-domain primitive generation

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

## Triangle tessellation interior

`TessellationTriangleInterior` streams the concentric inner-ring vertices used
by the triangle domain. It temporarily subdivides an outer edge using the first
inner level, projects its interior vertices onto each successive inner edge,
and removes two segments per ring. Directed sides exclude their ending corner;
a final two-segment ring becomes one center vertex. Ring, side and index retain
topological identity when fractional-odd promotion produces coincident points.

Coordinates are Q0.24. Perpendicular projections round locally, and the center
uses the nearest common Q0.24 value to one third; its three coordinates sum to
one minus one LSB. The directed test compares representative equal and
fractional levels and the maximum level against an independent real-valued
construction within 64 Q0.24 LSB, and checks counts, coordinate sums, discard,
backpressure, cancellation and reuse. This is a directed error check, not a
proof of the error bound for every level.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationTriangleInteriorSpec'
```

Adjacent rings and the independently subdivided outer boundary still need to be
joined into non-overlapping primitives. Evaluation, native submission and full
triangle-domain and product qualification remain open.

The [OpenGL ES 3.2 specification, §11.2.2.1](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf) defines the concentric triangle construction.

## Triangle tessellation ring strip

`TessellationTriangleStrip` streams triangles between two ordered sides of a
triangle-domain ring. The caller supplies barycentric Q0.24 vertices, including
both endpoints of each side. The outer side has 1–64 segments; the inner side
has 0–64. A zero-segment inner side supplies one vertex and creates a fan.
The caller must provide consistently ordered, non-crossing sides whose strip
polygon is convex, with the inner side to the left of the counter-clockwise
outer side. The module does not validate coordinates or construct the rings.

The block advances the side with the smaller normalized segment progress and
emits one triangle per segment, preserving task ownership and sequence numbers.
It supports either winding and holds an offered triangle until accepted, even
when cancellation arrives. Cancellation then completes with the number of
triangles already accepted. Invalid segment counts complete without output.

The directed simulation checks nine segment-count pairs, three barycentric
rotations and both windings, including unequal counts and the 64-segment limit.
It checks boundary edges, signed area, coordinate sums, backpressure,
cancellation, invalid counts and reuse:

```sh
sbt -Dopengpu.productQualification=0 'testOnly opengpu.graphics.TessellationTriangleStripSpec'
```

This primitive alone does not implement a triangle-domain primitive generator,
shader execution or an end-to-end rendering path.

## Tessellation warp barrier adapter

`TessellationWarpBarrier` parks multiple SIMT warps for one control-shader
patch. Each lane mask maps lane `i` to invocation `base+i`; active masks must
be nonempty, disjoint and within the 32-invocation patch limit. The adapter
serializes arrivals to the patch context and resumes each warp only after all
of its invocations are released. Barrier ID and epoch accompany each resume.
Invalid masks, overlaps and inconsistent releases report a checked error.

The owner must stop offering new work on cancellation and assert `terminal`
when it accepts an error result from the patch context. An arrival already
offered is held until accepted, and completed resumes are held under
backpressure. The patch context's cancellation path drains an already offered
arrival. Once an error result has been accepted, `terminal` retires any arrival
that the closed context can no longer accept and cancels the parked warps.
This module alone does not launch control shaders or connect the actual
compute-unit instruction scheduler.

The companion [warp patch access adapter](#tessellation-warp-patch-access)
serializes a warp's lane accesses to the same checked patch context. Its owner
must retire each instruction before parking that warp at a barrier.
The [control warp scheduler](#tessellation-control-warp-ownership) enforces
that ownership and drains both adapters before patch close.

The tests connect this adapter to the patch context for two 16-lane warps,
32 invocations, repeated barriers and cancellation, and check invalid masks and
backpressure:

```sh
sbt 'testOnly opengpu.graphics.TessellationWarpBarrierSpec'
```

Native control-shader execution, workgroup scheduling, primitive generation,
API/compiler integration and full H8 qualification remain open.

## Tessellation warp patch access

`TessellationWarpPatchAccess` serializes one warp's active lanes into checked
`TessellationPatchContext` control accesses. Lane `i` owns invocation
`base+i`; a nonempty mask must fit in the 32-invocation patch. The owner must
route control replies to this adapter and issue a warp's barrier or finish
only after its access result is consumed. The adapter retains one instruction
at a time, accepts lanes in increasing order, and holds its result until
accepted. Input, per-vertex output, per-patch and level permissions remain
enforced by the patch context.

The result contains a retired-lane mask and returned data. A checked access or
reply error stops further lanes after the failing reply; earlier writes are
not rolled back. Cancellation holds an offered access until accepted and drains
its reply, then reports cancellation without issuing another lane. The owner
must continue routing accepted replies during cancellation. When the owner
accepts a terminal patch-context error, it must assert `terminal` to retire an
unaccepted offer that the closed context can no longer receive; accepted
requests still drain their replies. Invalid or empty
masks produce a failure without patch traffic. A held result is immutable even
if cancellation arrives after it was offered.

The bounded tests cover sparse and full lane masks, invocation 31, held offers
and results, invalid masks and replies, a later-lane fault, cancellation and
reuse. A composed patch-context test writes and reads 32 invocation outputs
across two warps and a barrier, and checks a forbidden cross-invocation write.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.productQualification=0 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationWarpPatchAccessSpec opengpu.graphics.TessellationWarpPatchContextSpec opengpu.graphics.TessellationWarpBarrierSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

This is an internal ownership adapter. Actual CU instruction execution,
concurrent warp scheduling, dynamic TCS/TES ABI and compiler support, native
submission, physical timing and product throughput remain open. It does not
close H8.
