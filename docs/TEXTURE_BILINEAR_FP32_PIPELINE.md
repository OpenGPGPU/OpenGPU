# Native FP32 bilinear filter pipeline

`TextureBilinearFilterPipeline` accepts four decoded FP32 RGBA texels, two
unquantized FP32 axis weights and a 32-bit caller tag. Axis zero varies fastest;
the request's axis count must be two. This is a component for native banked
sampling integration. It does not accept coordinates or descriptors and is not
yet connected to shared-CU texture requests.

Each pair computes the existing native filter operation:

```
inverse = RNE(1 - weight)
product = RNE(a * inverse)
result  = FMA(weight, b, product)
```

Zero weights (including negative zero), weight one and equal input bit patterns
retain the existing endpoint/duplicate bypasses. This preserves selected NaN
payloads and infinities. Active weights outside [0,1], nonfinite weights and an
axis count other than two produce a format fault with zero RGBA. Weights are
never converted to Q16. The older banked weighted-sum normalizer remains a
separate numeric contract.

Two parallel pairs reduce the X axis; one pair then reduces Y. Each axis has a
shared inverse lane, four product and four fused-sum lanes per pair, and three
ordered metadata queues of sixteen entries. The complete tree has 26 FMA lanes.
`Fp32FmaLane` now accepts a trailing `resultEntries` parameter. Its existing
default remains five; this pipeline uses sixteen credits so storage reservation
covers the arithmetic and response-queue round trip. The input owner bounds
outstanding groups at 128; actual admission also depends on the internal queues.

Cancellation closes admission through complete epoch drain. Accepted arithmetic
is never flushed. Hidden rows retain cancellation through an epoch bit, while a
format fault keeps priority. One public result queue preserves an already offered
completion under cancellation and backpressure. Work remains busy until every
public completion retires. New work can enter after the drained epoch reopens.

The [825-input qualification](performance/mobile-texture-bilinear-fp32-2026-10-21.json)
now passes 46 fresh checks across six suites and two RTL emissions. These include 788 bilinear
numeric rows, 2,176 changing-input FMA reference rows, 192 complete groups at
input/output II1, 384 backpressured groups and thirteen cancellation cuts. Two
cuts offer until actual backpressure; every cut drains and reuses the epoch.

Earlier candidates exposed a held-result flush bug and missing subtraction,
sticky-bit and infinity-sign delays in the existing FMA pipeline. Directed
negative evidence is retained. Isolated requests with unchanged input bits hid
the rounding failure; poisoning invalid inputs and streaming unrelated rows
reproduced it. The corrected pipeline preserves the same five arithmetic stages.

The default FMA logic has changed, so old native endpoint timing is not carried.
The corrected default FMA passes synthesis at 1000 ps including I/O, with
+210.909/+434.695 ps slack, 11,091 fully mapped cells and 1,366.306380 mapped
library area. The complete filter screen is still running under the same
TC/SLVT, 25% I/O, standard ABC constraints, without retiming or place/route.
The eighteen vector FMA/reduction wrapper regressions also pass. Native raw-tap forwarding,
texel decode, resource/coordinate admission, shared-CU routing and whole-native
precision/rates/coherence remain open. This component rate is measured from
already decoded texels and weights, not from shader instructions.
