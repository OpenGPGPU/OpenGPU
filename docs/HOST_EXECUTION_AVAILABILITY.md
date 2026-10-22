# Closed AXI-host execution availability

`GpuHostSystemAxi` now selects actual scalar-FP backend availability for all its
CUs, independently of optional native graphics quotas. Its external FP handoff
is permanently unready. Previously, configurations without native quotas used
the legacy extension profile even when no scalar FP backend/register file
existed. A real MMIO-submitted scalar FP add then stalled beyond 6,000 serviced
AXI cycles without producing a completion.

The wrapper passes `nativeExecutionProfile=true` through `GpuSystem` into each
CU. An unavailable scalar FP instruction or vector operation accessing scalar
FP registers takes the existing illegal-instruction path before issue or
reservation. The host consumes the attributed trap and reports a failed kernel
completion. Standalone `GpuSystem` retains its existing default interface
profile. This changes no runtime register, capability bit, opcode or quota.
Vector-only FP remains supported; an enabled backend retains scalar/vector
interoperability. Existing capability bits remain conservative and unchanged.

Tests cover absent, disabled and serviced scalar-FP profiles, followed by healthy
vector-only successors through real AXI commands. Default-width tests use
sixteen lanes, eight warps and 128-thread groups; delayed replies, held
completion, exact FP32 outputs and output guards are checked. One group uses
one CU, so this is not an aggregate scaling measurement.

See [validation](EXECUTION_VALIDATION.md) for the composed test and timing scope.
