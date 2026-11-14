# Dynamic TES patch reads (P40 source)

The opt-in `evaluationPatchAvailable` path supplies TES reads from the actual
owned `TessellationPatchContext`. It serializes each selected vector lane over
that context's inspection port; it does not expand the 61-word static input
snapshot or accept patch values from a CPU. The option defaults to false in
all constructors. No default host factory, discovery word, serialized GNT
record or protected-program admission is enabled by this change.

## Internal instruction and launch contract

This instruction is reserved for the future checked stage-5 program version 2.
Old program version 1 must continue to reject it. The internal launch binding
has its own **binding version 1**, which is not a program-ABI version.

| Field | Contract |
| --- | --- |
| Instruction | CUSTOM-1 opcode `0x2b`, bits 31:26 = 33, funct3 = 0; bit 25 retains ordinary vector mask semantics |
| Sources / result | `vs1` supplies a full uint32 vertex per lane; `vs2` supplies a full uint32 address descriptor; `vd` receives uint32 raw patch words |
| Address bit 0 | Must be 1: TCS output only; this service cannot read the original input bank or write either bank |
| Address bits 1 / 2 | Per-patch / tessellation-level selection; a level also requires per-patch selection |
| Address bits 9:3 | Component index; bits 31:10 must be zero |
| Vertex output | Vertex < captured outputVertices and component < outputComponents |
| Per-patch | Vertex = 0, component < patchComponents |
| Tessellation level | Vertex = 0, component < 6 (outer[0..3], inner[0..1]) |
| Binding | Version 1, stage 5, taskId, generated primitive sequence and the actual captured patch layout |
| Layout | 1..32 input/output vertices; 0..64 components including applicable builtins; 0..120 per-patch components; input product <= 2048 and output product + per-patch <= 2048 |

Full-width operands are checked before SRAM address truncation. An invalid
selected lane faults the entire instruction without publishing any lane's
result. Inactive lanes retain old `vd`. A malformed instruction/operand or
mismatched reply remains a fault even when cancellation also arrives.

The shared task router retains the binding with the accepted launch. The CU
accepts it only for an actual single-invocation geometry/TES launch; it captures
the physical warp and independently enables decode from the checked binding.
The ordinary geometry fn32/33/35/36/37 instructions are unchanged: CUSTOM-1
fn33 is distinct from the existing CUSTOM-0 geometry-output fn33.

## Ownership, arbitration and retirement

The provider checks the actual CU owner and command tag, and compares the
complete request scope with the current evaluation binding. The old static
reader, new vector provider and external inspection port share one arbiter.
A stalled offer retains its issuer, and an accepted request owns the response
credit until that issuer accepts its reply. This consumes no external memory
transaction IDs and does not change P15's two uniform-memory IDs.

Cancellation retains already offered requests and drains accepted inspection
replies. Failed/canceled instructions suppress VRF writes. Normal retirement
uses the real vector commit arbiter and fault path. If physical kernel
retirement has already been proven, only the orphaned commit is discarded;
its patch traffic must still drain. CU completion, shared-owner completion and
frame retirement remain blocked while this work or an inspection credit is
outstanding. A provider fault is retained through subsequent abort draining.

The new mode selects `zeroOnBegin=true` along all three real hierarchies:
SharedEvaluator -> CuPatch -> ControlWarpScheduler -> PatchContext ->
PatchStore. It initializes the current legal input/output ranges and six levels
before opening the patch, using each bank's original write port. Legacy mode
keeps its false default. Dynamic mode rejects a nonzero old static selection
count or selection array; fn32 still supplies TessCoord components 0..2.

## Serialized/admission inventory still to be assigned

A later versioned native contract must carry or derive all of the following;
this source change deliberately allocates no magic, word offsets or MMIO bits:

- Independently verified stage-5 program version 2 and dynamic-read capability;
  exact original code/entry/RX extent and the retained TES resource roots.
- Actual task identity, primitive sequence and legal coupled patch layout.
  These must derive from the accepted owned patch, not shader operands.
- Explicit choice of dynamic reads versus the frozen legacy static selections.
- Independent TCS/TES checked uniform roots and scope bounds from P15/GUR3.
- P39's original VS program, original draw/index/attribute views, slice ordinal
  and instance range, component mapping, and acknowledged patch publication.
  A precomputed CPU patch cannot establish that provenance.

P16's independent validator and immutable snapshots, P17's real Mesa dynamic
vertex/component/per-patch lowering, and the separate LLVM CUSTOM-1
instruction declaration are not implemented by this first P40 block.

## Validation and remaining limits

The small service/provider tests use modeled transport peers. They cover
address limits and high bits, binding version/stage/coupled capacity, default
encoding rejection, held request/commit stability, owner/scope rejection,
three-way response ownership, cancellation draining and reuse. They are not
shader execution or original ESSL evidence.

Final bounded local results: five service/provider/decoder RTL cases passed;
three structural elaboration cases passed (legacy and dynamic mode in all
three domains). These verify the actual patch hierarchy's initialization and
unchanged SRAM depth/write-port count. A 47-source incremental typer check
covering CU/frontend/router and all three evaluator/client/viewport/bin paths
passed, as did 146 selector tests and the integration/shard catalog check.
The larger 68-source image/sample dependency check did not pass: hierarchy
macro errors against the older dependency classpath preceded a 128MiB heap
exhaustion. No larger heap or complete image/CU build was attempted.

`TessellationDynamicPatchSystemSpec` contains a separate actual 2-CU,
16-lane/8-warp instruction fixture: TCS instructions produce the patch words,
TES selects a runtime vertex from TessCoord and reads via CUSTOM-1 fn33, and a
held actual read result is canceled before reuse. **This CU test has not run.**
The complete current-source build, all three domain/CU execution paths,
protected admission, original Mesa ESSL, full-capacity dynamic accesses and
same-host product qualification remain pending. Scala regression stays disabled.

Focused reproduction after an explicitly permitted source-build/resource
window (no workflow dispatch):

```sh
sbt 'testOnly opengpu.graphics.TessellationEvaluationVectorAccessSpec opengpu.system.TessellationEvaluationPatchProviderSpec opengpu.system.TessellationDynamicPatchElaborationSpec'
sbt 'testOnly opengpu.system.TessellationDynamicPatchSystemSpec'
python3 -B scripts/test_changed_test_suites.py
python3 -B scripts/test_test_selection.py
```

The bounded local checks compile current selected sources with the Chisel
plugin and a read-only dependency classpath. Their Java limits are
`-Xmx128m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m
-XX:ActiveProcessorCount=1`; component simulation also uses
`-Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0
-Dopengpu.simCache=off`, a private workspace and a 180-second process-group
limit per run. Incremental checking does not qualify unrelated frozen classes
or replace a complete source rebuild.
