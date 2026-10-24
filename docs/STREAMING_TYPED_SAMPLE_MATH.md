# Native sample arithmetic

`StreamingTypedSampleMath` accepts four RGBA8+D24S8 sample records per clock.
Registered depth conversion, stencil/depth comparisons and four spatial blend
pipelines preserve each packet's token, masks and front/back state. Output
merges preserve inactive samples and unselected channels in their old words.
The dense fixture has a 40-clock first reply and consecutive input/output.

Outputs describe planned effects. The enclosing worker owns word reservations
and counts actual writes only after memory acknowledgements. Cancellation is
sticky within a cohort and suppresses newly formed plans while accepted
packets drain. Already held output remains immutable; the worker must suppress
new writes from an older held plan after global cancellation. Begin requires
complete drain and starts a new cohort.

Three tests pass: 1,125 dense packets, sparse masks and front/back/disabled
state through stalls, plus cancellation, held-output preservation and healthy
reuse. Values and packed bytes match independent FP32/depth/stencil models.
The retimed 80-FMA component passes complete synthesis at 1,000 ps with 25% I/O
delay: core slack +101.899 ps, I/O slack +468.933 ps, zero skipped or unresolved
mappings. The older pre-retimed depth path missed the same clock by 92.341 ps.
Whole-backend timing, acknowledged memory effects and product frame rates
remain separate integration gates.

```sh
sbt -Dopengpu.simHierarchy=Rgba8BlendFmaEngine -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simJobs=1 \
  'testOnly opengpu.graphics.StreamingTypedSampleMathSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-streaming-sample-math .artifacts/streaming-sample-math-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/streaming-sample-math-rtl StreamingTypedSampleMath \
  --stage synthesis --period-ps 1000 --io-delay 0.25 --timeout 14400 \
  --output .artifacts/streaming-sample-math-synthesis
```
