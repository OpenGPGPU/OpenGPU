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

The first wider run passes 18 of 19 checks and exposes the enclosing primitive
owner's shared signed-31 span guard. That guard remains required for triangles;
lines now use the coverage engine's signed-33 differences and checked signed-64
length/weight limits. Expanded signed-64 bounds retain their carry as well, so
an overlapping unrepresentable line cannot wrap into an outside-bounds success.
All five primitive-owner checks pass after this correction, including the new
extreme lines through interpolation/depth and full signed-64 overlap rejection.
The corrected source now passes all 19 coverage, primitive, interpolation and
sample-depth checks across four suites, including exact extreme-line helper
inputs and covered-sample depths. The [complete 39-check regression](performance/mobile-point-line-pipeline-validation-2026-10-20.json)
also passes all six actual-CU vertex/raster checks, nine typed-image checks and
five native typed AXI host checks on the same unchanged 626 source/ABI files.
The later 37-check image/native-host composition passes on its frozen 627-file
source; see [AXI response ownership](HOST_AXI_RESPONSE_OWNERSHIP.md). The
composed synthesis result below still fails timing; H0-H8 stay open.

Run a matched standalone cycle comparison with:

```sh
python3 scripts/benchmark_point_line_cycles.py \
  --baseline-rtl /path/to/baseline/PointLineRasterizer.sv \
  --candidate-rtl /path/to/candidate/PointLineRasterizer.sv \
  --output /path/to/new-comparison-directory
```

The [frozen 15-case comparison](performance/mobile-point-line-cycle-comparison-2026-10-20.json)
checks every output quad field for ordinary points and lines at all three
sample modes, fractional sizes and maximum width. With an always-ready consumer,
both total retirement cycles and first-offer latency increase by exactly two
cycles for points/basic lines and three for multisample lines. No extra cycles
accumulate per sample or quad. Both emissions expose identical port widths,
one active RTL module and no SRAM or private arithmetic module.

This comparison supplements the independent numeric controls; its baseline has
the known extreme-coordinate bug, so equality on these ordinary inputs does
not establish correctness outside this common domain. Combine measured cycles
with matched synthesis timing before claiming elapsed-time improvement.

The [matched standalone synthesis screen](performance/mobile-point-line-synthesis-2026-10-20.json)
maps both components completely at ASAP7 with identical parameters and no
SRAM. Core Fmax estimates change from 273.089 to 278.539 MHz (+2.00%); mapped
area changes from 15,226.054380 to 15,649.997040 square micrometers (+2.78%).
Both fail the 1 GHz setup target: baseline slack is -2,661.815 ps and candidate
slack is -2,590.158 ps. Reported I/O setup slack remains positive.

Dividing the 15 fixture cycle counts by those core Fmax estimates gives
1.26–1.94% lower estimated primitive latency, including the added setup cycles.
These are component estimates at a pre-layout clock; they exclude shaders,
interpolation, memory, contention and frame work. Carry corrections and staging
both contribute to the RTL difference, so this does not isolate retiming alone.
The worst reported path now starts at the captured sample-mode field and ends
at a coverage-mask bit.

The [completed composed synthesis screen](performance/mobile-point-line-replay-synthesis-2026-10-20.json)
maps one replay image engine completely but changes its core estimate from
255.537 to 255.397 MHz (-0.055%). Core setup slack is -2915.477 ps, with
+117.184 ps I/O slack. Area including the same 21 SRAM macros rises from
172654.027199 to 173950.116299 square micrometers (+0.751%). The worst core
path starts at captured sampleMode[0] and ends at mask[0]. This iteration
provides no composed clock benefit; its added setup cycles still apply.
The next timing cut must address the measured per-sample coverage path.
This source predates centroid and polygon-offset work. No full-GPU, frame,
power or routed result is established; PPA remains synthesis only.
