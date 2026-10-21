# Shader instruction limits and interruption

`GpuComputeUnit(executionLimits=true, finishOnTrap=true)` snapshots a 32-bit
instruction limit when it accepts a kernel. Each subsequent warp allocation
receives that snapshot. A nonzero value permits exactly that many admitted
instruction fetches per warp, including branches and `cease`. Zero disables the
quota and retains cooperative interruption. Changing the control input after
kernel acceptance does not alter its warps' quotas.

`WarpInstructionGuard` decides and registers each fetch before offering it to
instruction translation/cache. An exhausted warp receives a synthetic fetch
response instead of another physical request. The frontend retains the PC and
active mask and routes that response through scalar decode without reserving a
destination register. The resulting attributed trap uses cause 24 for quota
exhaustion or 25 for interruption, with the stopped PC in both `pc` and `tval`.
The existing trap-retirement owner holds the event until accepted, then retires
the warp once and marks its kernel completion unsuccessful.

Interruption is a level input with a per-kernel latch. A pulse also applies to
later warp allocations in that kernel. Only accepting a fresh kernel after all
old ownership has drained clears the latch; an interrupt asserted on that same
cycle applies to the new kernel. Interruption takes precedence over exhausted
quota for a fetch that has not yet been admitted.

An already registered physical fetch remains offered with an unchanged payload
until accepted. Its actual response, including a translation or access fault,
drains without replacement by interruption. The same rule applies to an older
load/store instruction: the warp scheduler remains blocked until that operation
completes, and the guard stops its next fetch. Physical replies from different
warps may arrive out of order. Queued public responses remain stable while
backpressured. Assertions check live warp allocation, one unresolved fetch per
warp, causal physical replies and drained fetch ownership before warp finish or
kernel replacement.

The guard is optional and absent in existing CU profiles unless explicitly
enabled. It requires finish-on-trap retirement. This checkpoint is a CU
mechanism, with native-host programming and reset integration still required.
It does not impose a cycle deadline on an external memory owner, a stalled
texture service or an unserviced execution-extension port. Full watchdog
liveness, resource permissions, SIMT overflow handling, native product capacity
and the original S02/H5/H6 and H0-H8 gates remain open.

## Verification

`WarpInstructionGuardSpec` checks single-warp, 4-lane/two-warp and default
16-lane/eight-warp shapes. It exercises independent exact quotas, zero and
`0xffffffff` boundaries, same-cycle allocation/fetch, immediate physical
responses, held requests and responses, latched interruption, fresh-kernel
reuse, all eight owners and out-of-order/faulting replies competing with
synthetic stops.

`GpuComputeUnitExecutionLimitSpec` executes eleven kernels with each scalar-FPU
setting. These include two real looping warps with an explicitly initialized
counter; exact and one-short finite quotas; an unlimited finite kernel; delayed
load, store and instruction-fetch interruption; real load/fetch fault priority;
a barrier waiter whose looping peer exhausts its quota; and healthy reuse of
both warp slots. Traps and completions are deliberately held. Every completion
must follow all owned memory replies, and failed operations must not fabricate
destination writes.

The first test run assumed an uninitialized `x10` value and failed both CU
counter comparisons after reaching their expected quota traps. The corrected
program explicitly initializes its counter; the original source and failed log
remain archived. The production mechanism is identical across those runs.

```sh
sbt 'testOnly opengpu.core.frontend.WarpInstructionGuardSpec opengpu.core.GpuComputeUnitExecutionLimitSpec'
sbt 'runMain opengpu.elaboration.EmitWarpInstructionGuard generated/warp-instruction-guard'
```

[Qualification](performance/mobile-shader-execution-limits-2026-10-22.json) passes
67 distinct checks on 992 frozen source files: 13 guard checks, two CU cases
executing 22 kernels, and 52 compatibility regressions. Default guard synthesis
passes 1000 ps TC/SLVT with 25% I/O, standard ABC and no retiming: core +402.588 ps,
I/O +626.374 ps, 2,753 mapped cells, library area 333.517500, sequential area
114.307200, 392 flops, zero SRAM macros and zero skipped instances. This is a
component result; it does not qualify the CU, native host or renderer timing.
