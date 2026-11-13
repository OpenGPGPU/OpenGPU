# Full shared workgroup publication

This explicit qualification connects a full workgroup-shared producer to a
consumer already running on another CU. Shared SRAM remains private to the
producer's workgroup. The producer publishes its derived result through an
original-GEM SSBO; ordinary, buffer-image and buffer-texture consumers observe
that global allocation through their own checked paths.

## Compiler and ordering contract

The two real ESSL producers each initialize all 4,096 shared words, execute a
converged barrier, read all stripes through the `localID ^ 16` cross-warp
permutation, and execute a second converged barrier. All 128 invocations then
write their sums to global storage. A third converged barrier and
`memoryBarrierBuffer()` precede 128 publication flags on separate cache lines.
The original initialized-prefix and convergence checks remain enforced.

Actual Mesa NIR-to-TGSI lowering and the checked native emitter produce 503
words per producer, within the existing 512-word ceiling. The real Gallium
callbacks, lib ioctl and extracted production kernel preparation supply the
immutable shader and resource records. Both actual image and texture consumer
programs read the old value, publish a checkpoint, cross a memory barrier, read
a mutable coordinate and repeat the same texel access in the original task.
Their native sizes are 79 and 82 words respectively. The mutable coordinate
prevents the compiler from replacing the repeated access with the first result.

The ordinary consumer is an explicit native cache witness. It reads the old
word twice before the producer starts, polls the separate flag, executes a
fence and reloads the data. The first read may already hit a valid L1 line from
a preceding task; the second read must cause no additional fabric request.
After a real data-line invalidation, the reload must add exactly one request
and return the new value. Image and texture consumers use their original Mesa
words, with the original checkpoint reply held until the producer publishes.

The fixture retains the complete 16-lane, eight-warp, 128-thread, 16 KiB shared
configuration, native FPU/vector execution and SRAM models. Two real CUs use
small private caches, the real interconnect and four-bank shared L2. Independent
stable Sv32 roots map actual views of the same original GEM to the same physical
bytes. This is a CU/resource component qualification; it does not claim the
command system concurrently runs unrelated API contexts in one VM.

The ordinary launch helper also supplies a null texture pointer when its
texture count is zero, matching the existing ioctl pointer/count contract.
This enables a valid image-only launch without relaxing kernel admission.

## Validation and limits

The ASan/UBSan CPU qualification passes four real Mesa programs and their
actual kernel records. Its independent shared-word interpreter checks all
4,096 initialized words, all 128 sums and flags, three converged barriers,
forward and reverse scheduling of eight warps, and original backing/output
guards. This CPU oracle checks compilation and publication, not GPU coherence.
The existing real Mesa buffer, full-shared and atomic-counter CPU regressions
also pass ASan/UBSan after the helper and fixture changes.

The explicit two-CU hardware run passes 21 kernel launches, three precise
warp faults, 15 original-consumer values, 2,979 global write attempts (three
failed), 17 private-cache invalidations and 452,925 cycles. The run uses the
original actual Mesa words and published resource records for both full shared
producers and the image/texture consumers.

The hardware fixture models OS/GEM primitives, page tables and external memory
bytes. Lower reads take seven cycles and writes 73 cycles; every healthy result
write must be acknowledged before any publication flag is offered. It checks healthy
successive epochs, a precise first-result write fault, held traps/completion,
resource leases, backing guards and healthy recovery without reset.
[Accepted precise traps](SHADER_TRAP_TERMINATION.md) retire only the faulting
warp; peer warps continue.
The negative run requires the first warp's 16 data words and flags to stay
unchanged, the other 112 data acknowledgements to precede the surviving flags,
the designated first flag to remain unpublished, and completion to fail.
Partial writes from the peer warps are checked exactly; no whole-kernel
cancellation or rollback is promised. Full workgroup ownership, warp
participation, barrier release and quiet teardown are explicit assertions.

This bounded chain does not complete H7, enable a complete GLES compute API,
qualify every alias/coherence domain, establish linked-stage aggregate resource
limits, or measure product throughput and physical timing. The original shared
capacity, native instruction capacity and API capability gates are unchanged.

## Reproduction

Use matching Mesa 22.3.6 objects and the checked uniform/shared NTT source:
set `TMPDIR` to a fresh short directory under the main GPU repository's
`.artifacts/` and pass it to the JVM as below. On macOS its complete path must
be at most 52 bytes to leave room for sbt's Unix socket name. Remove it after
all development/validation processes exit.

```sh
export OPENGPU_MESA_DIR=/path/to/mesa-22.3.6
export OPENGPU_MESA_BUILD=/path/to/matching-mesa-build
export OPENGPU_DRM_INCLUDE=/path/to/drm-headers
python3 scripts/test_gallium_shared_publication.py --build .artifacts/shared-active --sanitize

sbt -J-Xmx2G -J-XX:ActiveProcessorCount=2 -Djava.io.tmpdir="$TMPDIR" -Dopengpu.productQualification=0 \
  -Dopengpu.galliumSharedPublicationQualification=1 \
  -Dopengpu.galliumSharedPublicationInputs=.artifacts/shared-active/glsl \
  -Dopengpu.sramModels=/path/to/asap7_sram_0p0/generated/verilog \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit \
  'set Test / parallelExecution := false' \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "QualifyGalliumSharedPublication.scala").contains(f.getName))' \
  'Test/runMain opengpu.core.QualifyGalliumSharedPublication'

python3 scripts/test_gallium_buffer_flow.py --build .artifacts/shared-regression-buffers --sanitize
python3 scripts/test_gallium_compute_shared.py --build .artifacts/shared-regression-shared --sanitize
python3 scripts/test_gallium_atomic_counter.py --build .artifacts/shared-regression-counters --sanitize
python3 scripts/test_test_selection.py
```

The runner is excluded from default discovery and requires explicit properties
and real compiler/publication inputs. Failed tests exit nonzero; omitting the
input property is checked to exit 1. Delete private
builds, captures, models, caches and logs when their validation use ends. See
[shared compute](GALLIUM_SHARED_COMPUTE.md),
[image ordering](GALLIUM_IMAGE_ORDERING.md), and
[compute memory barriers](GALLIUM_COMPUTE_MEMORY_BARRIERS.md) for separate scopes.
