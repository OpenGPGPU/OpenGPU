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

Validation: the regression below executed 31 tests across 14 suites with no
failures, cancellations, ignored or pending tests. It includes Bare and Sv32
actual-CU execution, permission and bounds faults, cancellation and a compact
two-CU system with four bindings across direct and indirect dispatches. The
four-CU compute system with image and storage enabled also emitted RTL. These
are not a physical timing, full resource coexistence or performance result.

An additional sparse descriptor-to-access test accepts a 128 MiB view ending
at the exclusive 4 GiB boundary, reads its first and last words, and rejects
an out-of-range offset and an oversized view before data traffic. A compact CU
executes vector store/load instructions at the last word of that view in Bare
and Sv32 modes; an access immediately beyond the view faults without issuing
data traffic. Local-only qualification repeats the boundary load/store in both
modes on a 16-lane, eight-warp CU with 128 active threads. The smaller control
cases also pass. Multi-CU full-resource and host qualification remain open.

```sh
sbt 'testOnly *CheckedStorageWordAccessSpec *StorageDescriptorReaderSpec *StorageResourceAccessSpec *StorageCuServiceSpec *StorageVectorExecuteUnitSpec *StorageVectorCuServiceSpec *StorageExecutionAvailabilityDecodeSpec *ImageExecutionAvailabilityDecodeSpec *GpuComputeUnitStorageElaborationSpec *GpuComputeUnitStorageSpec *GpuComputeUnitStorageFaultSpec *GpuStorageCommandContextSpec *GpuSystemStorageElaborationSpec *GpuSystemStorageSpec'
sbt 'testOnly opengpu.core.GpuComputeUnitStorageFullProfileSpec -- -z "128 MiB"'
```

Host MMIO/AXI publication, driver/compiler integration, general storage
atomics, cross-domain visibility and barriers, full-profile resource
coexistence, physical qualification and H7 acceptance remain open.
