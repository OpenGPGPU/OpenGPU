# Dynamic TES patch reads (P40 source)

The opt-in `evaluationPatchAvailable` path supplies TES reads from the actual
owned `TessellationPatchContext`. It serializes each selected vector lane over
that context's inspection port; it does not expand the 61-word static input
snapshot or accept patch values from a CPU. The option defaults to false in
all constructors. No default host factory, discovery word, serialized GNT
record or protected-program admission is enabled by this change.

## Internal instruction and launch contract

This instruction belongs to the checked stage-5 program version 2.
Old program version 1 must continue to reject it. The internal launch binding
has its own **binding version 1**, which is not a program-ABI version.

| Field | Contract |
| --- | --- |
| Instruction | `opengpu.vtes.patch.read vd,vs1,vs2[,v0.t]`; CUSTOM-1 opcode `0x2b`, bits 31:26 = 33, funct3 = 0; bit 25 retains ordinary vector mask semantics |
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
replies. A failed/canceled instruction suppresses writes before its commit is
offered. Once commit is offered, Decoupled stability keeps its payload unchanged;
a later cancellation relies on actual parent kernel/frame cancellation and
retirement to suppress publication. Normal retirement
uses the real vector commit arbiter and fault path. If physical kernel
retirement has already been proven, only the orphaned commit is discarded;
its patch traffic must still drain. CU completion, shared-owner completion and
frame retirement remain blocked while this work or an inspection credit is
outstanding. A provider fault is retained through subsequent abort draining.

Input publication replies (context source 0) return directly to the original
publisher; only inspection replies (source 2) consume inspection arbitration
credit. This applies to all three dynamic shared evaluators. See the
[input reply routing results](TESSELLATION_INPUT_REPLY_ROUTING.md) for the
actual patch-store regression and original-source negative controls.

The new mode selects `zeroOnBegin=true` along all three real hierarchies:
SharedEvaluator -> CuPatch -> ControlWarpScheduler -> PatchContext ->
PatchStore. It initializes the current legal input/output ranges and six levels
before opening the patch, using each bank's original write port. Legacy mode
keeps its false default. Dynamic mode rejects a nonzero old static selection
count or selection array; fn32 still supplies TessCoord components 0..2.

## Native admission and remaining connection

This stage allocates no new magic, word offsets or MMIO bits. Previously
delivered GNQ/GNT source BUILD records retain their contracts. Runtime
connection must still derive all of the following from their accepted owners:

- Independently verified stage-5 program version 2 and dynamic-read capability;
  exact original code/entry/RX extent and the retained TES resource roots.
- Actual task identity, primitive sequence and legal coupled patch layout.
  These must derive from the accepted owned patch, not shader operands.
- Explicit choice of dynamic reads versus the frozen legacy static selections.
- Independent TCS/TES checked uniform roots and scope bounds from P15/GUR3.
- P39's original VS program, original draw/index/attribute views, slice ordinal
  and instance range, component mapping, and acknowledged patch publication.
  A precomputed CPU patch cannot establish that provenance.

The compiler and independent P16 validator now implement the internal v2
contract described below. CPU BUILD can retain versioned original programs, but the public getter,
queue and factory route for this execution service remain closed. A built
source graph does not prove that the shader executed.

## Staged source validation and limits

The exact P40 delivery freshly compiled all 771 production and all 1116 test
Scala sources with Scala 2.13.18/Chisel 7.15.0, in 88.873/174.177 seconds.
There was no old GPU class provider. The source union preserves the four
previously registered joint-metadata suites; the new load-reply fixture uses
this stage's existing interfaces and does not require a future language-ID
binding before seal.

All fourteen component/structural cases passed in 113.212 seconds: launch
contract, decoder/vector service, owner/provider, legacy/dynamic initialization
in all three domains, and actual source-0 load replies. They cover full-width
indices, stage/version/coupled bounds, held requests/commits, cancellation,
inspection ownership and no-reset reuse. The launch guard still raises actual
RTL assertion failures for missing geometry and multiple invocations.

The actual-CU test also passed in 235.986 seconds, including model generation
and native build. `TessellationDynamicPatchSystemSpec` contains two physical
CUs with 16 lanes/eight warps and compact caches. Literal TCS instructions
produce the tested patch values; TES obtains its runtime vertex from generated
TessCoord and reads CUSTOM-1 fn33 through the actual context/provider/CU path.
Both expected primitive vertex words (`0x1003`/`0x1103`), held-result
cancellation/drain, held completion and healthy reuse pass without reset.
Neither a patch result nor a CU completion is injected. The model contains two
CUs; this fixture does not establish that both were used, full cache capacity,
all-warp activity or complete 32-vertex/full-component CU accesses.

The launch assertion uses a port-only contract module. The former direct
internal signal reference prevented Verilator hierarchical builds; the contract
preserves its conditions while keeping assertions enabled inside that boundary.
All tests use fresh classes, enabled assertions, native one-job builds and no
executable/object caches. The CU build uses `GpuComputeUnit` hierarchy and
`opengpu.simLowMemOpt=1`; component builds use optimization zero.

Production/test compilation had separate 600-second/6144-MiB sampled aggregate
bounds (observed 3534992/4266016 KiB). Components used 600 seconds/1536 MiB
(observed 669072 KiB); the actual CU case used 1200 seconds/3072 MiB with a
1536-MiB JVM heap (observed 2334880 KiB). Sampled RSS is not an absolute peak.
Source/tool/HEAD/index checks passed before and after each command. Ended
classes, mutants, models and logs are removed after consumer checks.
All 157 affected-suite selector checks and integration/large-PR catalog checks
pass. The unchanged evaluator and load-reply sources also pass the three
original-routing semantic controls described in the
[input reply guide](TESSELLATION_INPUT_REPLY_ROUTING.md).

This source/limited-ISA stage does not qualify original Mesa execution, the
complete three-domain CU chain, full-capacity dynamic accesses, native
protected admission, the final host profile or the two-CU VGA60 product.
P39/P40 and the whole alignment target remain open.

Reproduce with the repository toolchain; do not dispatch Scala regression:

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=1 compile 'Test / compile'
sbt -J-Xmx768m -J-XX:ActiveProcessorCount=1 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simCache=off -Dopengpu.simCcache=off \
  'testOnly opengpu.core.TessellationEvaluationKernelContractSpec opengpu.graphics.TessellationEvaluationVectorAccessSpec opengpu.system.TessellationEvaluationPatchProviderSpec opengpu.system.TessellationDynamicPatchElaborationSpec opengpu.system.TessellationSharedLoadReplySpec'
sbt -J-Xmx1536m -J-XX:ActiveProcessorCount=1 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simCache=off -Dopengpu.simCcache=off \
  -Dopengpu.productQualification=0 \
  'testOnly opengpu.system.TessellationDynamicPatchSystemSpec'
python3 -B scripts/test_changed_test_suites.py
python3 -B scripts/test_test_selection.py
git diff --check
```

The independent LLVM candidate `codex/p40-tes-patch-isa` at `cc37fd175`
contains the matching fn33 MC declaration. Its existing 21 RUN/11-file MC
result retains its documented scope; no fresh MC or production-tool migration
is claimed for this GPU delivery.

## Real Mesa dynamic TES compiler

The opt-in `opengpu_native_tess_dynamic_reflect/emit` API accepts only stage 5,
program version 2 and a legal captured `tes_patch_layout`. Its five fields describe
TCS input/output/per-patch storage; the separate `output_components` field still
describes TES exports. Both the static selection count and all 61 entries stay
zero. The old reflect/emit and private-context APIs retain their v1 contract and
do not admit dynamic programs. TCS remains program version 1.

The real linked Mesa 22.3.6 frontend copies NIR variable spans after assigning IO
locations and before `nir_to_tgsi` discards ArrayID. The owned metadata records
original variable/matrix/array boundaries and semantic packing, excluding the
outer per-vertex array. The compiler cross-checks it with actual TGSI declarations;
it never merges adjacent variables merely because they have the same semantic
kind. These spans describe compiler semantics and grant no kernel permission.

The emitted fn33 descriptor checks the full original ADDR value before accepting
shift/add calculations. An out-of-range matrix-column or patch-array offset
produces an invalid high-bit sentinel rather than borrowing an adjacent variable
or wrapping into a legal descriptor. Vertex offsets receive the same full-width
check. Bounds checks are reused only within one TGSI instruction while their
physical scratch value remains valid. The service independently checks every
received full-width vertex/descriptor against its captured layout.

Actual Mesa lowers GLSL dynamic vector-component selection to ISLT/UCMP selection
trees after loading the vector. The v2 emitter preserves that real TGSI behavior;
this is not a direct dynamic-component token or an additional guarantee for GLSL's
undefined out-of-range vector indexing. Three-source UCMP remains rejected by the
legacy v1 parser.

Mesa also emits compact tessellation-level backing placeholders whose numeric
slots overlap real per-patch variables. The explicit TCS `control_emit` API may
normalize them only when the actual NIR compact declaration proves the canonical
four outer or two inner components. Wrong compact kind/index/extent/mask, real
semantic overlaps and unproven placeholders fail. Semantic inventory is 64 slots;
physical TEMP/ADDR/VGPR limits, 512 instruction words, 32 vertices, 64 components,
120 per-patch words and the coupled 2048-word SRAM limit are unchanged.

Final real ESSL compiler results (TCS/TES instruction words):

| Case | Captured input vertices/components; output vertices/components; per-patch | Words |
| --- | --- | --- |
| Dynamic vertex, matrix column and per-patch array | 2/4; 2/12; 8 | 300/239 |
| Adjacent independent matrices and patch arrays | 2/4; 2/20; 16 | 427/354 |
| Large coupled layout and dynamic 30-slot patch array | 32/4; 32/60; 120 (2040 total) | 360/433 |
| Exact full bank | 32/4; 32/64; 0 (2048 total) | 288/360 |
| Dynamic vector component lowered by Mesa | 2/4; 2/4; 0 | 151/154 |

All cases export eight TES components. These tests prove original ESSL compilation,
declared capacities and code/metadata construction, not execution of any shader
or correct values from unwritten patch components. They include actual token
truncations, wrong v2 permission/version, forged/overlapping span metadata and
output-unchanged-on-error checks. The original v1 uniform/full/conditional ESSL
cases also passed through the same current frontend, with their original
228/149, 292/201 and 340/149 instruction counts. The small ASan/UBSan parser test also checks old
VS/TCS/TES isolation, unused metadata tails and the illegal 2049-word combination.
A first-instruction UCMP literal TGSI regression requires explicit result-scratch
initialization; it cannot inherit old-vd definedness from a previous compare.
The strict independent validator rejected the prior uninitialized form, so the
emitter now initializes each select from its already-defined false operand.
No validator guard was relaxed. The original P16 validator/RX/MMU consumer
accepted all five final original-byte pairs with their exact nested layouts,
TES exports eight and zero static words. It also accepted the initialized
76-word literal UCMP program under its separate exports-four profile. These
checks neither interpret instructions nor prove shader results.

Validation reused immutable configured Mesa libraries in a network-disabled,
read-only-source container with one CPU and combined memory/swap capped at 512MiB.
The frontend's 24 translation-unit MMD dependencies plus the alternate legacy
frontend test unit (25 MMD files total), generated source and binaries,
GPU sources and 8101 external Mesa/DRM/library inputs were checked around execution.
Only GPU/frontend units needing changed source/header inputs were rebuilt; Mesa
libraries were not rebuilt. This is a CPU compiler/source result, not a DUT run.

Reproduce with actual configured Mesa 22.3.6 libraries and DRM headers:

```sh
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_tessellation_uniform_emit.py
python3 -B scripts/test_tessellation_uniform_frontend.py --dynamic --sanitize --build .artifacts/p40-front-end
python3 -B scripts/test_tessellation_program_snapshot.py --sanitize --programs .artifacts/p40-front-end/programs/edge --tcs-layout 32 4 32 64 0 --tes-layout 0 8 --tes-program-version 2 --tes-patch-layout 32 4 32 64 0
```

The frontend command is opt-in and needs the immutable Mesa/DRM/link environment
and bounded container recipe in [the static frontend instructions](TESSELLATION_MESA_UNIFORMS.md),
adding `--dynamic` and choosing a fresh output directory. Remove its outputs after consumers finish. The
independent validator/RX test does not interpret or execute instructions. The LLVM
MC declaration uses the mnemonic above; complete new-tool roundtrip remains a
separate qualification. Real three-domain CU execution, native submission/ABI
assignment, original VS-to-patch provenance and final host qualification remain
uncompleted dependencies.


Source dependencies include the two original P17 compiler/resource-plan commits
and the P16 validator/RX commits `7f07da63a`, `56a0d9451`, `e031887c1` and
`41de38440`. Their cherry-picks preserve provenance. The unrelated complete
`PROTECTED_TESSELLATION_OWNER.md` and absent P16 builder test were not imported
into this compiler-only dependency tree; the original P16 topic retains them.
This does not register the complete P16 Linux/native submission path.
