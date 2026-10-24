# Streaming RGBA8 packing

`StreamingRgba8Encoder` uses four spatial channel converters and four elastic
stages: FP32 classification, exact significand multiplication by 255,
round-to-nearest/even, and masked RGBA8 packing. With continuous output
readiness it accepts and retires one complete pixel per clock after four
clocks of warm-up. Backpressure retains up to four owned pixels and their
masks, tags and error status; an offered result remains immutable.

The integer conversion preserves saturation, negative/NaN-to-zero behavior,
all UNORM8 rounding transitions and disabled-channel zeroing. Invalid requests
produce zero value, mask and byte count with error status. The accompanying
`RoutedTypedColorEncoder` drains a format cohort before switching between this
pipeline and the general encoder, retaining all 36 internal formats.

Three executed tests cover a 4,096-pixel continuous stream, all 255 rounding
boundaries and their adjacent FP32 values with every channel enabled, all
channel masks, special values, full backpressure, invalid requests, reuse and
ordered mixed-format cohorts. No tests are cancelled, ignored or pending.
The suite is included in the bounded required PR gate.

Complete standalone synthesis passes at 1,000 ps, ASAP7 TC/SLVT and 25% I/O
budgets: +437.105 ps core and +392.007 ps I/O setup margin, with zero skipped
or unresolved mappings. This qualifies the packer alone. It is a component
capacity result; integration, overlapping quad ownership and SRAM bandwidth
remain necessary for four complete RGBA8+D24S8 updates per cycle per engine.
No whole-renderer frequency or frame-rate result is established here.

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.StreamingRgba8EncoderSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-streaming-rgba8-encoder .artifacts/rgba8-packing-rtl'
python scripts/qualify_ppa.py .artifacts/rgba8-packing-rtl StreamingRgba8Encoder \
  --stage synthesis --period-ps 1000 --io-delay 0.25 \
  --output .artifacts/rgba8-packing-synthesis
```
