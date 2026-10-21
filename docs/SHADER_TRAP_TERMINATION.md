# Shader trap termination

`GpuComputeUnit(finishOnTrap=true)` now consumes the complete core/system trap
arbiter. Unsupported `EBREAK`, `ECALL` and CSR forms produce attributed traps
and failed completions, followed by healthy CU reuse. The previous path used
only `core.trap.valid`, silently dropping `WarpSystemControl.unsupported`.

`ShaderTrapRetirement` captures each selected event before presenting it. Its
public payload stays stable under backpressure while peer warps cease. A normal
finish takes priority on the one-wide finish port; a simultaneously acknowledged
trap retains its warp in a private pending slot and finishes exactly once when
the port is free. A faulting warp remains resident until its public trap is
accepted. The per-kernel failure bit clears only for a new kernel after all
owned trap state drains. This control is absent when `finishOnTrap=false`.

The only supported CSR form is `csrrwi x0,vxrm,zimm`. Rejected CSR instructions
with a nonzero destination now cancel that register reservation without writing
a fabricated result. System and memory fault acknowledgements share the CU trap
arbiter; an assertion protects their one-wide cancellation ownership. A concurrent
CSR/load-fault test checks both reservations and both warp identities.

[Qualification](performance/mobile-shader-system-trap-2026-10-21.json) passes 33
checks on 986 frozen inputs. Eight CU cases cover system/decode faults with both
scalar-FPU settings; six additional cases target `x10` with CSR register,
immediate and set forms. Two actual two-warp cases combine CSR `x10` and failing
load `x11` with held traps and exact healthy reuse. Four control cases include
the default 16-lane/eight-warp shape and eight independent trap/cease owners.
Seven legacy CU, three scalar backend and three selected shader-stage checks
also pass. The actual shader entrypoint rejects `EBREAK` and then runs a healthy
shader on the same CU.

Both preceding failures are preserved. The original forwarding source fails
four system-instruction cases while its illegal-decode control passes. The first
forwarding repair passes 22 tests but fails a later CSR-to-`x10` reuse test;
ABI context initialization only writes `x1..x9`, exposing the leaked reservation.

Default retirement-controller synthesis passes 1000 ps TC/SLVT with 25% I/O,
standard ABC and no retiming: core +668.231 ps, I/O +425.689 ps, 491 mapped cells,
library area 62.504460, sequential area 32.076000, 110 flops, zero SRAM macros
and zero skipped instances. The changed default scalar backend is receiving a
separate synthesis screen. This does not qualify whole-CU or native-host timing.

Programmable watchdogs for infinite loops, interruption, general resource and
reconvergence contracts, product-capacity native host faults and composed budgets
remain open. S02/H5/H6 and the full H0-H8 objective remain partial.

```sh
sbt 'testOnly opengpu.core.GpuComputeUnitTrapTerminationSpec opengpu.core.GpuComputeUnitCsrDestinationTrapSpec opengpu.core.GpuComputeUnitConcurrentTrapSpec opengpu.core.ShaderTrapRetirementSpec'
sbt 'runMain opengpu.elaboration.EmitShaderTrapRetirement generated/shader-trap-retirement'
sbt 'runMain opengpu.elaboration.EmitShaderTrapRetirement generated/shader-trap-scalar scalar-backend'
```
