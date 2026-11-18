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

Each operation uses an independent DUT reset. Delayed ACKs, reservation and
fault followed by healthy reuse on the same DUT/RAM, image/global contention,
complete resource combinations and clock/FPS remain separate acceptance gates.
This stage leaves P10 open and the alignment acceptance count at 9/40.
