# Shader submission validation: what hardware already enforces

Status: design analysis only. No validator code is changed by this document.
Question: which checks in the kernel shader validator could be removed because
hardware enforces them, and what must be proven first.

## Verified facts

Source reads only; no simulation has been run for this document.

- Per-context address spaces exist. The driver states that context ASIDs have
  no identity map and that physical addresses fault once the private VM is
  active (`driver/opengpu_compute.c:162`). RTL data translation is driven by
  `vectorSatp` and an ASID-aware `VectorTlbFlush`
  (`src/main/scala/opengpu/core/GpuComputeUnit.scala`).
- Instruction and data translation both have walkers and TLBs
  (`core/memory/Sv32PageTableWalker.scala`, `InstructionTlb.scala`,
  `VectorTlb.scala`).
- Faults are reported, not silently completed. `VectorTlb.scala` returns
  permission and walk faults; `CoreTrap` maps them to load/store/instruction
  page-fault causes.
- Recovery is device-wide. A fault reaches `opengpu_hw_unified_reset_request`
  (`driver/opengpu_hw.c:146-160`), which writes `GPU_REG_UCMD_RESET`, and the
  abort path cancels in-flight unified work (`opengpu_hw.c:355-398`).

## Validator checks, classified

| Check | Protects | Can hardware replace it? |
|---|---|---|
| Memory address range for loads/stores | Cross-context memory access | Yes, if the fault is proven to block the access |
| Instruction fetch / branch target range | Executing outside the program | Probably; fetch is translated, but the fault path must be proven |
| Entry alignment and size arithmetic | Malformed offsets, overflow | No. Host-side input sanity, cheap, keep |
| Declared resource use (UBO, SSBO, texture, image units) | Use of unbound or foreign resources | No. An unbound slot is not a page fault |
| Shared-memory own-visibility proof (`offsets[512]`) | Data race correctness inside one workgroup | No. This is correctness, not isolation |

## The gap: fault isolation is not complete

Memory isolation between contexts is enforced by translation. Availability is
not isolated. One context's fault resets the device and aborts other in-flight
unified work. Data is not leaked, but other contexts can lose work. Removing
validation without fixing this changes the failure mode from a rejected
submission to a device-wide reset caused by user code.

## Required evidence before any validator check is removed

1. Component test (CU level): two ASIDs with disjoint physical pages. Context A
   faults; context B's accesses complete with correct data. The existing page
   fault specs (`GpuComputeUnitStoragePageFaultSpec`,
   `GpuComputeUnitInstructionPageFaultSpec`) cover single-context faults only.
2. System test (driver/RTL level): the same two contexts through the unified
   submission path. Expected result: B must not be aborted. Current driver
   behavior (device reset on any fault) is expected to fail this test. Record
   the actual result either way.
3. Only if test 2 passes, or if the reset is changed to per-context, consider
   removing the memory range check. Keep all other rows of the table.

## Decision

Keep the shader validator unchanged. The memory row is a candidate for removal
only after test 2 passes. Until then, the validator is the only protection
against a faulting submission resetting the whole device.

## Not verified

- Whether the GPU reset can be made per-context in RTL.
- Behavior of instruction fetch faults through the full path.
- Performance of validation; see the separate measurement plan.
