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

## Weighted UV and scalar consumer checkpoint

`PipelinedSignedProduct` registers 16-bit digit products, carry-save reduction
levels and the final prefix addition. Exact signed64x32 and signed96x33 products
take six/eight cycles and sustain II1. `PipelinedTexUVInterpolator` composes
three edge/inverse-W products, six unsigned-UV products, the original modular
96-bit denominator and 129-bit numerator sums, and two exact dividers. Ordered
metadata queues align the payload; default latency is 146 cycles with II1.

The production scalar `TexturedFragStage` now queues arithmetic independently
of the sampler and output stalls. Each admission captures fragment data,
per-sample depths and the complete texture descriptor. In particular, the
legacy sampler reads wrap mode after admission, so a dedicated register holds
that policy until the next owned sample. The first version connected it to
the advancing UV queue and failed both original high-unsigned-UV regressions.
Those tests remain unchanged; the corrected consumer passes them and a stronger
24-fragment-per-mode stream alternating CLAMP/REPEAT and poisoning live
descriptor pins after admission. Every output retains its original edges,
coverage, depths and color/alpha modulation; drained waits for the final output.

The [1,008-input checkpoint](performance/mobile-legacy-uv-pipeline-2026-10-22.json)
passes twenty-one checks: fourteen arithmetic/consumer cases and seven renderer
regressions. The latter include exact 1x texture and 4x texture/blend/stencil
images through shared L2. The default UV numeric emission uses a 32-bit metadata
tag and passes the same 1000 ps TC/SLVT, 25% I/O gate with standard ABC and
no retiming: core +108.792 ps, I/O +448.657 ps and zero skipped instances.
It maps 588,499 cells, library area 72,792.443340, sequential area 24,935.299200,
85,512 flops and zero SRAM macros. This substantial full-width arithmetic and
metadata state is an explicit area cost. It cannot qualify the wider actual
scalar metadata/sampler, still-combinational quad helpers, affine
color/depth/sample gradients or the complete host. Those paths remain required.
