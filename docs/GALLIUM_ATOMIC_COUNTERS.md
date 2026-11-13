# Mesa hardware atomic counters

The bounded compute frontend lowers actual Mesa 22.3.6 `HW_ATOMIC` instructions
for ESSL `atomicCounter`, `atomicCounterIncrement` and `atomicCounterDecrement`
to the dedicated checked counter service. Counter binding zero owns a separate
original GEM through `COUNTER_SOURCE_RESOURCES`; it does not consume an SSBO
window. All four SSBO and four image bindings remain available. The private
publication list retains their nine distinct write owners through CPU
publication; the submitted fence guards accepted work through completion.

Creation, binding and submission require both discovered atomic-counter and
independent-counter-source feature bits. The real Mesa state tracker calls
`set_hw_atomic_buffers`, which owns its buffer reference and validates aligned,
nonempty logical ranges. Missing or undersized active bindings, malformed
metadata and unsupported instructions fail without a partial emitted program.
Active aliases with storage, images, textures and uniform sources are rejected;
the existing kernel also checks complete original backing disjointness.
Capability reporting, compute API enablement and native capacities are unchanged.

## Compiler and execution contract

Mesa's actual `gl_nir_lower_atomics` and NIR-to-TGSI lowering supply the counter
word index in the dimensioned `HW_ATOMIC` operand. Their extra byte-offset source
is redundant for this hardware-counter path and is not added again. Dynamic
counter indexing uses Mesa's canonical `ADDR[2].x`, held separately from uniform
indirection through `ADDR[0].x`. The native counter index is checked before
base-index addition can wrap into the declared range. General TGSI indirection,
other counter bindings, counter operations in control flow, and additional
atomic-counter extension operations remain rejected.

TGSI `ATOMUADD(-1)` returns the old value; native decrement returns the new value.
The emitter converts the native result back to the TGSI old-value contract so
Mesa's following decrement adjustment produces the ESSL new value. Increment
returns the old value. Read uses the native counter read, whose acknowledged
L2 operation is add-zero. Consequently even read requires an RW checked view
and retains original-GEM write ownership. Counter values are not cached by the
service; only the immutable per-task descriptor is cached. Existing task start,
retirement, abort and VM flush invalidate that descriptor cache.

The native index, declared extent, selected source words, enclosing transport
lines and page permissions remain checked by the existing counter/L2 path.
Already acknowledged updates are preserved when a later access faults. This
change adds no global-pointer AMO escape and makes no rollback promise.

## Validation and limits

A fresh ASan/UBSan CPU run passes eight real ESSL→NIR→TGSI programs and 128 exact
returned SSBO words. It calls actual `st_bind_hw_atomic_buffers`, submits through
actual Gallium callbacks/lib ioctl and extracted production kernel preparation,
then independently interprets the emitted native words and original records.
Cases cover all three operations, wraparound, static and dynamic array indices,
UBO binding 35, a counter window spanning two pages, the last word of an
allocation, four SSBO plus four image writes, nine publication owners and
pending counter rebind retention. Both feature gates, ten unsupported TGSI
shapes/opcodes, missing/short/misaligned/empty/end-range bindings, and active
SSBO/image/UBO aliases reject before the source ioctl. This CPU oracle is not
GPU execution. Existing actual Mesa buffer, uniform, SSBO-atomic and shared
corpora also pass ASan/UBSan; the standalone production-driver lifecycle corpus
and test-selection catalogs pass.

The explicit real two-CU/Sv32/shared-L2 qualification passes 34 commands,
20 precise traps, 195 lower RMW write attempts and 800 checked storage/image
outputs in 86,335 cycles. Eight programs use their original Mesa native words
and kernel-published counter/storage/image/uniform records without editing
healthy metadata. Two four-lane workgroups exercise same-word contention,
all eight dynamic indices, counter return-value multisets and whole-backing
and output guards. Lower reads are delayed seven cycles and writes 37 cycles;
RMW completion waits for the real write acknowledgement. The run checks lower
read/write faults, denied counter PTEs, an intentionally nonwritable negative
record, an out-of-range dynamic UBO payload, held precise traps and completion,
retained leases, and healthy reuse after each fault without reset. Three failed
RMW write attempts are included in the 195; failed writes do not update memory.

The fixture models OS/GEM primitives, PTEs and external memory bytes. It runs
real four-lane/two-warp CUs with small private caches and the real four-bank
shared L2. It qualifies counter binding zero with at most eight words and
bounded R32UI buffer-image coexistence, not every legal shader/resource set,
linked graphics-stage aggregate limits, all alias/coherence domains, Linux/API
execution, full workgroup-shared publication, default-product throughput or
physical timing. H7 and the complete API/resource matrix remain open.

## Reproduction

Use matching Mesa 22.3.6 native objects and the checked uniform-index source:

```sh
export OPENGPU_MESA_DIR=/path/to/mesa-22.3.6
export OPENGPU_MESA_BUILD=/path/to/matching-mesa-build
export OPENGPU_DRM_INCLUDE=/path/to/drm-headers
python3 scripts/test_gallium_atomic_counter.py --build .artifacts/counter-active --sanitize

sbt -J-Xmx2G -J-XX:ActiveProcessorCount=2 -Dopengpu.productQualification=0 \
  -Dopengpu.galliumAtomicCounterQualification=1 \
  -Dopengpu.galliumAtomicCounterInputs=.artifacts/counter-active/glsl \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit \
  'set Test / parallelExecution := false' \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "QualifyGalliumAtomicCounter.scala").contains(f.getName))' \
  'Test/runMain opengpu.system.QualifyGalliumAtomicCounter'

python3 scripts/test_compute_uniform_flow.py
python3 scripts/test_test_selection.py
```

The hardware runner is excluded from default discovery, requires explicit
properties and actual compiler/publication inputs, and exits nonzero on a
failed test. Omitting the input property is checked to exit 1. Delete private
builds, captured inputs, models, caches and logs when their validation use ends.
See [independent counter submission](COMPUTE_COUNTER_SOURCE_SUBMISSION.md),
[counter service](ATOMIC_COUNTER_SERVICE.md) and
[compute memory barriers](GALLIUM_COMPUTE_MEMORY_BARRIERS.md) for separate scopes.
