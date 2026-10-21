# FP load-fault reservation release

An acknowledged scalar FP load fault now releases its destination reservation
without writing the FP register file. Previously `FpuIssueStage` released a
reservation only on normal writeback or initialization. A failed `flw` completed
its kernel but left the destination busy; a later kernel writing that register
could never issue. This affects `f0` as well as the other FP registers.

`FpuBackend` sends cancellation only when its memory fault is accepted and
`writeRd` is set. `FpuIssueStage` forwards the warp/register identity to a
separate `FpuScoreboard.cancel` port. Cancellation and a normal retirement may
clear different registers or warps on the same edge. A new reservation on that
edge wins over clearing the previous owner. Other reservations remain intact.
The RF write port and committed-writeback stream contain only real writes.

[Qualification](performance/mobile-fpu-fault-reuse-2026-10-22.json) passes 23
checks on 996 frozen source files. Six actual CU cases execute twelve kernels:
misaligned, physical-access and page faults at `f0` and `f10`, followed by a
healthy FP write and dependent integer bit move using the same destination.
Each public trap is deliberately held before acknowledgment. All six cases
fail on the archived baseline after completing the faulting kernel: the next
kernel stalls with zero FP writes.

Two backend cases hold a fault, retain its reservation, and then acknowledge it
on the same cycle that a peer warp commits an FP register. They check exact
write identities and healthy destination reuse. Three cancellation-mask cases
cover single-warp, 4-lane/two-warp and default 16-lane/eight-warp shapes, including
same-cycle allocation, `f0`, retained unrelated bits and wrong-warp cancellation.
Ten legacy FPU checks and the two execution-limit cases also pass; those latter
cases execute 22 additional CU kernels.

Default component synthesis passes at 1000 ps TC/SLVT with 25% I/O, standard
ABC, no retiming and zero skipped instances:

| Module | Core slack (ps) | I/O slack (ps) | Mapped cells | Library area | Sequential area | Flops |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `FpuScoreboard` | +464.366 | +253.874 | 2,770 | 309.183480 | 74.649600 | 256 |
| `FpuIssueStage` | +407.628 | +194.342 | 60,091 | 8,238.545640 | 2,602.530000 | 8,925 |

Both use zero SRAM macros; the issue stage includes the current register-based
FP RF. These component results do not qualify whole-FPU, CU, native-host or
renderer timing. Native product-capacity faults, execution availability and
interruption, resource permissions, reconvergence capacity and the original
H0-H8 requirements remain open.

```sh
sbt 'testOnly opengpu.core.GpuComputeUnitFpuFaultReuseSpec opengpu.core.backend.FpuBackendFaultCancellationSpec opengpu.core.backend.scoreboard.FpuFaultCancellationSpec'
sbt 'runMain opengpu.elaboration.EmitFpuFaultCancellation generated/fpu-fault-scoreboard'
sbt 'runMain opengpu.elaboration.EmitFpuFaultCancellation generated/fpu-fault-issue issue'
```
