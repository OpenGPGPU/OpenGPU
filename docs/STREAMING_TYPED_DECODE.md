# Streaming typed color decode

`StreamingTypedColorDecoder` accepts one packed RGBA vector per clock and
returns its four decoded components two clocks later without stalls. Two
elastic stages retain each format, tag and invalid-format result through
backpressure. Packed extraction precedes four spatial numeric expanders.
There is no cancellation interface; consumers must drain accepted work.

Two tests pass: 67,794 consecutive vectors include all FP16 patterns in all
four channels, every supported normalized code, sRGB codes, all 36 formats and
all 28 invalid format IDs. A further 768 mixed-format requests exercise input
gaps, 610 held-reply clocks and bounded two-entry ownership. Values use an
independent CPU reference. No tests are failed, canceled, ignored or pending.

Standalone synthesis at 1,000 ps with 25% I/O budgets passes at +269.093 ps core
and +457.744 ps I/O margin, with zero skipped or unresolved mappings. Decoder
throughput alone establishes neither blend/pixel throughput nor renderer FPS.

```sh
sbt -J-Xmx4G -Dopengpu.simJobs=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.StreamingTypedColorDecoderSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-streaming-typed-decoder .artifacts/streaming-decode-rtl'
python scripts/qualify_ppa.py .artifacts/streaming-decode-rtl StreamingTypedColorDecoder \
  --stage synthesis --period-ps 1000 --io-delay 0.25 \
  --output .artifacts/streaming-decode-synthesis
```
