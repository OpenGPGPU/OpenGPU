# Legacy interpolation timing repair

A fully mapped default discovery-host synthesis failed the 1000 ps gate on
raster-edge to sampled-UV interpolation. Exact wide combinational signed
arithmetic remains in the legacy UV, color/alpha/depth and gradient paths.
Repair must preserve their widths, signed helper edges, unsigned UV range,
modular intermediate sums and useful steady-state rate.

`PipelinedSignedDivider` performs signed restoring division with one quotient
bit per registered stage. The default numerator is 129 bits, denominator 96
bits and quotient 32 bits, matching signed64 edge times signed32 inverse W
times a zero-extended unsigned32 UV. It truncates toward zero and returns the
exact low quotient bits, including overflow wrap. Zero divisors return zero
with a divide-by-zero flag; this explicit result is not claimed equivalent to
the legacy undefined divide-by-zero expression. The overflow flag describes
magnitude beyond the retained bits. Reset clears every valid owner. Elastic
stage groups retain ordered packets and stable output under stalls.

Four checks cover exhaustive small widths and full-width boundaries/random
cases, input/output II1, stalls, signed limits, oversized quotients and reset.
Default latency is 131 cycles with one request/result per cycle after fill.
The [frozen qualification](performance/mobile-legacy-interpolation-divider-2026-10-22.json)
passes on 1,002 inputs. Default synthesis passes 1000 ps TC/SLVT, 25% I/O,
standard ABC and no retiming: core +329.801 ps, I/O +163.606 ps, zero skipped
instances and no SRAM macros. Mapping uses 172,802 cells with library area
22,813.457220 and sequential area 8,625.236400. This fully parallel divider
trades substantial registered state for exact II1 service.

This is an arithmetic prerequisite. Weighted products, actual scalar/quad
consumer payload/descriptor alignment, gradient/color/depth paths, ownership
drain and full host timing still need integration and qualification. No path
may be deleted, narrowed, serialized below its required rate or screened at a
relaxed clock to turn the failing host into a passing result.
