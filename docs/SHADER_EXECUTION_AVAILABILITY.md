# Shader execution availability

Execution-limited CUs now classify instructions using the actual scalar FP
register file and backend availability. A decoded scalar FP instruction cannot
wait indefinitely on an unserviced extension port. A vector instruction that
reads or writes a scalar FP register cannot consume a fabricated zero operand
or discard its FP result when that register file is absent.

`GpuComputeUnit(executionLimits=true)` selects the native execution profile in
`GpuCore`. It passes actual scalar-FPU availability through the frontend and
decode pipe. `FullInstructionDecoder` retains its ISA allow-lists and rejects
scalar FP or vector `readsFloat`/`writesFloat` operations if the corresponding
backend/RF is absent. Rejection occurs before backend issue or destination
reservation. The existing illegal-instruction path preserves the instruction,
PC, active mask and cause 2; trap retirement fails the kernel once. Vector-only
FP arithmetic and conversion use the vector RF and remain supported.

This is an elaboration-time availability contract. It does not change backend
availability through the runtime quota register. Legacy extension-interface
profiles retain their previous routing. The complete execution/interruption,
resource and reconvergence contract remains required; an instruction quota alone
cannot stop an already blocked instruction.

[Qualification](performance/mobile-shader-execution-availability-2026-10-22.json)
passes 25 checks on 999 frozen inputs. Seven actual CU cases execute thirteen
kernels: unavailable scalar add/load, VF scalar reads and vector-to-FP writes,
including configuration F disabled, each followed by healthy vector-only FP
reuse. A supported scalar/vector bridge produces exact 2.0 through scalar FP,
vector arithmetic, VF input, vector-to-FP and dependent integer use. The original
baseline fails six unavailable cases; its enabled bridge control passes.

Two default 16-lane/eight-warp decode cases hold each packet and check raw
instruction/PC/mask/warp retention with and without scalar FP availability.
Eight legacy decode/frontend checks and eight fault/quota cases also pass. The
latter execute 34 additional CU kernels, for 47 actual kernels in this checkpoint.

Both default decode profiles pass 1000 ps TC/SLVT synthesis with 25% I/O,
standard ABC, no retiming, zero SRAM macros and zero skipped instances:

| Scalar FP available | Core slack (ps) | I/O slack (ps) | Cells | Library area | Sequential area | Flops |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| No | +525.450 | +416.626 | 1,546 | 170.119440 | 52.779600 | 181 |
| Yes | +517.250 | +407.170 | 1,821 | 203.580540 | 59.486400 | 204 |

Native-host composition, full product capacity, whole-CU/host timing and all
original S02/H5/H6 and H0-H8 gates remain open.

```sh
sbt 'testOnly opengpu.core.GpuComputeUnitExecutionAvailabilitySpec opengpu.core.frontend.decode.ShaderExecutionAvailabilityDecodeSpec'
sbt 'runMain opengpu.elaboration.EmitShaderExecutionAvailability generated/absent-scalar-fp'
sbt 'runMain opengpu.elaboration.EmitShaderExecutionAvailability generated/enabled-scalar-fp enabled'
```
