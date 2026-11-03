# Checked compute storage words

The checked descriptor and word-access composition accepts a 128 MiB view
ending exactly at 4 GiB. A bounded test reads and writes its final word,
rejects the next word, an oversized view and a denied write, then succeeds
again. It checks the 64-byte line address, byte mask and returned word without
allocating or traversing a 128 MiB buffer. Full-profile translated execution,
cross-domain ordering and API qualification remain open.

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "StorageResourceAccessSpec.scala").contains(f.getName))' \
  'testOnly opengpu.core.memory.StorageResourceAccessSpec -- -z "full 128 MiB descriptor boundary"'
```

The opt-in `GpuSystem` storage path connects task-owned storage descriptors to
native vector word loads and stores through the compute units and shared L2.
It is an internal hardware interface, not a GLES or host software ABI.

Each task publishes up to four immutable 64-byte `GSB1` records. A record
contains its context, pass, generation and binding index, access permissions,
view bounds and enclosing allocation bounds. The publisher must retain the
table, allocations and captured address space until completion and accepted
memory traffic drain. Descriptors and each access are checked before data
traffic. Views start on a 256-byte boundary and span at most 128 MiB; backing
allocation endpoints are 64-byte aligned. Word offsets must be aligned and
within the view. Bare and Sv32 translation enforce access permissions.

CUSTOM-1 functions 14, 15 and 16 capture per-lane store values, load words and
store words respectively. Lanes execute sequentially; successful stores from
earlier lanes remain visible if a later lane faults. This is not a vector-wide
atomic operation. Held offers and accepted requests drain on cancellation;
fault and completion ownership are retained until consumed. The path is
disabled unless explicitly built and enabled for the task.

The internal `CheckedStorageAtomic` and `StorageAtomicCuService` add a
descriptor-checked word-atomic request path for all ten existing L2 atomic
operations. They require read and write permission, a valid aligned in-range
word and a retained task/VM lease; accepted requests and cancellations drain
before completion. The service captures the task table and SATP, translates
descriptor reads, and supplies the captured SATP to the external atomic
translation path. Six small component tests pass for all four bindings and ten
operations, bounds and permissions, held offers, cancellation and reuse, bare
and translated descriptor access, and selected translation faults. Storage
atomic component tests alone do not establish end-to-end GPU behavior;
the opt-in system and guest tests are described below.

An internal vector stage and its task-owned service also execute lanes
sequentially through that checked access. An experimental CUSTOM-1 parameter
instruction captures per-lane operand and comparison values, followed by an
atomic instruction carrying binding and byte offset. The ten existing word
operations return old values, suppress helper side effects, and preserve fault
lane attribution and accepted-request drain. Six small tests pass, including
four contending lanes using bare and translated descriptors and selected
descriptor/translation failures. An explicit `storageAtomicAvailable` CU
elaboration option now connects these experimental instructions to the
task-gated decoder, the retained storage table, fault retirement and the
global atomic translator. It defaults off and requires storage and trap
retirement. A four-lane CU executes contended adds with bare and Sv32
descriptors, checking old values, task capture, held completion and selected
descriptor faults. The test models the external atomic responder. It also
exercises configurations with and without image and counter atomic owners;
it does not prove concurrent clients or shared-L2 behavior. An opt-in
`GpuSystem(storageAvailable = true, storageAtomicAvailable = true)` now carries
the option to its CUs. Two small local system cases pass with two four-lane CUs:
eight contended adds through real shared L2 change 10 to 34, preserving guard
bytes, in bare and Sv32 modes. The translated case also rejects a read-only
atomic mapping without a write, then succeeds under a fresh writable root.
Each case then races eight lanes across both CUs on one compare-swap: one sees
the original value 10, seven see the replacement 7, and neighboring bytes stay
intact. Two additional one-lane system cases execute all ten operations through
the same shared L2 in bare and Sv32 modes. They check each returned old value
and stored word, including wrapping addition, signed versus unsigned extrema,
and matching and nonmatching compare-swap. All four local cases pass; they
retain the command and storage lease through held completion. These results do
not qualify contention for every operation or general cross-domain ordering.
Two further local cases enable both image and storage atomics. Two four-lane
workgroups run simultaneously on separate CUs, using an R32UI image view and a
storage view of the same word. In bare and Sv32 modes, their eight adds return
the exact old-value sequence 10 through 31 in steps of three, store 34 and
preserve guard bytes. Both resource leases overlap and drain. The tests do not
observe simultaneous atomic offers or establish general barrier semantics.
An internal opt-in `GpuHostSystemAxi` configuration passes four small local
AXI cases: direct and indirect dispatch, bare and translated access, and a
read-only atomic mapping followed by recovery with a fresh writable root.
Each successful four-lane add changes 10 to 22 and preserves neighboring bytes.
GCR1 reports `OPENGPU_COMPUTE_RESOURCE_STORAGE_ATOMICS` only for the enabled
hardware profile. Linux exposes `OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_ATOMICS`
only with that bit, storage transport and unified commands. Its shader
validator requires an unmasked capture of both operands on every incoming path
and resets that proof on a vector-length change. The storage service checks
the retained view, access permissions and word bounds for each lane. A small
one-lane Linux/ARTI guest passed a storage atomic add, checking old value 10,
new value 17 and a neighboring guard. API/compiler support, general
cross-domain ordering and full product qualification remain open.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.core.memory.CheckedStorageAtomicSpec opengpu.core.memory.StorageAtomicCuServiceSpec'
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.core.memory.StorageAtomicVectorExecuteUnitSpec opengpu.core.memory.StorageAtomicVectorCuServiceSpec'
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 -Dopengpu.simHierarchy=GpuComputeUnit \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.core.frontend.decode.StorageAtomicAvailabilityDecodeSpec opengpu.core.GpuComputeUnitStorageAtomicSpec'
sbt -J-Xmx5G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.GpuSystemStorageAtomicSpec -- -z "allOps="'
sbt -J-Xmx6G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.GpuSystemStorageAtomicSpec -- -z "serialize image and storage atomics"'
sbt -J-Xmx5G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.GpuHostStorageAtomicCommandSpec'
ARTI_STORAGE_ATOMICS=1 scripts/build_arti_compute_resources_model.sh "$QEMU_BUILD" "$QEMU_SRC" "$ARTI_EMBEDDED_WRAPPER"
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 ARTI_SMOKE_TIMEOUT=1800 \
  ARTI_SMOKE_DRAW_WAIT_MS=720000 QEMU="$STORAGE_ATOMIC_QEMU" \
  scripts/run_arti_small_smoke.sh storage-atomic
```

An error returned for a serviced lane reports that lane in the trap fault mask;
earlier successful lanes do not appear in the mask. Instruction-level errors
retain the execution mask. The local storage vector and service suites pass
(three tests), including a second-lane descriptor fault with held completion.
A separate small four-lane CU test checks the trap mask for an out-of-range
storage load. The tests do not establish complete storage fault or cross-domain
ordering coverage.

A separate one-CU, one-lane test checks fences between storage and ordinary
kernarg memory. With both write acknowledgements delayed by 31 cycles, an
ordinary read follows the storage-store acknowledgement, and a later storage
read follows the ordinary-store acknowledgement. An injected storage-write
fault suppresses the later operations; a second kernel then succeeds without
reset. This is same-warp evidence, not a general cross-CU, texture, image or
API barrier contract. It runs locally outside PR and nightly CI:

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=4 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  'testOnly opengpu.core.GpuComputeUnitStorageFenceSpec'
```

The storage translation service preserves page-fault classification for
descriptor and data translations through the vector fault port. Invalid or
denied mappings are page faults; physical PTE-read and data errors remain
access faults. Data faults report the requested word address; descriptor
faults report the descriptor request address. Three component suites passed locally (six
tests), including a read-only data mapping. Two further focused tests passed
for invalid descriptor mappings, physical PTE-read errors, consecutive
requests, held fault output and lane attribution. This is not a complete
system-level fault qualification. Two additional local four-lane CU cases
execute a translated storage load at a nonzero word offset and verify the trap
cause, word address and lane mask for an invalid data mapping and a PTE-read error.
The checked-word suite also passed both cases with the updated fault address.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.core.memory.StorageCuServiceSpec opengpu.core.memory.StorageVectorExecuteUnitSpec opengpu.core.memory.StorageVectorCuServiceSpec'
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 -Dopengpu.simHierarchy=GpuComputeUnit \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.core.memory.CheckedStorageWordAccessSpec opengpu.core.GpuComputeUnitStoragePageFaultSpec'
```

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.core.memory.StorageVectorExecuteUnitSpec opengpu.core.memory.StorageVectorCuServiceSpec'
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 -Dopengpu.simHierarchy=GpuComputeUnit \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.core.GpuComputeUnitStorageFaultSpec -- -z "enabled=true"'
```

Validation: the regression below executed 31 tests across 14 suites with no
failures, cancellations, ignored or pending tests. It includes Bare and Sv32
actual-CU execution, permission and bounds faults, cancellation and a compact
two-CU system with four bindings across direct and indirect dispatches. The
four-CU compute system with image and storage enabled also emitted RTL. These
are not a physical timing, full resource coexistence or performance result.

```sh
sbt 'testOnly *CheckedStorageWordAccessSpec *StorageDescriptorReaderSpec *StorageResourceAccessSpec *StorageCuServiceSpec *StorageVectorExecuteUnitSpec *StorageVectorCuServiceSpec *StorageExecutionAvailabilityDecodeSpec *ImageExecutionAvailabilityDecodeSpec *GpuComputeUnitStorageElaborationSpec *GpuComputeUnitStorageSpec *GpuComputeUnitStorageFaultSpec *GpuStorageCommandContextSpec *GpuSystemStorageElaborationSpec *GpuSystemStorageSpec'
```

The driver has a separate opt-in shader-validation profile for the native
storage parameter, load and store instructions. It requires unmasked execution,
defined vector operands and a store-value capture on every incoming path; a
vector-length change invalidates that capture. Legacy profiles still reject
these operations. Runtime binding indices, offsets and access permissions are
checked by the storage service against retained GSB1 records. This validator
alone does not admit jobs or establish backing ownership and visibility. The
small host test runs with `python3 scripts/test_driver.py`.

[Host MMIO/AXI publication](COMPUTE_STORAGE_COMMANDS.md) provides opt-in native
transport and C encoders. Linux submission is described in [compute storage submission](COMPUTE_STORAGE_SUBMISSION.md).
API/compiler integration, general storage atomics, cross-domain visibility and barriers, full-profile resource
coexistence, physical qualification and H7 acceptance remain open.
