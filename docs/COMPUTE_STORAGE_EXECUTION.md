# Checked compute storage words

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
atomics are not yet exposed or qualified as an end-to-end GPU feature.

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
These tests retain the command and storage lease through held completion. They
do not qualify concurrent resource clients, general cross-domain ordering or
host transport. GCR1 and Linux do not advertise or admit the instructions;
API support and full product qualification remain open.

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
  'testOnly opengpu.system.GpuSystemStorageAtomicSpec'
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
