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
SIMT stack bank. GpuCore, vector operand issue, branch execution, scheduler
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

Automatic compiler selection and driver admission remain separate work; this
change advertises no new native capability bit. Tests cover nested divergence,
loops, capacity/missing-scope/misalignment faults, held offers and healthy reuse
at compact and default 128-thread capacity. See [validation](EXECUTION_VALIDATION.md).
