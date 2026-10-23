# Exact extended-exponent scaling

`ClipFloatArithmetic` divides a finite nonzero operand by a normalized signed
power of two by preserving its significand and adjusting sign/exponent. The
request reaches its output in three clocks, retaining the registered packing,
cancellation and response ownership states. Illegal operands, extended range
errors, zero and special values retain their checked behavior; general division
continues through the existing service. This reduces setup latency without
changing separately rounded interpolation arithmetic.

All 37 tests in the arithmetic, compact-packing, clipping, viewport,
polygon-offset and scalar fragment-interpolation suites pass, with zero failed,
canceled, ignored or pending cases. Coverage includes 96 independent rational scaling vectors,
both operand signs, exponent limits, subnormal packing, cancellation at each
ownership boundary, immutable held responses and healthy general division.

The scalar service uses one aligned guard/sticky path for underflow packing,
preserving its response latency. A differential check matches every output bit
and status flag against the existing packer on 1,920 boundary, special and
malformed inputs, including both signs and rounding modes. Other callers retain
the existing packing path; both prepared-owner variants have unchanged generated
logic after excluding source comments.

Complete synthesis at 1000 ps, TC/SLVT and 25% I/O delay passes core/I/O setup
by +159.873/+487.250 ps. Mapping contains 15,212 standard cells, library area
2,036.77 µm², no SRAM macros and zero skipped or unresolved instances.
This qualifies the scalar arithmetic component, not a renderer clock or FPS.

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.ClipFloatArithmeticSpec opengpu.graphics.ClipFloatCompactPackingSpec opengpu.graphics.PrimitiveClipStageSpec opengpu.graphics.Fp32ViewportStageSpec opengpu.graphics.PolygonDepthOffsetSpec opengpu.graphics.Fp32FragmentInterpolatorSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-clip-float-arithmetic .artifacts/exact-clip-scale-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/exact-clip-scale-rtl ClipFloatArithmetic --stage synthesis \
  --period-ps 1000 --io-delay 0.25 --output .artifacts/exact-clip-scale-synthesis
```
