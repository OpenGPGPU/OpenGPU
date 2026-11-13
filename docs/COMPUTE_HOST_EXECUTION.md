# Compute full-host execution boundary

The H7 full-host compute execution path is unfinished. The current independent
CPU fixture connects actual Linux resource binding, uniform/SSBO preparation,
source snapshots, scheduler/admission, `opengpu_job_run`, MMU PTE generation and
hardware MMIO/IRQ/drain and context CREATE/DESTROY bodies. It does not execute shader instructions, and
no returned GPU bytes or performance results are claimed.

The intended execution input comes from real Mesa ESSL/NIR/TGSI and the normal
Gallium/library submission path. That frontend/controller and its independent
returned-byte oracle are not connected yet. The active executor and complete
96-word DUT qualification also remain open. Native GNJ captures are a separate
protocol and cannot authorize an opcode-0 compute submission.

The boundary retains stock two-CU, 16-lane/eight-warp tasks, 16 KiB shared SRAM,
twelve 16 KiB UBOs plus the full default block and four SSBOs. Source windows,
descriptors, private code/uniform copies, mappings and physical addresses come
from the Linux implementation and the OS allocation primitive. Legacy compute
private resource/code leaves retain the current production RWX permissions;
uniform snapshot leaves use the actual read-only, non-executable map. No native
private-owner permission guarantee is inferred for compute.

`compute_host_export_memory` serializes original allocations and actual PTE
pages as OGMEM001 after the real run path has mapped and submitted its command.
The CPU transport keeps that kernel pending. OGCMP001 contains its actual MMIO
trace and discovery; OGCMT001 records the real IRQ/drain/POP applied to an
external held receipt. All returned memory must be checked independently before
a separate cleanup authorization. Each actual shootdown blocks on the live
DUT acknowledgement before its backing may be freed. VM/ASID/global-page-table
teardown is part of that boundary: actual context destruction evicts its ASID,
then the global MMU shutdown clears both SATPs and waits for a full shootdown.
A failed acknowledgement retains the reachable VM/page tables and wedges the
device. The CPU composition passes six negative cases: wrong sequence and busy
acknowledgements at job-window revocation, context-ASID eviction and final
global shutdown. Each isolated negative-test process ends with quarantined
allocations retained; it does not manufacture a later recovery acknowledgement.

Only uniform + SSBO/shared kernels have an initial execution entry. Capture of
image/counter writable views and continuous multi-kernel publication remain
open; this qualification boundary does not change device capability limits.
In particular, compute's ordinary scheduler chain requires full retirement
before a later job reuses fixed code/descriptor windows. It cannot reuse the
native two-prepared-owner shortcut.

Run the current CPU composition checks without retaining build outputs:

```sh
PYTHONDONTWRITEBYTECODE=1 OPENGPU_TEST_SANITIZERS=1 python3 scripts/test_compute_host.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_compute_owner_protocol.py
```

The explicit CPU test peer only checks transport/admission and never writes
shader results. It is guarded by `OPENGPU_COMPUTE_HOST_TEST_MAIN`, which must
never be defined when linking an actual Mesa/DUT producer. The source remains
on a dependent draft topic until the complete execution pipeline is ready.

The independent compute controller codec requires the real 96-word profile and
ten auxiliary register reads. Native profile/receipt schemas, truncated profiles,
changed completion identities and cleanup without `contextCleanupObserved`
are rejected. A completion receipt cannot authorize cleanup; a separate
`OGCMBA01` authorization follows all independent byte/source/guard oracles.
The non-test producer object is also compiled without the CPU peer macro.
