# Component-parallel typed blending

`ParallelTypedColorBlend` uses four FP32 arithmetic owners and one shared typed
destination decoder for RGBA. Products and sums retain separate RNE rounding,
zero/one shortcuts, normalized clamping, integer bypass and arithmetic flags.
One request owns the group through its held reply and cancellation drain.
This improves request latency; it does not accept one request per clock.

Three comparison tests execute 557 paired requests against the serial service
and an independent numeric model: 36 formats, all 225 factor pairs, five blend
equations, special values, poisoned inputs, held replies and cancellation during
arithmetic or after completion. Four existing serial/decoder tests also pass,
including every FP16 pattern and normalized/sRGB conversion checks. No tests
are failed, canceled, ignored or pending. A separate decoder admission case
checks all 64 format IDs against poisoned live inputs and held replies; the
shared numeric fixture source remains unchanged.

For the measured blend state, RGBA8, RGB8, RGBA16F and SRGB8_ALPHA8 latency falls
from 187 to 54 clocks with exact values and flags. Standalone synthesis at
1,000 ps with 25% I/O budgets passes at +140.441 ps core/+489.487 ps I/O margin,
with zero skipped or unresolved mappings. Preparing immutable packed destination
fields at decoder admission preserves its interface and decode latency.
These component results establish neither complete pixel throughput nor FPS.

```sh
sbt -J-Xmx4G -Dopengpu.simJobs=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.ParallelTypedColorBlendSpec opengpu.graphics.TypedColorBlendSpec opengpu.graphics.TypedColorDecoderAdmissionSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-parallel-typed-blend .artifacts/parallel-blend-rtl'
python scripts/qualify_ppa.py .artifacts/parallel-blend-rtl ParallelTypedColorBlend \
  --stage synthesis --period-ps 1000 --io-delay 0.25 \
  --output .artifacts/parallel-blend-synthesis
```
