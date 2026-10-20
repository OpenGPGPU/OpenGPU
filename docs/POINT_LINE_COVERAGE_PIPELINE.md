# Point/line coverage preparation and coordinate carries

The prior replay-engine synthesis screen reports its worst core path from
`request.state.lineWidth[30]` to point/line coverage `mask[0]`. This candidate
registers work that is constant for a primitive before scanning samples:

1. Decode clamped FP32 sizes and capture full signed endpoint differences.
2. Capture squared length, squared line width and major-axis selection.
3. Check the original full-width admission bounds. For multisample lines,
   capture the width-squared times length threshold before scanning quads.

Points and basic lines add two setup cycles per primitive; multisample lines
add three. The per-sample state sequence is unchanged. This is a proposed
timing cut, with no new Fmax, area or elapsed-time improvement claimed until
matched synthesis and workload checks finish. It adds derived-value registers,
with no private SRAM or floating arithmetic lane.

The boundary tests also expose a correctness defect in the original arithmetic.
At eight subpixel bits, a horizontal line from `(-2^31,640)` to `(1024,640)`
has a legal signed-64 squared length and crosses visible pixels of an 8x8 view.
Subtracting its first endpoint from a positive pixel center requires 33 signed
bits. The old subtraction could truncate that carry and drop every covered
pixel. The regression also fails at two and four subpixel bits; sixteen bits
already gave the intermediate a wider type and passes the original test.

Endpoint-to-center/sample differences, cross-product subtraction, remaining
weights and diamond-center sums now retain their carries. Coordinates keep
their original signed-32 interface. Admission still rejects lengths that do
not fit the signed-64 weight contract, and each lane checks its full parameter
and remaining weight before narrowing. This does not claim support for every
unrepresentable guard band or complete API numeric conformance.

The initial eight checks pass all existing direction, width, point-size,
scissor, helper, sample-mode and held-quad behavior. New checks compare coverage
and exact weights at subpixel settings 2/4/8/16, including extreme endpoints,
both directions, length overflow and healthy reuse. Eleven early cancellation
cuts and mutations of unoffered descriptor inputs verify snapshot ownership.
The frozen original has three failures in those four new boundary checks.

Primitive/interpolation, sample-depth, actual-CU image and native typed host
checks are pending. Their new extreme-endpoint case follows the corrected
weights through interpolation and covered-sample depth. Matched standalone
synthesis and wider integration remain necessary; H0-H8 stay open.
