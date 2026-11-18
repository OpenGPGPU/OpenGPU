# Global atomic translation

Global RV32 word AMOs capture the data SATP root and virtual operand before
sharing the ordinary data page walker. They require readable and writable
translations, retain the page offset and PTE cache policy, and hold physical
requests and attributed replies under backpressure. They do not cache
translations across tasks. Unsupported physical PPN/root bits are rejected
instead of aliasing a 32-bit physical address. Existing ISA encodings remain
unchanged.

Uncached AMOs invalidate cached aliases and wait for their acknowledgements
before reading authoritative lower memory. They retire after the lower write
acknowledgement, allocate no cache line and preserve unrelated resident ways.
Fault reports retain the original virtual instruction operand.

Twenty-three translation/walker/execute/data-TLB/CU hardware cases and five
focused L2 atomic cases pass. CU tests execute twelve single-warp kernels across
payload-guard modes with a 16-lane/eight-warp configuration. They check SATP
remapping without a cached translation, denied mappings, PTE-fetch and physical
AMO access faults, held precise traps and healthy reuse. The external atomic
responder checks CU physical requests; actual read/modify/write behavior is
covered separately by L2 tests.

All 47 L2, local-memory and payload-atomic guard cases also passed on the initial
cache-policy change. The two uncached L2 cases use four CU ports with a
16-lane/eight-warp configuration and an eight-set, two-way cache. These checks
do not qualify whole-GPU coherence or full resource capacity.

PTE-fetch access faults retain their cause through the shared walker and data
TLB; invalid or denied mappings report page faults. Both AMO fault kinds retain
the original virtual operand. This follows the distinction in the
[Sv32 translation algorithm](https://docs.riscv.org/reference/isa/v20260120/priv/supervisor.html#_virtual_address_translation_process).

The Sv32 walker also propagates a non-leaf PTE's global bit to its leaf
translation, as required by the [supervisor specification](https://docs.riscv.org/reference/isa/priv/supervisor.html).
The walker test checks all combinations of parent and leaf global bits, held
responses and successive walks; the separate TLB test checks reuse across ASID
changes and ASID-specific flushes. These two suites passed locally (12 tests):

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.core.memory.Sv32PageTableWalkerSpec opengpu.core.memory.VectorTlbSpec'
```

A subsequent focused case connects the real walker to the TLB. A global
non-leaf and non-global leaf produce a translation reused after an ASID switch
and scoped flush; a full flush forces a new walk. It passed locally:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.core.memory.VectorTlbSpec -- -z "reuse a non-leaf global walk"'
```

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'testOnly opengpu.core.memory.GlobalAtomicTranslationSpec opengpu.core.memory.SharedDataPageTableWalkerSpec opengpu.core.execute.memory.SharedAtomicExecuteStageSpec opengpu.core.memory.Sv32PageTableWalkerSpec opengpu.core.memory.VectorTlbSpec opengpu.core.GpuComputeUnitGlobalAtomicVmSpec opengpu.core.memory.SharedL2CacheSpec opengpu.core.memory.BankedSharedMemorySpec opengpu.core.memory.ShaderPayloadAtomicGuardSpec'
```

A bounded four-CU integration case connects real instruction/data caches,
Sv32 translation, alias invalidation and all four L2 banks. Its 36 kernels
exercise twelve successful cached/uncached RMWs and sixteen precise faults:
denied mappings, PTE-fetch failures and physical AMO read/write failures.
Stale private aliases must reload the updated value after uncached AMOs.
Concurrent CUs receive distinct old values; each successful AMO waits for its
actual delayed lower write acknowledgement before retiring. Held traps and
completions, unchanged neighboring words and healthy command-ID/owner reuse
are checked. Only lower memory is modeled.

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'testOnly opengpu.system.GpuSystemGlobalAtomicVmSpec'
```

This fixture retains four 16-lane/eight-warp CUs and four L2 banks, with an
eight-set/two-way L2 and four-set/one-way private caches to bound simulation
cost. It does not qualify full cache capacity, sustained atomic throughput or
whole-GPU coherence. [Word compare/exchange](WORD_COMPARE_EXCHANGE.md) adds
captured comparison operands and matching/failed CAS through the same local and
global owners. General storage/image access, wider atomics, broader
ordering/contention and complete composed timing remain open. These checks do
not close H7 or the full mobile alignment goal.

An L2 atomic write error now invalidates the affected cached line: lower memory
may have accepted the write even when it returns an error. A bounded L2 test
models that outcome, preserves an unrelated line in the same set, and verifies
that the next access refetches memory before a later atomic. It does not claim
that a failed atomic is rolled back.
Another focused case holds two L1 aliases and checks that their invalidations
complete before the failed atomic write, then refetches the memory value.
The same bounded suite checks signed and unsigned min/max against `0xffffffff`
and 10 through L2, including returned values, byte masks and preserved bytes.

Cached word AMOs also retain the carry when forming their exclusive byte end.
Previously, an operation at byte offset 60 wrote the correct lower-memory word
but left its cached copy unchanged because the six-bit end wrapped at 64.
A focused regression reproduced that stale final word (355 instead of 362)
after an acknowledged add. The widened bound now merges all four final bytes.
The existing L2 suite passes four cases, including 32 consecutive cached adds
across all sixteen word positions. It checks exact lower write masks/data,
delayed acknowledgements, returned old values and full cached-line contents
after every operation. These component results do not qualify aggregate
resource coexistence or whole-system coherence.

```sh
sbt -J-Xmx2G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'set Test / parallelExecution := false' \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "L2AtomicWriteFaultSpec.scala").contains(f.getName))' \
  'testOnly opengpu.core.memory.L2AtomicWriteFaultSpec'
```

```sh
sbt -J-Xmx5G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.core.memory.L2AtomicWriteFaultSpec'
sbt -J-Xmx5G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.core.memory.SharedL2CacheSpec -- -z AMO'
sbt -J-Xmx5G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.core.memory.L2AtomicWriteFaultSpec -- -z "drain shared L1 aliases"'
```

## Original global AMO source corpus

`scripts/compute_global_atomic_cases.py` defines 24 driver-level RV32 programs
for the existing writable-kernarg word-atomic gate. Six two-operation families
cover all ten relaxed word operations, including independently observable CAS
winners and misses. The target is at byte 0, 4092, 4096 or 8188 of an original
8-KiB kernarg, covering both sides of a page boundary and the final whole word.
Four workgroups each contain one sixteen-lane warp. Scalar AMOs execute once
per warp: there are four competing scalar callers and eight source events,
with eight distinct old-word slots selected by the original launch group ID.
The source preserves the immutable x1 root and uses bounded forward branches,
constant scalar-store offsets and a fence between each caller's two AMOs.

All 24 programs pass the compiled production validator. Their missing-feature
controls and 1,152 individual malformed AMO mutations reject, including aq/rl,
wide operations, LR, undefined addresses and writes to the immutable root.
Literal CAS histories check both winning-caller identity and every returned
old word. The source and existing atomic-order suite also pass with the native
admission helper built under ASan/UBSan. Source uploads contain only the initial
target and output guards.

These checks establish source admission, not actual GPU execution. Original
Linux submission/capture, private Sv32 mappings, complete-host returned bytes,
delayed acknowledgments, fault recovery and cached-alias behavior retain their
separate P10 acceptance gates. No generated kernel or CPU result is retained.

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_compute_global_atomic_cases.py
```

The standalone `compute_global_host_producer.py` builds the production userspace
library, the original Linux/MMU boundary and a raw `opengpu_compute()` caller.
It binds the original shader at slot 1 and an independent original kernarg at
slot 2. Shader bindings retain the production 64-byte alignment with zero
padding after the final CEASE. Original source bytes, private code snapshots,
cached executable mappings, both exact UC2 kernarg pages and the sole 8-KiB
writable grant are checked independently from captured memory and MMIO.

All 24 original Linux CPU captures pass with ASan/UBSan, followed by 432
metadata/code/PTE/write-grant rejection controls and untouched-upload rejection.
The source/order suite (18 tests) and existing counter/image/source oracle suite
(33 tests) pass; these overlapping suites are not additive counts. Fabricated
CPU byte fixtures use literal histories for all six families, checking each old
word, the final target and every other original physical byte. OR operands set
previously clear bits, and signed-min operands cross the sign boundary so those
operations have observable effects even in a group-serial execution.
The complete float-owner Linux/MMU/IRQ/cleanup CPU transport regression also
passes under ASan/UBSan; its modeled receipts are not DUT execution evidence.
All 23 software-executor source/input-closure checks pass.

Reproduce CPU capture checks inside the compiler environment, using discovery
wire/JSON from the same complete float-owner probe:

```sh
python3 -B scripts/compute_global_host_producer.py --build "$CPU/build" \
  --profile-wire "$OWNER_PROFILE_WIRE" --case all --capture-only --sanitize
python3 -B scripts/test_compute_global_atomic_source.py \
  --captures "$CPU/build/captures" --profile-json "$OWNER_PROFILE_JSON"
PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest discover \
  -s scripts -p 'test_compute_*source.py'
OPENGPU_TEST_SANITIZERS=1 PYTHONDONTWRITEBYTECODE=1 \
  python3 -B scripts/test_compute_host.py --image-float-stage
(cd scripts && PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest \
  test_compute_global_atomic_cases test_compute_global_atomic_source \
  test_compute_atomic_order test_prepare_owner_executor)
```

The complete-owner `--case global-atomics` entry binds the exact full float
executor, committed source archive and original software qualification before
starting its compiler image. It builds the three original native C units with
ASan/UBSan, discovers every actual header before compilation, and requires the
same complete `-MD` closure afterward. Exact native objects, the linker trace,
compiler/image tools, generated Linux bodies, qualified source, dependency
receipts and executable remain checked through all 24 original source owners.
This route requires no Mesa or NTT preparation. The existing controller checks
each source's independent old values/whole RAM before authorizing its real
held/retired/IRQ/PTE/context/MMU cleanup; each case resets its DUT.

All 24 guarded original Linux CPU captures and their controller READY bindings
pass, including 432 source/PTE/grant controls and eight real native compiler,
header, dependency, object, executable and link rejection controls. The 44
focused source/controller/compiler tests also pass. The complete assertion-enabled
2-CU, sixteen-lane/eight-warp float owner subsequently passed all 24 original
programs and all 192 returned scalar old words. Every case passed its caller
program order, legal shared-word serialization, final target and complete
physical-byte guards, followed by the real held/retired IRQ, PTE/context/MMU
cleanup. Each case used a fresh complete DUT/reset.

The original guarded producer, native children, controller and outer qualifier
all exited zero. Final compiler/header/link/runtime, source, executor and model
checks passed. Frozen execution source `4be83bdbc204128955339561516eb153943b5be6`
and the publication source matched all 227 qualified software files byte for
byte. The original full hardware model and assertions were retained; only the
software execution wrapper was rebuilt. Ended qualification data was removed.

```sh
python3 -B scripts/qualify_compute_owner.py \
  --executor "$COMPUTE_EXECUTOR" --executor-qualification "$COMPUTE_QUALIFICATION" \
  --executor-checker scripts/prepare_owner_executor.py \
  --executor-checker-python "$QUALIFICATION_PYTHON" \
  --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
  --case global-atomics --timeout 7200
```

These CPU captures do not prove delayed ACKs, global fault recovery,
accepted-write/error cached aliases or cross-command coherence. P10 remains
open; generated CPU data is removed after its checks end.

## Complete-owner global delayed acknowledgements

The explicit `global-atomics-delayed-ack` entry retains all 24 original RV32
programs and the complete assertion-enabled float factory. A source-owned
`OGCGACK1` plan identifies the exact physical target line and eight original
old-value slots in the same kernarg GEM. The native executor withholds the
first actual due target B response for at least 257 cycles, preserving its
original AXI identity and payload. It checks the real completion register and
all 32 old-output bytes throughout the hold. Early completion or any returned
old word before ACK release aborts without authorizing owner cleanup.

After release, the existing independent source-order/final-word/whole-physical
byte oracle and real IRQ/PTE/context/MMU cleanup still apply to every case.
The global transport has its own plan/report kind and native mode; the existing
storage v1 plan and 37-cycle contract are unchanged. Both plans use the same
actual AXI response gate, not manufactured kernel results.

Six ACK/AXI guard tests pass with native ASan/UBSan, including exact source
windows, missing observers, changed old words, malformed plans, premature
completion and unrelated writes. The 47 focused source/controller/compiler
checks and 23 software input/closure checks pass. The complete assertion-enabled
2-CU, sixteen-lane/eight-warp float DUT passed all 24 original programs and
192 returned scalar old words. Every case held its actual target B response
for at least 257 cycles with the completion bit clear and all eight original
old-value slots untouched. After release, caller-ordered old values, legal
serialization, the final target, complete physical guards and real
held/retired IRQ/PTE/context/MMU cleanup all passed. Each case used a fresh
complete DUT/reset.

The original guarded producer, native children, controller and outer qualifier
all exited zero. Final compiler/header/link/runtime, source, executor and model
checks passed. Frozen execution source `feda7c7163e8c3167cfa887d03f7ee70c17b14eb`
and the publication source matched all 227 qualified software files byte for
byte after execution. The complete hardware model, capacities and assertions
were unchanged; only the software wrapper was rebuilt. Ended qualification
data was removed.

```sh
python3 -B scripts/qualify_compute_owner.py \
  --executor "$COMPUTE_EXECUTOR" --executor-qualification "$COMPUTE_QUALIFICATION" \
  --executor-checker scripts/prepare_owner_executor.py \
  --executor-checker-python "$QUALIFICATION_PYTHON" \
  --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
  --case global-atomics-delayed-ack --timeout 7200
```

This delay gate does not qualify continuous global fault/healthy reuse,
accepted-write/error cached aliases, cross-command coherence, clocks or FPS.

## Complete-owner global physical faults and continuous reuse

The distinct `global-atomics-recovery` entry runs each of the 24 original RV32
programs through read fault, healthy, first-write fault and healthy phases.
All 96 phases share one complete assertion-enabled float DUT, RAM and initial
reset, retaining the original command ID. Original caller allocations occupy
disjoint 16-MiB physical namespaces; every previously cleaned RAM byte remains
in the independent comparison. Each phase requires actual held/retired IRQ,
PTE/context/MMU cleanup and external drain before the next original owner.
The cleanup decoder requires the raw caller's zero inactive contexts; the
existing image/storage capture contract retains exactly one inactive context.

The original production `opengpu_compute()` caller consumes failed receipts
and requires its real negative fence and userspace `EIO` result. Read faults
permit no RAM changes or target writes. A one-shot lower B error suppresses
the first target write. The grid stops creating offers on failure and drains
previously admitted work, including a registered offer. Successful callers
must form an admitted group prefix with exactly one failed initial caller
(group zero or one); failed and unissued callers retain both guarded slots.
Each survivor returns both source-ordered old words. Those words must have a
legal shared-word serialization and final target, with every other physical
byte unchanged. The target write count must equal one plus the returned old
word count, including the failed first attempt and lower writes for CAS misses.
The existing storage first-write contract retains its separate five attempts.

Seventy focused source/controller/compiler checks and existing atomic/source
regressions pass. CPU controls cover all source layouts, both initial failed
callers and each admitted prefix, legal interleavings, source-order rejection,
prior RAM guards, exact arguments and counters, and cleanup refusal after bad
partial results. The 23 source/input-closure regressions also pass.

All 96 phases passed on the complete assertion-enabled float DUT with one RAM,
initial reset and original command ID. The 48 healthy phases returned 384
source-ordered old words. The 24 suppressed-write failures returned 48 more:
each drained one surviving caller, returned both old words and made exactly
three target attempts, including the suppressed first write. Failed and
unissued callers retained their guarded slots. All final targets, current and
previous physical-byte guards, actual negative fences/userspace `EIO`, held
and retired IRQs, PTE/context/MMU cleanup and external drains passed. The 24
read-fault phases changed no RAM bytes; cumulative physical/injected counters
agreed at 48 read errors and 24 write errors.

The original guarded producer, native children, controller and outer qualifier
all exited successfully. Final compiler/header/link inputs and software/model
checks passed. Frozen execution source
`f18705382184deb986e52c12691be84a8518b47c` and publication matched all 231
qualified software files byte for byte. This qualifies 432 actual returned
old words and complete continuous fault/healthy cleanup for this corpus.

```sh
python3 -B scripts/qualify_compute_owner.py \
  --executor "$COMPUTE_EXECUTOR" --executor-qualification "$COMPUTE_QUALIFICATION" \
  --executor-checker scripts/prepare_owner_executor.py \
  --executor-checker-python "$QUALIFICATION_PYTHON" \
  --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
  --case global-atomics-recovery --timeout 7200
```

The injected write is suppressed, so this gate makes no rollback claim for
an accepted write followed by an error. Accepted-write/error cached aliases,
cross-command coherence, clocks and FPS remain separate qualifications. P10
remains open.

## Complete-owner accepted first-write errors

The distinct `global-atomics-accepted-write-error` entry uses all 24 original
RV32 programs. Each runs a committed first-write error followed by a healthy
owner: 48 phases share one complete float DUT, retained RAM, original command
ID and initial reset. Original allocations occupy disjoint physical namespaces;
each phase checks all previously cleaned physical bytes as well as its uploads.

The physical AXI agent applies the original enabled W bytes after both AW and
WLAST are accepted, then returns the delayed failing B response. It counts
committed errors independently of target attempts and response errors. Other
writes succeed; rearming the existing suppressed-write mode restores its
original policy while preserving cumulative counters. The original caller
checks its failed fence and userspace `EIO`, then performs real retirement and
PTE/context/MMU cleanup before the next original healthy owner.

The failed caller returns neither old-value slot, but its first AMO has changed
the target before surviving callers execute. The independent oracle starts
their source-ordered serialization from that committed first result; a
rollback history is rejected. Surviving callers must match the admitted group
prefix, and their old-word count must bind exactly to physical target attempts.
Every other physical byte remains guarded.

Seventy-four focused source/controller/qualifier checks and 26 input/closure
checks pass. CPU fixtures cover both initial failed callers, all admitted
prefixes and source layouts, interleavings, literal old/final values, rollback
and source-order rejection, whole-RAM guards and cleanup refusal. Native
ASan/UBSan covers 26 AXI cases, including W-before-AW, byte enables, independent
read snapshots before a delayed/stalled failing B, later healthy writes and
rearming without commit or counter leakage.

All 48 phases passed on the complete assertion-enabled float DUT with one
RAM, initial reset and original command ID. The 24 healthy phases returned
192 source-ordered old words, and the 24 committed-write failures returned
48 more, for 240 actual old words. Every failure made three target attempts:
one committed write returning an error, then both AMOs of the surviving
caller. Failed and unissued old slots stayed guarded. All committed-first
histories, final targets, current/prior physical-byte guards, actual negative
fences/userspace `EIO`, held/retired IRQs, PTE/context/MMU cleanup and external
drains passed. Cumulative physical/injected/accepted write errors agreed at
24; no physical or injected read errors occurred.

The original guarded producer, native children, controller and outer qualifier
all exited successfully, including final compiler/header/link, software source
and model checks. Frozen execution source
`066de7f1e6608e26ed7550bb072e4e166ca142ee` matched all 233 qualified software
files in publication byte for byte. No RTL, capacity or assertion changes were
needed.

```sh
python3 -B scripts/qualify_compute_owner.py \
  --executor "$COMPUTE_EXECUTOR" --executor-qualification "$COMPUTE_QUALIFICATION" \
  --executor-checker scripts/prepare_owner_executor.py \
  --executor-checker-python "$QUALIFICATION_PYTHON" \
  --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
  --case global-atomics-accepted-write-error --timeout 7200
```

Cached-alias behavior and cross-command coherence retain their separate
complete-host qualification. P10 remains open.
