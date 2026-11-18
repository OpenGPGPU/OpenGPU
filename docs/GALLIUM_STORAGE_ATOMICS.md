# Gallium checked storage atomics

The bounded compute emitter lowers real Mesa TGSI integer SSBO atomics to the
existing checked `GSB1` word-atomic instructions. Exchange, add, xor, or, and,
signed/unsigned minimum and maximum, and compare-swap return the old word.
Each access retains the descriptor bounds, read/write permissions, task
identity and captured address space. It does not use an unchecked global AMO.

Atomic compute states and launches require both discovered storage and storage
atomic driver features. The target binding must be writable. The emitter
tracks both reads and writes, stages all operands before writing the result,
and admits exactly three TGSI sources, or four for compare-swap. Mesa's actual
SSBO binding remapping is retained through the state tracker and submission.

This lowering accepts straight-line scalar 32-bit buffer operations, with up
to four native bindings. Shared-memory, image and hardware-counter atomic
sources, conditional atomics, malformed source counts and undeclared resources
are rejected without publishing a partial program. Other resource types do
not acquire SSBO semantics. No compute API capability or GLES version is
enabled by this change.

## Validation and limits

The checked SSBO word-RMW lowering accepts `TGSI_MEMORY_COHERENT`. It uses
the same acknowledged uncached atomic service and shared-cache invalidation as
plain word atomics; it does not add an unchecked access or a new instruction.
Volatile, restrict and combinations containing either remain rejected without
publishing a partial program. Ordinary coherent SSBO loads and stores remain
unsupported.

The actual Mesa CPU regression passes ASan/UBSan. All fourteen compiled
programs preserve every native instruction and reflection byte when their
atomic token gains the coherent qualifier, while all 56 unsupported qualifier
mutations reject. This includes the eleven operation/CAS recipes, dynamic UBO
CAS inputs and both endpoint recipes. Reproduce with
`scripts/test_gallium_compute_atomics.py --build .artifacts/atomic-active --sanitize`
in the configured environment below. These are compiler and CPU checks;
complete-host mixed image/storage ordering remains a separate P10 gate.

Real Mesa 22.3.6 ESSL310 → NIR → TGSI → emitter → kernel word validator and
real state-tracker/callback submission pass ASan/UBSan. The CPU oracle checks
96 returned old words, all ten operations, matching/nonmatching compare-swap,
wrapping and signed/unsigned boundaries, dynamic UBO comparisons and replacement
values, TEMP destination/operand aliases and unchanged guard bytes. Missing
feature combinations and a read-only target reject before submission. Mixed
shared-memory/buffer declarations and MEMORY, IMAGE and HW_ATOMIC sources
remain rejected; opcode-specific source-count failures leave output untouched.

The actual native words also pass an explicit two-CU `GpuSystem` qualification
using the real checked resource clients, Sv32 translation and four-bank L2
RMW path. Its external model supplies only memory bytes, PTEs and delayed
responses; it does not implement atomics. Both Bare and Sv32 runs cover all
ten operations, eight contending invocations, exact returned-old multisets,
37-clock delayed write responses, request backpressure and byte masks. Each
operation runs healthy, with a failed first lower write, and healthy again
without reset. Held traps/completion, retained leases, new generations and
command-ID reuse pass. Additional cases cover dynamic UBO compare-swap,
read-only descriptors, a denied Sv32 write, the final word of a 128 MiB view
ending at 4 GiB, and rejection of the following word. The complete run passes
75 root commands, 32 precise traps and 494 atomic lower-write attempts.

The existing callback corpus, full 16 KiB shared-memory frontend and compute
memory-barrier frontend are separate regression checks. The RTL fixture uses
four lanes and two warps per CU with small caches. It does not establish full
product capacity, performance, arbitrary simultaneous resource-client offers,
general cross-domain/API ordering, image/shared/counter atomic lowering,
graphics-stage atomics or full API resource namespace remapping. H7 remains
open.

## Reproduction

Use matching configured Mesa 22.3.6 objects and the checked uniform-index
lowering source. The scripts build privately without changing Mesa:

```sh
export OPENGPU_MESA_DIR=/path/to/mesa-22.3.6
export OPENGPU_MESA_BUILD=/path/to/matching-mesa-build
export OPENGPU_DRM_INCLUDE=/path/to/drm-headers
python3 scripts/test_gallium_compute_atomics.py \
  --build .artifacts/atomic-active --sanitize

sbt -J-Xmx2G -J-XX:ActiveProcessorCount=2 -Dopengpu.productQualification=0 \
  -Dopengpu.galliumStorageAtomicQualification=1 \
  -Dopengpu.galliumStorageAtomicInputs=.artifacts/atomic-active/glsl \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit \
  'set Test / parallelExecution := false' \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "QualifyGalliumStorageAtomic.scala").contains(f.getName))' \
  'Test/runMain opengpu.system.QualifyGalliumStorageAtomic'

python3 scripts/test_gallium_compute.py
python3 scripts/test_gallium_compute_shared.py --build .artifacts/shared-active --sanitize
python3 scripts/test_gallium_compute_barriers.py --build .artifacts/barrier-active --sanitize
python3 scripts/test_test_selection.py
```

The RTL runner requires the explicit property and actual compiler inputs;
it is excluded from default discovery. Missing inputs fail this explicit run.
Remove private build directories and simulation outputs when their active
validation use ends; retain source and these reproduction commands only.

## Complete-owner storage-atomic stage (P10)

All seventeen original Mesa storage-atomic source cases pass on the complete,
assertion-enabled float-stage host: two 16-lane/eight-warp CUs, the full
112-word factory contract and ten auxiliary registers, and unchanged supported
RTL capacities. Each case compiles through Mesa and uses the original
production submission, GEM objects, checked descriptors, PTEs and executable
code. One guarded compiler build serves the sequential matrix.

The fifteen successful cases cover all ten integer operations, signed and
unsigned limits, wrapping add, matching/nonmatching CAS, lane-dependent
exchange/add/CAS and the last word of an 8192-byte view. Eight invocations in
two workgroups contend on one word. An independent source-level oracle finds
a legal serialization consistent with every invocation's returned old value
and the final word; all 120 returned words pass. The read-only and following-word
cases report actual checked execution failures with no physical bus error or
memory write. These are hardware view checks, rather than pre-enqueue rejects.

Every case passes whole-physical-memory guards, original held/retired receipts,
IRQ handling, actual PTE/context/MMU cleanup and executor exit zero. The original
Mesa producer also exits zero; final compiler, source, executable and model
binding checks pass. The separate CPU compiler/admission/capture matrix passes
all seventeen cases, and 241 focused protocol, oracle, controller, executor and
test-selection checks pass. The old-value oracle's negative tests run in host CI.

The exact execution source is `e82c0cfb9051a8f6d31bddcd13ec0f43288edbc0`.
Publication preserves all 202 qualified software input files byte for byte.
Its hardware input contents match all 1960 original preparation inputs apart
from two upstream Scala comment changes; the intervening Scala directory move
changes paths only. Existing executors retain their original checkout binding.
Reproduction requires a normally prepared and source-qualified complete float
compute executor from the same checkout:

```sh
owner_dir="$PWD/.artifacts/p10-owner"
env -u SDKROOT -u CPATH PYTHONDONTWRITEBYTECODE=1 \
  /usr/bin/python3 -B scripts/qualify_compute_owner.py \
  --executor "$owner_dir/executors/compute/executor" \
  --executor-qualification "$owner_dir/executors/compute/qualification.json" \
  --executor-checker "$PWD/scripts/prepare_owner_executor.py" \
  --executor-checker-python /usr/bin/python3 \
  --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
  --case storage-atomics --timeout 3600
(cd scripts && /usr/bin/python3 -B -m unittest \
  test_compute_atomic_order test_compute_owner_oracle test_compute_owner_controller \
  test_qualify_compute_owner test_prepare_owner_executor \
  test_prepare_owner_verilator_executor test_changed_test_suites)
```

Each operation uses an independent DUT reset. The delayed-ACK extension is
qualified below. Reservation and fault followed by healthy reuse on the same
DUT/RAM, image/global contention,
complete resource combinations and clock/FPS remain separate acceptance gates.
This stage leaves P10 open and the alignment acceptance count at 9/40.

## Complete-owner delayed ACK execution (P10)

The seventeen-case matrix also passes with actual target write ACKs withheld
on the same complete float-stage factory. For each of the fifteen successful
cases, the controller derives one physical 64-byte line from the independently
validated original GEM and atomic offset. The native executor gates a real due
AXI B response for at least 37 cycles. Public unified-status reads throughout
the hold, including the final read before release, show no kernel completion.
The existing gate retains response identity through stalls; normal completion
requires release and actual external drain.

All 120 invocation old values, final atomic words and whole-RAM guards pass
after release. The two original read-only/bounds cases retain their no-write
failure checks. Every original producer, receipt, actual owner cleanup,
executor exit and final compiler/source/model binding check passes. Each case
still uses an independent reset; this establishes the delayed-ACK gate rather
than continuous fault recovery or cross-domain ordering.

The execution source is `d6ec42be658c0c5b69775e79f4dc344cac8b1410`.
Publication preserves all 206 qualified software input files; hardware
provenance has the same path/comment-only differences described above.
The 251 focused checks pass, including native gate, premature-completion,
wrong-target, missing-ACK and stalled-response checks under ASan/UBSan.
Original indirect CPU transport and continuous-phase compatibility also pass.
Invalid delay receipts cannot authorize byte acceptance or owner cleanup.

Use a matching, newly source-qualified complete float compute executor:

```sh
owner_dir="$PWD/.artifacts/p10-owner"
env -u SDKROOT -u CPATH PYTHONDONTWRITEBYTECODE=1 \
  /usr/bin/python3 -B scripts/qualify_compute_owner.py \
  --executor "$owner_dir/executors/compute/executor" \
  --executor-qualification "$owner_dir/executors/compute/qualification.json" \
  --executor-checker "$PWD/scripts/prepare_owner_executor.py" \
  --executor-checker-python /usr/bin/python3 \
  --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
  --case storage-atomics-delayed-ack --timeout 3600
(cd scripts && /usr/bin/python3 -B -m unittest \
  test_compute_storage_atomic_delay test_qualify_compute_owner test_compute_owner \
  test_compute_owner_oracle test_compute_owner_controller test_prepare_owner_executor \
  test_prepare_owner_verilator_executor test_compute_atomic_order test_changed_test_suites)
/usr/bin/python3 -B scripts/test_compute_indirect_capture.py
```

The continuous storage fault/reuse extension is qualified below. RV32 global
AMOs and image/storage contention retain their separate P10 acceptance gates.
Acceptance remains 9/40.

## Complete-owner physical faults and continuous reuse (P10)

All sixty original Mesa phases pass on one assertion-enabled complete float
DUT, one retained RAM and one execution reset. The original command ID is 3
throughout. Each of the fifteen successful storage programs runs this sequence:

| Phase | Cases | Observed result |
|---|---:|---|
| Target read-response fault | 15 | Failed original command; no writes; every RAM byte unchanged |
| Healthy reuse after read fault | 15 | Eight exact old words and final atomic word |
| First target write-response fault | 15 | Five actual target write attempts, one B error, four exact old words from the healthy workgroup |
| Healthy reuse after write fault | 15 | Eight exact old words and final atomic word; prior error history retained |

The matrix checks 300 returned old words, 30 actual injected read errors and
15 actual injected write errors. It covers all ten operations, signedness,
wrapping, CAS match/miss and invocation identity, lane-dependent operands and
the last word of the original 8192-byte view. The independent oracle permits
exactly one healthy four-invocation workgroup in each write-fault phase and
finds a legal source-level serialization for its old values and final word.
The failed workgroup's output words and every other physical byte stay guarded.

One guarded compiler build and producer serve all sixty original C source
owners. Each uses fresh disjoint original GEMs. Native execution and independent
checks retain all earlier RAM, cumulative read/write fault counters, command
identity and the execution clock. Actual held/retired IRQ receipts, PTE
revocation, context/MMU cleanup and quiet state precede every next upload.
The capture reader rejects unified reset and unrelated command opcodes.
The original producer and native executor exit zero; final original compiler,
source, executable and model binding checks pass.

The controlled B-error provider waits 37 cycles and rejects the selected write
before committing it to backing memory. The other workgroup can still commit
its four operations. This qualifies that explicit failure model; a write that
commits before reporting an error and cached-alias behavior retain separate
full-host gates. Component accepted-write/error coverage is described in
[global atomic translation](GLOBAL_ATOMIC_TRANSLATION.md).

Execution source: `f521d4d5bed6a73ce9bcf71e9613a551d4f59045`.
Publication preserves all 211 qualified software inputs. All 1960 hardware
input contents have the same path/comment-only differences recorded above;
RTL and supported capacities are unchanged. The 259 focused CPU checks pass;
60 targeted checks also pass after precise target-attempt accounting.
All 25 native AXI cases pass ASan/UBSan, including one-shot delayed B errors,
W-before-AW, stable stalled error ownership and later healthy responses.
Original indirect CPU transport/continuous-phase compatibility and current
layout test selection pass.

Use a matching source-qualified complete float compute executor:

```sh
owner_dir="$PWD/.artifacts/p10-owner"
env -u SDKROOT -u CPATH PYTHONDONTWRITEBYTECODE=1 \
  /usr/bin/python3 -B scripts/qualify_compute_owner.py \
  --executor "$owner_dir/executors/compute/executor" \
  --executor-qualification "$owner_dir/executors/compute/qualification.json" \
  --executor-checker "$PWD/scripts/prepare_owner_executor.py" \
  --executor-checker-python /usr/bin/python3 \
  --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
  --case storage-atomics-recovery --timeout 7200
(cd scripts && /usr/bin/python3 -B -m unittest \
  test_compute_storage_recovery test_compute_storage_atomic_delay test_qualify_compute_owner \
  test_compute_owner test_compute_owner_oracle test_compute_owner_controller \
  test_prepare_owner_executor test_prepare_owner_verilator_executor \
  test_compute_atomic_order test_changed_test_suites)
/usr/bin/python3 -B scripts/test_compute_indirect_capture.py
```

P10 remains open for RV32 global AMOs, accepted-write/cached-alias failures and
image/storage competition. Complete resource combinations, cross-domain
coherence and clock/FPS retain their separate plan gates. Acceptance stays 9/40.

## Complete-owner committed write errors and continuous reuse

The distinct `storage-atomics-accepted-write-error` entry uses all 15 original
successful Mesa storage programs. Each runs a committed first-write error
followed by a healthy owner: 30 phases share one complete assertion-enabled
float DUT, RAM, initial reset and original command ID. Original allocations
occupy disjoint physical namespaces and retain every previously cleaned byte.
The original eight invocations use two workgroups of four; the full model
retains two sixteen-lane/eight-warp CUs.

The physical AXI agent applies the original enabled W bytes, then returns the
delayed failing B response. One AMO from the failed workgroup has committed;
that group returns no old values, while the other group's four invocations
finish. Their exact old values and final target must serialize after a legal
first AMO from the failed group. Invocation order within that group is not an
API guarantee, so the oracle checks all four possible original identities.
The five physical target attempts bind to that first write and four surviving
AMOs, including lower writes for CAS misses. Every other physical byte is
guarded. A missed CAS writes the existing value: data alone cannot distinguish
its accepted and suppressed histories, so independent native
physical/injected/accepted counters remain mandatory.

Each phase requires actual failed fence/userspace `EIO` or healthy success,
held/retired IRQ, PTE/context/MMU cleanup and external drain. The capture keeps
the storage owner's one inactive context; global raw owners still require
zero. Original Mesa source, compile/link dependencies and the exact ordered
30-phase producer arguments remain bound. The existing suppressed-write and
read-fault matrix retains its separate mode and receipts.

Ninety-one focused source/controller/qualifier checks and 26 input/closure
checks pass. CPU controls include all 2,880 failed-invocation/successful-group
permutations, literal values, failed-group identity, meaningful rollback and
full-RAM negatives, no-op CAS accounting, and cleanup refusal. Full-DUT
execution still must establish all 30 phases, 180 returned old words, actual
cleanup and final compiler/source/model checks.

```sh
python3 -B scripts/qualify_compute_owner.py \
  --executor "$COMPUTE_EXECUTOR" --executor-qualification "$COMPUTE_QUALIFICATION" \
  --executor-checker scripts/prepare_owner_executor.py \
  --executor-checker-python "$QUALIFICATION_PYTHON" \
  --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
  --case storage-atomics-accepted-write-error --timeout 7200
```

Image accepted-write errors, cached aliases and cross-command coherence retain
their separate complete-host gates. P10 remains open.
