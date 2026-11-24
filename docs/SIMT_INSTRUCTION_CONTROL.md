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

## Mesa and Linux integration

The Gallium integer compiler lowers forward conditionals, fixed and uniform
loop bounds, nested loops and conditional breaks. The Linux validator checks
each scope's own join and backedge, invalidates proofs for registers written
inside nested loops at the outer backedge, and rejects storage capture reused
across a loop exit. Negative tests reject inner exits and backedges that
target the outer scope.

Mesa lowered the arithmetic ESSL `continue` fixture to a conditional select
and the SSBO fixture to a conditional inside the loop. Direct TGSI `CONT`
remains unsupported. This work adds no Gallium capability bit.

The sanitizer-enabled [Mesa/Gallium control-flow test](GALLIUM_COMPUTE.md#forward-ifelse-cpu-acceptance)
checks the actual submitted compiler words against an independent ESSL oracle.
All thirteen original submissions passed the
[native 16-lane, eight-warp CU replay](GALLIUM_COMPUTE.md#explicit-forward-ifelse-cu-qualification).
For the loop cases, the replay checked outputs, guards, held completion,
stable address spaces and zero traps. The dynamic loop issued 56 checked
uniform loads. The storage loop observed 384 reads, writes and acknowledgements.
The masked storage loop observed 256 reads, writes and acknowledgements.

## Complete dual-CU execution

The assertion-enabled `two-cu-vga60-owner-v2-image-float-stage` FlashSim
executor passed all nine cases at source `cbf8e2b72`. Each ran original
Mesa ESSL compilation and production Linux driver submission. The independent
oracle checked returned bytes and every other physical byte. Observed command
retirement and context cleanup passed for each case.

| Case | Native words | Checked result |
| --- | ---: | --- |
| `constant-loop` | 73 | 128 results |
| `dynamic-loop` | 81 | 128 results and original 16-byte default uniform view |
| `loop-break` | 89 | 128 per-lane early-exit results |
| `storage-loop` | 86 | 384 results and unchanged original SSBO input |
| `nested-loop` | 110 | 128 results with lane-dependent inner trip counts |
| `continue-loop` | 97 | 128 results with lane-dependent skipped arithmetic |
| `masked-storage-loop` | 116 | 256 selected results and 256 skipped words preserved |
| `loop-branch` | | 12 results after conditional SSBO fences and preserved skipped words |
| `indexed-uniform-loop` | 191 | 128 results and unchanged original 4096-byte default view |

Follow the [owner CPU capture setup](COMPLETE_OWNER_COMPUTE_EXECUTOR.md) or
[complete owner qualification setup](P12_RESOURCE_COEXISTENCE.md#reproduce)
with a FlashSim executor built from the checked-out source. Run each listed case.

```sh
for case in constant-loop dynamic-loop loop-break storage-loop nested-loop \
  continue-loop masked-storage-loop loop-branch indexed-uniform-loop; do
  qualify_p12 --case "$case" || exit
done
```

The compiler currently rejects workgroup execution barriers inside control
flow. Workgroup barriers combined with loops and product clock/FPS
qualification remain open.

## Indexed uniforms inside a masked loop

The `indexed-uniform-loop` fixture compiles through Mesa to 191 native
words. Different lanes and iterations select 128 distinct array elements,
including element 255. The sanitizer-enabled Gallium CPU run checked all
128 lane results. Native CU replay verified 1,536 checked uniform word
reads, 128 stores and acknowledgements, and zero traps. Mesa evaluates
the uniform reads each iteration and selects the lane's accumulator
afterwards. The complete owner fixture passed original Linux CPU
capture with its 4096-byte default uniform view. The independent oracle
evaluates selected loads from original physical bytes and rejected unchanged
RAM. The assertion-enabled two-CU float owner passed this case at source
`f91f860e7` in 95,486 service cycles. All 128 returned results, the original
uniform bytes, physical guards, command retirement and context cleanup
passed. Reproduce with the complete owner command and
`--case indexed-uniform-loop`.

The loop producer also compares Gallium's TGSI block and shared-memory limits
against production driver queries before submission. It checks the 512-word
and four-SSBO limits and, for uniform cases, the 13 constant-buffer slots and
4096-byte default view. The full float-profile CPU capture passed these checks
for storage, masked storage, uniform-bound and indexed-uniform loops. The
public compute feature remains disabled while the wider API contract is open.

At `2614c2b65`, all nine cases in `COMPUTE_LOOP_CASES` passed the original
Mesa/Linux CPU capture using the 112-word float-profile fixture. The sanitized
capture parser accepted each submission. The independent byte oracle rejected
unchanged output memory for every case. Reuse the
[complete owner CPU capture procedure](COMPLETE_OWNER_COMPUTE_EXECUTOR.md)
with `IMAGE_FLOAT_PROFILE`, `IMAGE_FLOAT_STAGE` and `IMAGE_FLOAT_WORDS` in the
profile wire. Compile the parser with `-DOPENGPU_OWNER_IMAGE_FLOAT_STAGE=1`.
Run each case in `COMPUTE_LOOP_CASES` with `--capture-only`.

### Workgroup barriers with a divergent branch

The `shared-barrier-mask` ESSL case runs 128 invocations in a 1D workgroup.
Every lane publishes one shared word and enters the first execution barrier.
Each lane atomically adds three to a different warp's word. A divergent
branch uses each returned old value in one of two disjoint output ranges.
After the second barrier all lanes read the updated shared values. The byte
oracle requires 256 words and checks all skipped writes and physical guards.

The sanitized Mesa/Linux capture and float-owner capture parser passed. The
oracle rejected a wrong peer warp, modifications to skipped words and
unchanged returned memory. Reproduce with the CPU capture procedure above and
`--case shared-barrier-mask --capture-only`. The complete FlashSim owner can
run the same case with `qualify_p12 --case shared-barrier-mask` after building
the executor from these sources. Its full-DUT execution is pending.
