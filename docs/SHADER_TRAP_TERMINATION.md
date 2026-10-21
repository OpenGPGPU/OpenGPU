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
and zero skipped instances. Default scalar-backend synthesis also passes: core +428.223 ps, I/O +74.102 ps,
123643 cells, library area 15071.914620, sequential area 6077.527200, zero SRAM
macros and zero skipped instances. The register-based RF follows the current
native `useBlackBoxes=false` implementation. These component screens do not
qualify whole-CU or native-host timing.

Programmable watchdogs for infinite loops, interruption, general resource and
reconvergence contracts, product-capacity native host faults and composed budgets
remain open. S02/H5/H6 and the full H0-H8 objective remain partial.

```sh
sbt 'testOnly opengpu.core.GpuComputeUnitTrapTerminationSpec opengpu.core.GpuComputeUnitCsrDestinationTrapSpec opengpu.core.GpuComputeUnitConcurrentTrapSpec opengpu.core.ShaderTrapRetirementSpec'
sbt 'runMain opengpu.elaboration.EmitShaderTrapRetirement generated/shader-trap-retirement'
sbt 'runMain opengpu.elaboration.EmitShaderTrapRetirement generated/shader-trap-scalar scalar-backend'
```

## Native AXI integration

[Two fresh native checks](performance/mobile-shader-system-trap-native-axi-2026-10-22.json)
pass after merging main `673edb6a`. Twelve two-CU frames cover local/spilled
payloads: six expected `EBREAK`/`ECALL`/CSR-`x10` shader faults publish no image,
and six healthy native reuses write `x10` before producing exact MRT/depth/
resolve images. Each frame owns fresh instruction addresses. Held completions
pin VM state and all payload reservations release. The 988-input integration
retains every production/build/ABI byte from the 33-check component source;
changes are main's query tests plus the new native probe. Both default component
emissions are byte-identical to their qualified synthesis inputs. The distinct
scoped total is 35. Full product capacity, programmable watchdog/interruption,
whole-CU/renderer timing and the full H0-H8 objective remain open.
