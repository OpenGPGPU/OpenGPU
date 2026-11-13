# Bounded Gallium shared uint32 frontend

Compute state creation now queries the device's shared base, byte capacity and
lane count. A nonzero `req_local_mem` must fit that discovered capacity. Missing
or inconsistent discovery fails closed; zero is a valid shared base. The screen
reports the queried shared size without enabling a GL/GLES compute API.

The library queries `COMPUTE_SHARED_BASE/BYTES/LANES` independently and publishes
the tuple only after all three queries and interval checks succeed. An unknown
device returns no inferred capacity; an older driver error is preserved.

The explicit shared emitter accepts canonical uint32 MEMORY stripes over a full
workgroup, straight-line converged barriers, and the checked adjacent-warp
`localID ^ 16` read permutation. Mesa's actual shared allocation bounds every
access. The emitter preserves the shared declaration's typed MEMORY binding,
proves an initialized prefix before a barrier makes it readable, and emits
unmasked e32 unit-stride operations using the trusted local-linear warp base.
It also admits a read of the exact most recent invocation-local store stripe
without a workgroup barrier. The proof retains that stripe's byte offset,
separately from the initialized prefix: a shifted read may name another
invocation's word, even inside that prefix, and still requires publication.
Peer permutations remain barrier-dependent. Native CFG joins retain the local
proof only if every incoming path has the same most recent store offset;
the shared frontend continues to require straight-line control flow.
Only the most recent stripe is retained: another store drops that local proof
even when a later read of an older stripe would be legal. Arbitrary
invocation-local layouts, multiple retained stripes and general CFGs remain
outside this increment.
The distinction between an invocation's own accesses and communication with
other invocations follows [GLSL ES 3.10, shared variables](https://registry.khronos.org/OpenGL/specs/es/3.1/GLSL_ES_Specification_3.10.pdf),
section 4.3.8.
The shared lane count must be a power of two representable by `vsetivli`
(at most 31); the validated device has 16 lanes. Legacy nonshared compilation
keeps its entry point and program layout, including the 16-vec4 immediate limit.
The shared parser permits 32 immediate vec4s; the native limit remains 512 words.

The explicit own-stripe CPU test builds 36 programs using the actual Mesa
22.3.6 TGSI builder/parser and production emitter/validator. The first and last
128-word stripes are accepted without control barriers; uninitialized,
shifted, peer and overwritten-stripe proofs are rejected without partial
output. It covers zero, ordinary and exclusive-4-GiB shared address windows.
A published peer read remains accepted. Removing the accepted store
from the actual emitted words makes Linux validation fail. ASan/UBSan and the
complete native validator corpus pass, including CFG joins and an exclusive
4 GiB endpoint. This test does not execute a GPU or establish general shared
layout/barrier semantics. The additional ESSL own-stripe cases in the full
shared frontend and the existing SRAM CU execution test still require fresh
qualification before this change is considered complete.

```sh
OPENGPU_MESA_DIR=/path/to/mesa-22.3.6 \
OPENGPU_DRM_INCLUDE=/path/to/drm-headers \
OPENGPU_TEST_SANITIZERS=1 \
python3 scripts/test_gallium_shared_own_stripe.py
```

The CPU qualification passed with actual Mesa 22.3.6 ESSL310 → NIR → TGSI, the
production emitter, discovery callbacks and kernel word validator. Its full
128-thread program uses all 16 KiB (4096 uint32 words), exchanges values across
adjacent warps and executes two barrier generations. Both shader seed epochs
compile to 486 native words. An independent instruction decoder checks every
store and every read address/value, each complete 4096-index read multiset,
all eight warps in forward and reverse orders, two groups, exact outputs and
allocation guards. The original 51 callback submissions, comparison/predicate
corpus and failure/recovery checks also pass under ASan/UBSan. Missing discovery,
short allocation, uninitialized reads, omitted/divergent barriers, masked stores,
undeclared MEMORY bindings and unsupported lane configurations are rejected.

The separate [full shared publication qualification](GALLIUM_SHARED_PUBLICATION.md)
connects three converged barriers and both full 503-word producers to ordinary,
image and texture consumers already running on another CU. It preserves the
16 KiB / 128-thread / eight-warp workgroup and checks acknowledged global
publication, precise-warp partial faults and healthy reuse without reset.

Reproduce manually with matching, already configured Mesa 22.3.6 objects and the
checked uniform-index lowering source:

```sh
OPENGPU_MESA_DIR=/path/to/mesa-22.3.6 \
OPENGPU_MESA_BUILD=/path/to/matching-mesa-build \
OPENGPU_DRM_INCLUDE=/path/to/drm-headers \
python3 scripts/test_gallium_compute_shared.py \
  --build /new/private/shared-cpu-output --sanitize \
  --mesa-nir-lower-io /path/to/checked/nir_lower_io.c
```

The runner compiles private fixture/emitter objects and one private NIR-to-TGSI
object, preserving all other Gallium archive members and shared Mesa files. It
corrects the shared MEMORY use index to match Mesa's existing typed declaration.
It neither rebuilds Mesa nor starts a GPU/guest. This evidence covers the bounded
compiler/validator/CPU instruction semantics.

The opt-in native qualification executes those same CPU-qualified programs on
the stock 16-lane/eight-warp CU with the real SRAM models, FPU and vector units
retained. Two epochs and two groups per epoch start 32 warps, allocate all
16 KiB of shared storage, cross both barrier generations, hold completion for
13 cycles and drain to a quiet 16-cycle tail. Instruction, vector, descriptor,
storage and output AXI reads and writes are checked against the independent
word oracle; every output word and allocation guard matches. The harness loads
`manifest.properties`, `full.words` and `epoch-b.words` from the CPU run, checks
every hash and the fixed program shape before elaboration, and refuses to run
without the property. Faults/cancellation, physical shared-memory ordering,
whole-host timing and GL/GLES API qualification remain separate work.
`PIPE_CAP_COMPUTE` remains zero and API versions are unchanged.

A second opt-in qualification runs both word programs at once on two stock CUs.
Each CU keeps its own Sv32 instruction/vector roots, code, kernarg, descriptor
and output pages, and reaches memory only through one
`SharedComputeMemoryInterconnect` and one real `SharedL2Cache` (four banks, 32
outstanding IDs, 16 transactions per CU) with a single lower port. Both CUs
start 16 warps, keep eight resident, cross both barrier generations and finish
with held completion. The run checks 512 exact output words (256 per CU), both
descriptors, every code/descriptor/page-table page read, the written and
acknowledged store sets, and 612 and 616 request/response handshakes on the two
CUs. Both
CUs held warps together for 30668 of 30818 cycles, and offered memory requests
in the same cycle 13 times. This is bounded two-CU coexistence through the
shared fabric; it does not establish four-CU product coexistence, whole-host
timing, physical shared-memory ordering or API/compiler qualification, and H7
remains open.

A third opt-in qualification runs the two word programs on four stock CUs, each
program twice, behind one interconnect and one real L2 sized for 64 outstanding
transaction IDs (16 per CU). Each CU keeps its own Sv32 instruction/vector
roots, code, kernarg, descriptor and output pages; CU0/CU2 run the first seed
and CU1/CU3 the second. All four CUs start 16 warps, keep eight resident, cross
both barrier generations and finish with held completion. The run checks 1024
exact output words (256 per CU), all four descriptors, every
code/descriptor/page-table page read, and the written and acknowledged store
sets, with 613-618 request/response handshakes on each CU (2466 total). All
four CUs held warps together for 30833 of 31047 cycles, two or more offered
memory requests in the same cycle 151 times, and all four offered one in the
same cycle once. This is bounded four-CU coexistence through the shared fabric
in simulation; it does not instantiate the full product `GpuSystem`, and
whole-host timing, physical shared-memory ordering, API/compiler qualification
and general shared layouts remain open. H7 remains open.

A fourth opt-in qualification injects one lower store fault into CU2's first
output write while all four CUs share that fabric. The faulted CU holds a store
access trap (cause 7) at the output's virtual address with one lane in the fault
mask, finishes with completion success deasserted, and never acknowledges the
faulted word. The three peer CUs still produce all 768 of their exact output
words, and the L2 drains with a quiet tail. The same CU then accepts a fresh
launch of the same program and writes all 256 of its words exactly (256 store
requests, 16 warp starts) while the peers stay idle. This is bounded fault
isolation and reuse through the shared fabric in simulation; cancellation,
whole-host timing, physical shared-memory ordering, API/compiler qualification
and general shared layouts remain open. H7 remains open.

A fifth opt-in qualification runs those two word programs on the full four-CU
product `GpuSystem`. The unified command path dispatches four `kernel` commands
(2x1x1 grid, 128x1x1 local) to the four CUs, each with its own storage task and
an identity-mapped Sv32 root covering its code, kernarg, descriptor and output
pages. All four CUs start 16 warps, keep eight resident, cross both barrier
generations and complete successfully under command ids 0-3 while the system
observes the storage lease. The run checks 1024 exact output words, all four
descriptor tables, every code/descriptor page, the written and acknowledged
store sets, and drains to `vmContextIdle`. All four CUs held warps together for
30849 of 31014 cycles. This is product-path coexistence for the bounded
programs; whole-host timing, physical shared-memory ordering, API/compiler
qualification, general shared layouts and broader resource limits remain open.
H7 remains open.

A sixth opt-in qualification injects the same first-output store fault into
command 2 on the product path. The executing CU holds a cause-7 trap at the
output address with one lane in the fault mask; command 2 completes with
success deasserted while commands 0, 1 and 3 complete successfully with all 768
of their exact peer words, and the system drains to `vmContextIdle`. A fresh
command for the same launch then completes successfully: its two workgroups are
placed on two CUs (eight warp starts each) and together write all 256 words
exactly, with no further trap. This is product-path fault attribution and reuse
for the bounded programs; whole-host timing, physical shared-memory ordering,
API/compiler qualification, general shared layouts and broader resource limits
remain open. H7 remains open.

A seventh opt-in qualification queues two waves of the same shared commands on
the product path. Eight commands (ids 0-7) each own a descriptor table and a
disjoint output page while sharing the read-only code and kernarg pages; all
eight complete successfully, write all 2048 words exactly, and keep all four
CUs busy together for 61130 of 61354 cycles. This is bounded command queueing
and CU reuse for the shared programs; whole-host timing, physical
shared-memory ordering, API/compiler qualification, general shared layouts and
broader resource limits remain open. H7 remains open.

Reproduce the native qualification from a CPU acceptance output directory:

```sh
sbt -Dopengpu.galliumComputeSharedQualification=1 -batch \
  "Test/runMain opengpu.core.QualifyGalliumComputeShared \
   --artifacts /path/to/words --manifest-sha256 <sha256> \
   --reports /new/private/report-directory"

sbt -Dopengpu.galliumSharedTwoCuQualification=1 -batch \
  "Test/runMain opengpu.core.QualifyGalliumSharedTwoCu \
   --artifacts /path/to/words --manifest-sha256 <sha256> \
   --reports /new/private/report-directory"

sbt -Dopengpu.galliumSharedFourCuQualification=1 -batch \
  "Test/runMain opengpu.core.QualifyGalliumSharedFourCu \
   --artifacts /path/to/words --manifest-sha256 <sha256> \
   --reports /new/private/report-directory"

sbt -Dopengpu.galliumSharedFaultQualification=1 -batch \
  "Test/runMain opengpu.core.QualifyGalliumSharedFault \
   --artifacts /path/to/words --manifest-sha256 <sha256> \
   --reports /new/private/report-directory"

sbt -Dopengpu.sharedProductFourCuQualification=1 -batch \
  "Test/runMain opengpu.core.QualifySharedProductFourCu \
   --artifacts /path/to/words --manifest-sha256 <sha256> \
   --reports /new/private/report-directory"

sbt -Dopengpu.sharedProductFaultQualification=1 -batch \
  "Test/runMain opengpu.core.QualifySharedProductFault \
   --artifacts /path/to/words --manifest-sha256 <sha256> \
   --reports /new/private/report-directory"

sbt -Dopengpu.sharedProductQueueQualification=1 -batch \
  "Test/runMain opengpu.core.QualifySharedProductQueue \
   --artifacts /path/to/words --manifest-sha256 <sha256> \
   --reports /new/private/report-directory"
```

All seven qualifications consume the same CPU artifact directory and manifest
hash. The product run elaborates the full four-CU system; for small hosts, add
`-Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1
-Dopengpu.simReleaseHeap=1 -Dopengpu.simJobs=1` to bound build memory.
`--artifacts` holds the renamed CPU `*.native.words` files and a
`manifest.properties` carrying the fixed shape keys, both word-file hashes and
a `cpu.result.sha256`; `--manifest-sha256` is the SHA-256 of that manifest.
