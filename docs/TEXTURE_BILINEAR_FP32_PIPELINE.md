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

The first frozen 825-input candidate (`a4e5fc7`) passed 23 of 25 checks.
The 192-group input/output II1 and 384-group backpressure checks passed, as did
the thirteen cancellation cuts. A new held-result flush test exposed an FMA
wrapper bug, and an arbitrary FP32 numeric case disagreed with the software
reference. The wrapper now explicitly clears its public valid register on flush;
the numeric mismatch is under investigation. Neither full numeric qualification
nor synthesis is claimed. Native banked integration remains open.
