# Per-invocation shared word atomics

This opt-in path gives each executing invocation a distinct atomic operation
and old-word result. Scalar RV32A remains once per warp. The functionality is
under development: CPU admission/lowering and all sixteen ESSL type/operation cases
pass; the new Scala owner/CU tests have not run. It does not close H7 or replace complete-host
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
proofs or publish a prefix. Unknown addresses, peer permutations, arbitrary
shared layouts and frontend CFGs retain their existing restrictions.

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
remain passing. The complete kernel-free driver test also passes.

Actual Mesa ESSL 3.10 lowering additionally passes sixteen cases covering all
ten operations and both int/uint overloads where defined. Each
188-word program initializes all 4096 shared words, crosses one workgroup
barrier and executes two actual atomic instructions at the first and last
word. The independent decoder checks 512 returned old words per program,
all shared words and guards, two groups and both warp orders. Actual Gallium
state creation rejects the new program without bit 41 and accepts it with the
bit and valid shared discovery. The original callback corpus (57 submissions,
66 waits), two 486-word epochs and 480-word own-stripe case remain passing.
These are CPU results; the atomic programs have not executed on RTL.

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
`GpuComputeUnitSharedAtomicSpec`. They require actual execution before these
results can be extended to source shaders or hardware. Full 96-word host,
cross-domain ordering, general shared layouts/CFGs, API and physical H7 gates
remain open.


## Explicit actual-Mesa CU gate (not yet executed)

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

This gate retains the actual 188-word programs, stock sixteen lanes/eight warps,
16 KiB SRAM, FPU and vector hardware. It checks two groups per type/operation,
each endpoint's 128 returned old words against an independent source-level
multiset, actual Sv32 descriptor/data/code traffic, delayed acknowledgements,
held completion, immutable code/tables/page-table/output guards and reuse
without reset. It does not synthesize a shader or calculate returned bytes in
the memory responder. The separate owner and stock-CU tests retain the fault,
cancellation, reset, helper-mask and SRAM guard checks. These prepared gates
have not yet run; their presence does not establish GPU execution or H7 closure.
