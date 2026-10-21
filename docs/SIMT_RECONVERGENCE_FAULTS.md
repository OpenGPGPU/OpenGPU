# SIMT reconvergence faults

The reconvergence controller now reports stack exhaustion instead of waiting
for capacity that the blocked warp cannot release. An instruction-driven `join`
on an empty stack also reports a fault. These are intermediate S02/H5/H6
execution contracts; lane-wise vector-branch execution and the full shader
resource, interruption and reconvergence contract remain open.

## Capacity and ownership

`GpuConfig.simtStackDepth` counts entries per hardware warp. The default is eight
entries for each of eight 16-lane warps. A divergent branch requires two entries:
reconvergence first, alternate path second. The current path becomes available
only after both writes complete. Uniform branches require no entries, including
when the stack is full. Thus the default stores four nested divergent branches;
it does not provide eight independently nested divergences.

The sequencer checks all required capacity when it accepts a resolved branch.
Insufficient capacity captures the original branch PC and active mask and
offers one overflow event before either stack write. Changing live branch pins
or subsequently freeing entries cannot change that event. The core maps it to
trap cause26, with the branch PC in both `pc` and `tval`, and the original active
mask in both mask fields. The selected core fault is queued before public
presentation, so another warp's fault cannot replace a stalled event.

An empty `join` never issues a restore. `WarpSystemControl` instead offers its
captured instruction through fault retirement, with cause27 and its decoded PC
in `pc`/`tval`. `GpuComputeUnit(finishOnTrap=true)` holds the trap until accepted,
finishes that warp exactly once, marks the kernel completion unsuccessful and
allows healthy reuse after completion acceptance. The generated shader header
and JSON contain both cause constants. Instruction encodings, GSL1 words and
feature bits are unchanged.

Warp finish clears its stack and any matching resolver/sequencer ownership.
This also cancels a partly written pair, preventing a delayed alternate push
or current-path redirect after recycling the slot. Other warps' saved entries
remain intact. The existing execution owner remains responsible for draining
older physical transactions before finish; this mechanism supplies no deadline
for an unresponsive external owner.

## Evidence and remaining integration

The [1,038-input integration](performance/mobile-reconvergence-faults-2026-10-22.json)
passes54 distinct checks: 42 component checks carried from identical production,
build and component-test dependencies, plus12 fresh integration checks. The
component set includes nine new cases and33 compatibility regressions. Capacity cases
cover depth1, odd depth3 and the complete default16-lane/eight-warp/depth8
configuration, atomic admission, uniform branches at capacity, held original
metadata, peer LIFO preservation and reuse. Three clear cuts cover the pending
resolver, before the first push and after the first push. A core test routes
two held peer overflow events through the existing external SIMT result port.
It does not execute a vector-branch instruction.

The actual CU cases execute `join` at0x1000 with scalar FP disabled/enabled,
verify cause27 and both PC fields, hold the trap and failed completion, then
execute a healthy scalar write and `cease`. Both identical tests originally
failed after501 cycles with no trap and the warp still active. Their original
source and failed results are retained. The compatibility set also retains
system/decode faults, concurrent CSR/load-fault destination reuse, and22 actual
instruction-quota/interruption kernels. The ABI check and default flow,
warp-system and four-CU indirect-host emissions pass.

Both complete component synthesis screens pass1000ps TC/SLVT with25% I/O delay,
standard ABC, no retiming and zero skipped instances:

| Component | Core slack ps | I/O slack ps | Cells | Library area | Flip-flops |
|---|---:|---:|---:|---:|---:|
| Default `SimtControlFlow` | +493.532 | +348.407 | 16,331 | 2232.314640 | 3,468 |
| `WarpSystemControl` | No sequential path | +376.990 | 703 | 55.301940 | 0 |

Neither component contains an SRAM macro. The first result wrapper incorrectly
required two timing groups for the entirely combinational warp-system block.
Its strict synthesis qualification already passed the one input-to-output
virtual-clock group. The classification was corrected after checking zero
sequential area, the actual path endpoints and unchanged constraints; the
original wrapper result remains preserved. No synthesis was rerun.

The12 fresh checks pass actual default four-CU indirect-compute execution,
both256-bit AXI host buffering profiles, Sv32 translation and real reset drain,
and typed/binned local/spilled native vertex/fragment join-fault recovery. The
four native cases execute16 frames: eight attributed shader faults and eight
exact healthy successor images, with held-completion VM protection and payload
release. The configured four-CU indirect checks do not prove that one grid
distributes workgroups across every CU. Production inputs and the emitted
component RTL are byte-identical to the42-check synthesis checkpoint.
Instruction-driven vector-branch production, native overflow, discoverable
resource limits and full core/CU/host timing still require evidence. No whole
watchdog, API or H0-H8 completion follows from this checkpoint.
