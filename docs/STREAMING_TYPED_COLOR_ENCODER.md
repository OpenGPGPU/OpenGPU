# Streaming typed color encoding

`StreamingTypedColorEncoder` accepts one complete four-component color per
clock. Its ten elastic stages retain request order, tags, format and channel
masks through backpressure. Without stalls, latency is ten clocks and output
interval is one clock. Every accepted request produces one reply; reset flushes
all queued ownership.

The 36 existing formats retain exact UNORM round-to-nearest/even, integer
saturation, binary16 conversion, raw binary32 storage and sRGB thresholds.
Four spatial converters overlap UNORM preparation/product/rounding with eight
registered sRGB search steps. Invalid format/type pairs return status 1 with
zero value, mask and byte count. Unselected and absent channels have zero masks.
Integer bounds are captured with the format layout. UNORM shifts and rounding
use separate existing stages, preserving the external latency and interval.

Two tests pass with 7,459 completed requests: all formats and channel masks,
neighboring FP32 values at every UNORM and sRGB transition, invalid IDs/types,
dense admission, input gaps, 513 held-output clocks, 385 input stalls and reset
discarding seven queued requests. The dense 6,691-request stream completes in
6,701 clocks.

Complete component synthesis passes at 1,000 ps with 25% I/O delay: core slack
+256.037 ps and I/O slack +211.835 ps, with zero skipped or unresolved mappings.
Pixel-owner throughput, whole-GPU timing and product frame rates require their
own integration measurements.

```sh
sbt 'testOnly opengpu.graphics.StreamingTypedColorEncoderSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-streaming-typed-encoder .artifacts/streaming-typed-encoder-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/streaming-typed-encoder-rtl StreamingTypedColorEncoder \
  --stage synthesis --period-ps 1000 --io-delay 0.25 --timeout 1800 \
  --output .artifacts/streaming-typed-encoder-synthesis
```
