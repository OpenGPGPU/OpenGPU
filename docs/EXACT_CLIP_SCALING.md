# Exact extended-exponent scaling

`ClipFloatArithmetic` divides a finite nonzero operand by a normalized signed
power of two by preserving its significand and adjusting sign/exponent. The
request reaches its output in three clocks, retaining the registered packing,
cancellation and response ownership states. Illegal operands, extended range
errors, zero and special values retain their checked behavior; general division
continues through the existing service. This reduces setup latency without
changing separately rounded interpolation arithmetic.

All 35 tests in the arithmetic, clipping, viewport, polygon-offset and scalar
fragment-interpolation suites pass, with zero failed, canceled, ignored or
pending cases. Coverage includes 96 independent rational scaling vectors,
both operand signs, exponent limits, subnormal packing, cancellation at each
ownership boundary, immutable held responses and healthy general division.

Fresh emission matches all ten hardware RTL files of the complete passing
synthesis byte-for-byte. At 1000 ps, TC/SLVT and 25% I/O delay, core/I/O setup
margins are +115.910/+487.394 ps. Mapping contains 15,372 standard cells, library
area 2,054.13 µm², no SRAM macros and zero skipped or unresolved instances.
This qualifies the scalar arithmetic component, not a renderer clock or FPS.

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.ClipFloatArithmeticSpec opengpu.graphics.PrimitiveClipStageSpec opengpu.graphics.Fp32ViewportStageSpec opengpu.graphics.PolygonDepthOffsetSpec opengpu.graphics.Fp32FragmentInterpolatorSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-clip-float-arithmetic .artifacts/exact-clip-scale-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/exact-clip-scale-rtl ClipFloatArithmetic --stage synthesis \
  --period-ps 1000 --io-delay 0.25 --output .artifacts/exact-clip-scale-synthesis
```
