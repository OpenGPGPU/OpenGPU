# Native tessellation host integration (experimental)

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

## Owned fragment texture chains at default capacity

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
