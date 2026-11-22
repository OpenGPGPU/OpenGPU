# Instruction-driven SIMT control

## Instructions and scope contract

The six existing custom-2 B-type vector branches now read the vector register
file and compare full 32-bit operands. Their existing encodings and `join`
encoding are unchanged. RVV VL, v0 and bit 25 do not mask these comparisons:
bit 25 belongs to the signed 13-bit branch displacement. Pending requests
preserve warp, PC, active mask and results under backpressure.

`opengpu.sync join-target` establishes an explicit reconvergence scope. It uses
the previously reserved custom-2 funct3 `011`, a B-type signed displacement,
and zero rs1/rs2 fields. Nonzero register fields are illegal. Its target is
the scope's join instruction; restoring the original-mask marker resumes at
that target plus four. Targets must be word-aligned on this implementation.
No ABI register is reserved or repurposed.

The scope marker and alternate paths occupy the existing physical per-warp
SIMT stack bank. GpuComputeUnitCore, vector operand issue, branch execution, scheduler
and WarpSystemControl now execute the complete instruction path. Legacy
externally supplied branch frames share the bank through exclusive ownership
and cannot masquerade as instruction scopes. A branch without an established
scope faults rather than comparing scalar register aliases or inventing a
reconvergence PC.

At divergence, the taken path runs first and an alternate path is retained
where needed. A join restores that alternate before the original-mask
marker. Nested scopes restore in LIFO order. Lanes taking a loop exit to the
current scope's join can park in the marker without allocating an entry per
iteration. The compiler must emit properly structured scopes and joins;
arbitrary unstructured shader control flow is not admitted by this work.

Missing scopes, exhausted capacity and misaligned taken targets produce
attributed faults before a partial push. The trap offer remains stable under
backpressure. Fault retirement clears the owning warp; peer stacks and a
healthy successor kernel remain usable.

## Integration

The bounded Gallium integer compiler and compute kernel admission now accept a
canonical forward if/else subset; see [its CPU evidence](GALLIUM_COMPUTE.md#forward-ifelse-cpu-acceptance)
and [actual compiler-word CU qualification](GALLIUM_COMPUTE.md#explicit-forward-ifelse-cu-qualification). No new capability bit is
advertised. Existing hardware tests cover nested divergence,
loops, capacity/missing-scope/misalignment faults, held offers and healthy reuse
at compact and default 128-thread capacity. See [validation](EXECUTION_VALIDATION.md).

The compute emitter and Linux word validator also accept fixed and uniform
loop bounds and repeated conditional `break` statements targeting the same
join. The real Mesa ESSL fixtures produce 73, 81 and 89 words for `constant-loop`,
`dynamic-loop` and `loop-break`, respectively. Those streams pass the original
Linux word validator and the Gallium submission fixture in the sanitizer-enabled
[`test_gallium_compute_control_flow.py` run](GALLIUM_COMPUTE.md#forward-ifelse-cpu-acceptance).
An independent CPU decoder executes the submitted words and checks each of
128 lane results plus output guards against source-level expectations.
The original submissions for all three loops also passed the native 16-lane,
eight-warp CU replay. Each produced and acknowledged 128 output writes without
traps, with exact guards, held completion, and stable address spaces. The
dynamic loop issued 56 checked uniform loads. This replay covers the CU and
its memory clients. Full-system Linux execution, nested loops and `continue`
remain open.

The `storage-loop` CPU fixture reads the original SSBO and publishes 384 words
across three iterations. The Linux validator checks each storage operation
and rejects a store whose operand capture is separated from it by a loop exit.
The native CU replay passed for the same 86-word Mesa submission. It observed
384 checked SSBO reads, 384 output writes and 384 write acknowledgements
across eight warps. Output guards, held completion and stable address spaces
passed with zero traps. The full replay passed all nine captured submissions.
Use the [capture and CU replay commands](GALLIUM_COMPUTE.md#explicit-forward-ifelse-cu-qualification)
to reproduce. This result covers the native CU and memory clients.

The complete compute-owner harness also accepts `--case storage-loop`. It
compiles the same ESSL shader through Mesa and submits through the production
Linux driver path. The sanitizer-enabled CPU capture passed command parsing.
The independent physical-byte oracle rejected unchanged RAM and its tests
rejected corruption in the input, result and output guard. The fixture uses
128 distinct input words across one workgroup and checks the 384 expected
result words and all other original physical bytes.

For the CPU capture, follow the
[owner capture setup](COMPLETE_OWNER_COMPUTE_EXECUTOR.md) with
`CASE=storage-loop`. To run on RTL, use the
[complete owner qualification command](P12_RESOURCE_COEXISTENCE.md#reproduce)
with `qualify_p12 --case storage-loop` and an executor built from the current
source. Full DUT execution and cleanup are pending.
