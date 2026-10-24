# Cached FP32 arithmetic elaboration

Scalar and streaming clip clients use `Instantiate` to reuse an FP32 FMA
module definition for each parameter set. The tag width, five arithmetic
stages and result-credit capacity retain their original values. Every hardware
instance keeps independent state, queues, clock/reset connections and traffic.
The [Chisel hierarchy API](https://www.chisel-lang.org/docs/cookbooks/hierarchy)
requires the existing macro-annotation compiler support and public wrapper IO.

The complete four-engine qualification host contains 680 FMA instances,
including 134 scalar clip and 80 streaming clip clients. Reusing these
arithmetic definitions allows its Chisel conversion to finish with a 6 GiB
Java heap. Full-host frame performance and whole-GPU timing have separate gates.

All 32 focused FMA, clip and blend checks pass with a 4 GiB heap and zero failed,
canceled, ignored or pending tests. They retain dense issue rates, exact rational
and FP32 results, flags, rounding, backpressure, flush, cancellation and reuse.
The current scalar clip helper passes complete 1000 ps TC/SLVT synthesis with
25% I/O delay: +159.873 ps core/+487.250 ps I/O, zero skipped/unresolved mappings.
Streaming clip synthesis RTL matches the qualified emission after comments are
removed, retaining +74.477/+346.132 ps. The streaming blend's 26 synthesis files
also match. Verification predicates and bindings are unchanged; assertion
messages move by two source lines. Definition reuse preserves every arithmetic
state and cycle.

```sh
sbt -J-Xmx4G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simHierarchy=Rgba8BlendFmaEngine \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.core.execute.fpu.Fp32FmaLaneSpec opengpu.graphics.ClipFloatStreamingAddMulSpec opengpu.graphics.ClipFloatArithmeticSpec opengpu.graphics.StreamingRgba8BlendSpec opengpu.graphics.TypedColorBlendSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-clip-float-arithmetic .artifacts/reusable-clip-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/reusable-clip-rtl ClipFloatArithmetic --stage synthesis \
  --period-ps 1000 --io-delay 0.25 --output .artifacts/reusable-clip-synthesis
```
