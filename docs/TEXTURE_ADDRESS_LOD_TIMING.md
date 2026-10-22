# Sampling arithmetic and vertex staging counters

Texture LOD multiplication, composed image-address sums and vertex staging byte
counters had long arithmetic paths in the enclosing vertex stage. These changes
retain their numeric, address, status and handshake contracts without adding
request latency or changing descriptor layouts.

## Exact arithmetic

The LOD owner retains forty Q1.39 binary-log iterations and one final RNE FP32
packing. For `x = lo + hi * 2^20`, its exact 80-bit square is
`lo^2 + (lo * hi) * 2^21 + hi^2 * 2^40`. Three 20x20 products replace two 20x40
products; the disjoint squares and shifted cross product feed the existing
prefix adder. A prefix negation prepares the packed magnitude. The final
logarithm iteration captures sign/zero from the updated fraction before
normalization. Selection, iteration count, cancellation and output latency stay
the same, including zero rho, extended exponents and the min/mag boundary.

Composed addressing uses balanced carry-save reduction followed by one prefix
addition for each span/address sum. The reduction width accommodates every
original unsigned operand, including malformed views; full useful product and
sum bits survive until bounds checks. Selected-end arithmetic includes the byte
count directly, avoiding another serial addition. Only successful spans within
the exclusive 4 GiB limit publish a narrowed address. The three-cycle default
and optional four-/five-cycle profiles retain their existing register counts,
ordered replies, backpressure and one-address-per-cycle steady throughput.

Vertex read/write byte counters use the existing 64-bit carry-save accumulator
with prefix observation. Counts change on the same accepted staging requests,
retain modular wrap and draw-clear priority, and return through the same
completion fields. Each counter uses two 64-bit state words instead of one;
the two counters add 128 state bits in total. No observation cycle is added.

## Validation and synthesis scope

Four counter tests check consecutive updates, full-width wrap and clear
priority. Three LOD tests cover 3,207 magnitude/extent cases, 4,617 monotonicity
and malformed-input cases, and 170 cancellation/reuse cuts. Seven address
tests cover exact legal/fault boundaries and all four pipeline profiles with
512-request stalled streams each. Two actual two-CU vertex checks retain
indexed output order, instance/restart boundaries, cache savings, cancellation,
fault drain and reuse through local/global payloads. All thirteen complete
sampler tests also pass on the combined changes, including late faults and
held results: **29 distinct directed checks** in total. Host script tests pass.

```sh
sbt -Dopengpu.simJobs=4 'set Test / parallelExecution := false' \
  'testOnly opengpu.util.CarrySaveEventCounterSpec opengpu.graphics.TextureGradientLodUnitSpec opengpu.graphics.TextureImageAddressUnitSpec' \
  'testOnly opengpu.graphics.VertexShaderStageSpec -- -z "reuse pure indexed"' \
  'testOnly opengpu.graphics.TextureImageSampleUnitSpec'
```

All three components pass 1000 ps TC/SLVT synthesis, including 25% I/O, standard
ABC, no retiming/false paths and zero skipped operators. No SRAM macros occur
in these component tops.

| Emitted top/profile | Core / I/O slack (ps) | Cells | Library area |
| --- | ---: | ---: | ---: |
| TextureGradientLodUnit | +169.009 / +480.095 | 11,168 | 1,513.30 |
| TextureImageAddressUnit, both pipeline cuts | +129.805 / +333.006 | 25,297 | 3,417.10 |
| CarrySaveAccumulator, 64-bit prefix observation | +672.237 / +95.247 | 996 | 127.21 |

At the same settings the prior LOD component fails core setup at −113.998 ps
with library area 1,704.74; final area decreases 11.23%. Prior composed
addressing fails at −92.581 ps with area 3,000.35; current area increases 13.890%.
The current [full-width pitch-product repair](VERTEX_STAGE_ARITHMETIC.md)
registers compressed rows before prefix finishing at the existing optional
cut, preserving latency while increasing stored data. Its enclosing-stage
gate remains separate.
These are synthesis library areas, not placed/routed area or energy estimates.

Emit `mobile-texture-gradient-lod`, `mobile-texture-composed-address` and
`mobile-vertex-byte-counter` through `EmitPpaRtl`. Qualify each directory/top
with `scripts/qualify_ppa.py --stage synthesis --period-ps 1000 --io-delay 0.25`.
Keep raw reports local under `.artifacts/`.

Passing component screens do not qualify enclosing response routing, the
vertex/host clock, frame rates, energy or H0–H8. Those require their full gates.
