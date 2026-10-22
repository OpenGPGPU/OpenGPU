# Task-owned texture execution availability

## Execution contract

A compute task has no sampler owner in `GpuSystem`, including when the same
physical CU also accepts graphics tasks. Previously, scalar and vector texture
instructions could issue to permanently unserviced ports and stall indefinitely.

`textureAvailable=false` rejects all seven scalar and seven vector texture
families at decode. They use the existing illegal-instruction cause 2, with the
original PC, instruction and active mask. Rejection precedes destination
reservation or sampler ownership. The ordinary trap owner retains the event,
fails completion and permits a healthy task to reuse the CU and public ID.
Barrier, derivative and ordinary scalar/vector/FP instructions retain their
existing classification. Instruction encodings and capability records do not
change.

Graphics-capable shared CUs additionally enable `taskTextureAvailability`.
`GpuComputeUnit` snapshots `textureEnabled` only on kernel acceptance and holds
it for every warp allocated to that task. `GpuSystem` derives this input from
the producer selected on the actual graphics-launch/CU-launch handshake.
Registered `graphicsOwner` still describes the preceding task on that edge.
Compute tasks therefore reject texture instructions while accepted graphics
tasks retain their sampler access. Later input changes cannot alter an active
task or a held decoded packet. A newly accepted task receives its own policy.

Standalone externally serviced CU/core/frontend/decode profiles retain static
availability by default. Their integrator must declare absent service or use
the optional task policy. This mechanism does not impose a response deadline
on an existing sampler or qualify other execution-extension ports.

## Validation

Tests cover all fourteen texture families, compute-only and graphics-capable
compute owners, precise faults, healthy ID reuse, delayed replies and policy-pin
changes after acceptance. Both compact and default 128-thread profiles pass.
See [validation](EXECUTION_VALIDATION.md) for composed runtime and timing scope.
