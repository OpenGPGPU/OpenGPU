# Private shader execution availability

`KernelShaderStage` now selects actual scalar-FP backend availability for its
private CU even when instruction quotas are absent. The adapter has no external
scalar-FP completion service: its unsupported FP handoff is held unready. The
previous decoder profile nevertheless admitted scalar FP instructions when
`enableScalarFpu=false`, allowing a shader to remain blocked indefinitely.
Vector operations that read or write scalar FP registers could also reach a
missing register file.

`GpuComputeUnit.nativeExecutionProfile` explicitly selects the existing
backend-aware decode profile. Private shader adapters set it independently of
`executionLimits`; enabling execution limits still selects that profile as
before. Generic CU construction retains its existing default external-service
profile. This adds no runtime register, port, capability bit or quota requirement.

Absent scalar FP operations now enter the existing illegal-instruction path
before issue or destination reservation. Cause 2 retains the raw instruction,
PC, warp and lane masks. Held faults retain ownership, each affected warp
retires, and completion reports failure. Vector-only FP uses its own registers
and backend and remains available. Configurations with a serviced scalar
backend retain scalar/vector FP interoperability.

Tests cover scalar arithmetic/load rejection, vector transfers to/from absent
scalar-FP registers, disabled configuration F, an enabled scalar/vector bridge,
held faults/completions and healthy shader reuse. Default sixteen-lane/eight-warp
cases check every launched thread's exact FP32 output and guards. Trap warp IDs
identify reusable physical slots; fault PCs distinguish logical warps.

See [validation](EXECUTION_VALIDATION.md) for runtime and component timing scope.
