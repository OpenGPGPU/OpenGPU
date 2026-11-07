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
The shared lane count must be a power of two representable by `vsetivli`
(at most 31); the validated device has 16 lanes. Legacy nonshared compilation
keeps its entry point and program layout, including the 16-vec4 immediate limit.
The shared parser permits 32 immediate vec4s; the native limit remains 512 words.

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
```

All three qualifications consume the same CPU artifact directory and manifest
hash.
`--artifacts` holds the renamed CPU `*.native.words` files and a
`manifest.properties` carrying the fixed shape keys, both word-file hashes and
a `cpu.result.sha256`; `--manifest-sha256` is the SHA-256 of that manifest.
