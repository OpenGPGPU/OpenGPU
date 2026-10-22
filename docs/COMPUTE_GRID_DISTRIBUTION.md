# Compute grid distribution

One compute kernel now distributes its workgroups across the shared CU pool.
A workgroup remains entirely on one CU, retaining the existing resident-warp,
shared-memory and barrier scope. Graphics tasks continue through the existing
physical compute/graphics arbiter and graphics payload ownership mechanism.
This checkpoint does not establish the complete H0–H8 hardware target.

## Admission and built-ins

`ComputeGridDistributor` captures a parent kernel and walks its unsigned 32-bit
XYZ workgroup dimensions in X-major order. It does not form a truncated grid
product. Registered last-axis flags control carry. Parent rows and child tags
are each bounded by `min(numComputeUnits, 2^commandIdWidth)`; the target profile
uses four rows and four child tags with eight-bit command IDs.

Each child retains the parent's PC, kernarg, original grid and local dimensions.
A captured `KernelGridSlice` sideband selects one group and supplies its full
32-bit XYZ ID to the CU job dispatcher. Therefore workgroup IDs, local shape and
warp-local linear bases retain their original meaning. Slice admission checks
that every selected coordinate is below the original grid extent. Legacy raw
CU callers omit the optional sideband, and graphics tasks select the existing
whole-task path. ISA encodings and shader register assignments are unchanged.

The physical dispatcher still arbitrates compute against graphics, reserves
separate producer completion credits and retains graphics payload pins until
the graphics producer consumes completion. The wrapper translates private
compute child IDs back to parent command IDs for physical owner observation.
Equal numeric compute and graphics tags retain independent namespaces.

## Completion, faults and reset

An offered child descriptor remains stable until accepted. Its parent remains
owned even before the child increments the accepted-work count. Accepted child
tags are released only by actual child completion handshakes. Issue and
retirement on the same edge update each parent's count together, including a
last-group admission concurrent with a failed earlier child.

The first failed child stops creating new offers for that parent. Already
offered or accepted children still execute and drain their real completions.
Only then does the parent produce one failed result. The distributor does not
manufacture replies or reclaim live physical ownership. Other parents continue
through round-robin group admission.

Parent completion is queued and remains stable under backpressure. Its row and
ID remain reserved until that completion is consumed. The upstream command
processor continues retaining the public command ID through public result
consumption. A held parent result does not occupy physical CUs whose children
have already retired. Controlled reset blocks new commands and drains accepted
compute grids; native graphics interruption and payload drain retain their
existing admission and retirement boundaries.

## Qualification

The [initial qualification](performance/mobile-compute-grid-distribution-2026-10-22.json)
passes20 checks: seven new unit cases, two actual system cases and eleven legacy
dispatch/payload checks. All three default component emissions and the strict C
resource header check pass. The [main qualification](performance/mobile-compute-grid-main-2026-10-22.json)
adds30 passing host/native checks, three generated ABI checks, strict C validation
and two host emissions. All three default grid component synthesis screens pass
1000ps including I/O with zero skipped instances. The feature now has50 distinct
checks. Larger coenabled/whole-host timing remains a separate gate. The actual two-CU and default four-CU tests submit one 2x2x2 grid at
full per-CU resident capacity. They compare every group/local/warp built-in
store, untouched guards and actual delayed write acknowledgements, require all
configured CUs concurrently active, and hold public completion before VM reuse.
The original test mistakenly treated `busyComputeUnits` as a count. It is a bit
mask; the corrected test counts set bits and keeps the all-CU requirement. Both
the original fixture failure and the corrected old-hardware baseline are kept.

Additional checks cover multiple parents, reordered and simultaneous child
retirement, held offers and results, real-fault stop with an outstanding offer,
last admission concurrent with a fault, full-width slice IDs, range rejection,
legacy whole-grid dispatch and graphics payload pins with one/two/four CUs.
Synthesis must use 1000 ps TC/SLVT, 25% I/O, standard ABC, no retiming and zero
skipped instances. Component results do not establish whole-CU or host timing.

Fresh host resource discovery, indirect execution, AXI/Sv32/reset and native
shader interruption/fault recovery pass on the composed source. Main retains
all1055executedhardware/ABI/build/test dependencies exactly; the only source
catalog delta is an unused independently committed GTK guest script. Full shader resources, instruction-driven lane-wise branch
semantics, remaining H7/H8 work, coherence, sustained rates and product budgets
remain open.
