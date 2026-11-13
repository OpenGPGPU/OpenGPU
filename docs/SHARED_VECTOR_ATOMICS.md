# Per-invocation shared word atomics

This opt-in path gives each executing invocation a distinct atomic operation
and old-word result. Scalar RV32A remains once per warp. CPU admission/lowering,
the decoder, real SRAM owner, stock-CU tests and all sixteen straight-line ESSL
endpoint programs pass their gates. The bounded-address/SIMT extension below
still requires its separate actual ESSL and CU gates. This does not close H7 or replace complete-host
execution.

The operations follow GLSL ES 3.10 section 8.11: exchange, add, XOR, OR, AND,
signed min/max, unsigned min/max and compare/swap on 32-bit shared words.
Signedness affects min/max; CAS captures comparison and replacement separately.
All operations return the original word. See the
[GLSL specification](https://registry.khronos.org/OpenGL/specs/es/3.1/GLSL_ES_Specification_3.10.pdf).

## Encoding and ownership

The instruction family uses CUSTOM-1 (`0x2b`), separate from scalar atomics and
CUSTOM-0 geometry. Function 30 with funct3=0 and rd=0 captures per-lane
replacement (`vs1`) and comparison (`vs2`) values. Function 31 selects operations
0–7 with funct3; function 32 selects operations 8–9 with funct3=0–1. Operations
use scalar rs1 as the shared base and vector rs2 as byte offsets. An rs2 field
of zero means literal zero offsets, enabling same-word contention. The owner
forms each address with 33-bit addition, checks alignment and the complete word
against the configured SRAM interval, and never falls back to global memory.

Parameter validity is per warp and lane, reset at warp-slot initialization.
The owner captures operands, active/execution/helper masks and old destination
lanes at acceptance. It serializes each selected lane through the existing SRAM
atomic engine. A round-robin arbiter shares that engine with unchanged scalar
AMOs. Offered requests cannot be withdrawn on cancellation; accepted requests
must drain before commit. Earlier acknowledged lanes may have side effects on
a later fault. Faults suppress vector writeback and retain the failing lane and
address. This is precise-instruction retirement, not group rollback.

Vector issue retains normal source/destination scoreboard reservations and
warp blocking through commit. Shared memory is not idle while this owner is
busy, including between individual lane requests. Other resource clients keep
their existing instruction, ownership and fault paths.

`GpuConfig.enableSharedVectorAtomics` defaults to false. Only an executing
configuration with this option reports GCR1 flag bit 18. Linux feature bit 41
requires that bit, unified commands and validated shared discovery. Bit 40 is
reserved for protected native submission. Existing validator entry points and
native/graphics profiles reject the new instructions; Linux uses a separate
explicit shared-atomic gate. No current default product profile is enabled.

## Compiler and admission limits

The shared TGSI path handles constant shared offsets and trusted invocation
offsets (`localID * 4 + constant`). Atomic reads require a barrier-visible
initialized prefix or a retained exact invocation-local store stripe. A store
prefix alone does not prove communication. Atomics do not create initialization
proofs or publish a prefix.

The bounded-address extension also admits canonical forward if/else AMOs when
real converged shared stores and a workgroup barrier dominate the operation.
Those stores remain outside every SIMT arm. A fixed 32-register heap analysis
tracks unsigned nonwrapping intervals and known-zero alignment bits through
copies, AND, OR, XOR, additions, products and shifts. An AND mask can bound
defined data with unknown values; an unknown unmasked address cannot gain a
bound. Signed/overflowing addition and shifts that might overflow lose the
proof. Branch joins widen intervals and intersect known-zero bits. Predicate
conditions do not narrow a range. Masked/short-VL and unsupported definitions
discard the destination's proof; source/destination aliases use the input
epoch. The five heap snapshots total at most 13 KiB, while the existing
kernel-stack state limit remains 384 bytes.

Every dynamic or permuted address must be aligned and wholly inside both
Mesa's declared allocation and the barrier-visible initialized prefix. The
16 KiB physical bound alone grants no initialization credit. Permutations and
dynamic expressions never earn the exact-LOCAL own-store shortcut. A capture
from another arm or before the split cannot authorize an AMO. Parameters are
captured in each executing arm, and hardware still suppresses helper lanes.
Scalar control state and capture validity are discarded at scope boundaries.
The additional scope-free prefix set is limited to fixed shared VL, the
existing x11/x12 shared warp-base/stride construction, proven unmasked unit-e32
SRAM loads/stores and converged workgroup barriers. It grants no new CSR,
texture/image protocol, arbitrary configuration or vector-instruction prefix
permission. The legacy SIMT arm set stays unchanged; the new feature gate adds
only the atomic instructions, pure per-lane immediate shifts and the emitter's
exact constant-lane-width scalar XOR for adjacent-warp rematerialization.
Symbolic TEMP values live across a split only after materialization into their
stable planned slots; dead initialization addresses consume no persistent
slot. Loops, arbitrary shared layouts, conditional initialization/barriers,
general shared loads in arms and unbounded addresses remain outside this
admission contract.

The 16 KiB SRAM, 128 resident invocations, 20 persistent vector registers and
512-word program limit are unchanged. All ten operations use the same MEMORY
declaration and shared binding as ordinary shared accesses; they cannot become
SSBO operations. Operand capture precedes destination writes, including CAS
and source/destination aliases.

## CPU results and reproduction

ASan/UBSan passes the new native admission corpus and the original validator
corpus. Actual Mesa 22.3.6 TGSI builder/parser, production emitter and Linux
validator pass 120 atomic programs: all ten operations, zero/ordinary/
exclusive-4-GiB SRAM windows, same-address and own-stripe access, and destination
aliases. The original 59 stripe programs and 278-word register-lifetime oracle
remain passing. The complete kernel-free driver test also passes. The actual
feature-query ioctl passes its original corpus plus sixteen combinations of
scalar-AMO support, new owner flag, unified commands and checked shared
presence under ASan/UBSan; scalar-AMO support alone never exposes bit 41.

The bounded-address/SIMT CPU tests pass 150 additional actual Mesa TGSI
builder/parser-to-emitter-to-validator cases, all ten operations over three
SRAM base windows, with peer permutations, AND-bounded offsets and rejection
without publication, for unaligned addresses and divergent barriers. Native
tests cover differing per-arm ranges at joins, missing per-arm captures,
negative immediates, wrap/overflow and reads beyond the initialized prefix.
Five thousand unsigned interval samples are checked against concrete 32-bit
arithmetic for all seven range operations. The original corpora stay intact.
Thirty full-16-KiB TGSI cases additionally retain 32 distinct symbolic stripe
addresses within the original 20-register/512-word limits, including the
exclusive 4 GiB endpoint. Removing any initializer needed by their final-stripe
AMO is rejected. Valid VXRM/texture/image prefixes and noncanonical vector
forms remain rejected before SIMT, with the corresponding resources enabled.

Actual Mesa ESSL 3.10 lowering additionally passes sixteen cases covering all
ten operations and both int/uint overloads where defined. Each
188-word program initializes all 4096 shared words, crosses one workgroup
barrier and executes two actual atomic instructions at the first and last
word. The independent decoder checks 512 returned old words per program,
all shared words and guards, two groups and both warp orders. Actual Gallium
state creation rejects the new program without bit 41 and accepts it with the
bit and valid shared discovery. The original callback corpus (57 submissions,
66 waits), two 486-word epochs and 480-word own-stripe case remain passing.
These CPU checks are separate from the actual compiler-produced program
execution reported below.

```sh
OPENGPU_MESA_DIR=/path/to/mesa-22.3.6 \
OPENGPU_DRM_INCLUDE=/path/to/drm-headers \
OPENGPU_TEST_SANITIZERS=1 \
python3 scripts/test_gallium_shared_own_stripe.py
python3 scripts/test_driver.py
```

For the real ESSL corpus, use a private Mesa build and the exact checked NTT
patch stages described by `test_gallium_compute_shared.py`:

```sh
python3 scripts/test_gallium_compute_shared.py --build /path/to/private-build --sanitize
```

Prepared hardware gates are `SharedAtomicVectorExecuteUnitSpec`,
`SharedAtomicAvailabilityDecodeSpec`, and the explicit stock SRAM/FPU/vector
`GpuComputeUnitSharedAtomicSpec`. Their actual results are listed below; the separate actual-Mesa qualifier is
also passes on the real source shaders as reported below. Full 96-word host,
cross-domain ordering, general shared layouts/CFGs, API and physical H7 gates
remain open.


## Hardware results and reproduction

The complete 726-source main and five selected test sources compile with a
2 GiB JVM. Three decoder tests preserve the old default profile and scalar AMO
classification while checking the opt-in bit and every encoding. The real
round-robin arbiter plus `BankedSharedMemory` owner test passes all ten
operations on 128 invocations at both SRAM endpoints, signed/unsigned/CAS
boundaries, returned old words, helper/inactive masks, scalar contention,
irrevocable offered cancellation, held-commit immutability, delayed responses,
faults, reset and reuse without reset.

The explicit stock SRAM/FPU/vector CU test also passes: 128 contending adds
return exactly the multiset 0 through 127; misalignment and out-of-range jobs
retain precise held traps for all eight warps and publish no output. Five
healthy/fault/recovery epochs retain 37-cycle responses, held completion and
output guards without reset. This test uses its checked instruction fixture;
it does not substitute for the actual-Mesa-word qualification below.

Run with a short GPU-local `TMPDIR`/`java.io.tmpdir`, repository-local tool
caches and `CXX=clang++ MAKEFLAGS=-j1`:

```sh
sbt -J-Xmx2G -J-Xss6M -J-XX:ActiveProcessorCount=2 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "SharedAtomicVectorExecuteUnitSpec.scala", "SharedAtomicAvailabilityDecodeSpec.scala", "GpuComputeUnitSharedAtomicSpec.scala", "QualifyGalliumComputeSharedAtomic.scala").contains(f.getName))' \
  'Compile / compile' 'Test / compile' \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.core.frontend.decode.SharedAtomicAvailabilityDecodeSpec' \
  'testOnly opengpu.core.memory.SharedAtomicVectorExecuteUnitSpec' \
  'Test / runMain org.scalatest.tools.Runner -s opengpu.core.GpuComputeUnitSharedAtomicSpec -oD'
```

The stock suite is `DoNotDiscover`; `testOnly` does not count as its execution.


## Actual-Mesa CU results and reproduction

The CPU command above emits all sixteen `shared-atomic*.native.words` files in
its private `glsl/` directory only after their compiler/admission/oracle checks
pass, then prints `ACTUAL_MESA_SHARED_ATOMIC_INPUT_SHA256`. Keep that directory
only while the explicit CU run needs it. The input digest binds the ordered
names and exact padded word bytes; it is checked before and after execution.
Missing files, malformed words, the wrong digest or a missing explicit
qualification property fail rather than skip the gate.

```sh
sbt -Dopengpu.galliumComputeSharedAtomicQualification=1 \
  'Test / runMain opengpu.core.QualifyGalliumComputeSharedAtomic --inputs /path/to/private-build/glsl --input-sha256 PRINTED_SHA256 --reports /path/to/temporary-reports'
```

The explicit gate passes both tests: input-contract rejection and execution of
all sixteen actual 188-word programs on stock sixteen lanes/eight warps, 16 KiB
SRAM, FPU and vector hardware. It executes two groups per type/operation and
checks each endpoint's 128 returned old words against an independent
source-level multiset. It validates actual Sv32 descriptor/data/code traffic,
delayed acknowledgements, held completion, immutable code/table/page-table/
output guards and reuse without reset. All 32 groups, 256 warp starts, 8192 returned old words and
8192 acknowledged output writes pass; each case observes all eight warps and
holds completion for thirteen cycles. It does not synthesize a shader or
calculate returned bytes in the memory responder. The separate owner and
stock-CU tests retain the fault, cancellation, reset, helper-mask and SRAM guard
checks.

This is a CU execution gate with a protocol memory responder. It does not
establish Linux GEM/producer/context lifetime, complete-host or two-CU product
execution, general shared layouts/CFG admission, physical timing or H7 closure.
The current product profiles continue to leave this new feature disabled.

The new sixteen `shared-dynamic*.native.words` cases have a separate explicit
entry point. It checks source-level conditional/permuted old words, sixteen
bounded dynamic contention buckets, final shared values, all output guards,
held completion and no-reset reuse on the same full stock CU. The memory
responder only acknowledges actual DUT requests. Actual ESSL compilation and
this CU execution are still pending; this command is not a recorded pass:

```sh
sbt -Dopengpu.galliumComputeSharedDynamicQualification=1 \
  'Test / runMain opengpu.core.QualifyGalliumComputeSharedDynamic --inputs /path/to/private-build/glsl --input-sha256 PRINTED_DYNAMIC_SHA256 --reports /path/to/temporary-reports'
```

The ESSL CPU script prints `ACTUAL_MESA_SHARED_DYNAMIC_INPUT_SHA256` separately
from the original sixteen-program digest. Missing or changed inputs and an
absent explicit property fail before elaboration. Keep only inputs needed by
an active consumer, then delete them along with models and reports.
