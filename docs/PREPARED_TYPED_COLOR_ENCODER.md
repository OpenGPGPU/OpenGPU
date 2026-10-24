# Prepared typed clear values

`PreparedTypedColorEncoder` owns one complete color request at a time. It
captures the format layout, then overlaps component conversion, exact UNORM
product/rounding and 128-bit placement on successive clocks. This supplies a
pipelined clear-value formatter for the parallel tile policy. New requests
wait until the current result is consumed; this is not a vector-II=1 encoder.

One targeted test passes with zero skipped or pending cases: 6,903 numeric
requests across all 36 formats and three invalid format/type requests. Independent
references check masks, saturation, NaNs, signed/unsigned integers, binary16,
raw binary32 and held output. Three adjacent FP32 values at UNORM quantization
boundaries and 765 sRGB transitions retain exact rounding and packed bytes.
Non-sRGB replies appear within seven clocks after admission without stalls.
sRGB uses eight search steps per selected RGB component.

These are component results. Tile clear ownership, SRAM initialization,
whole-renderer timing and frame throughput require separate integration checks.

```sh
sbt -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simJobs=1 \
  'testOnly opengpu.graphics.PreparedTypedColorEncoderSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-prepared-typed-color-encoder .artifacts/prepared-typed-color-encoder-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/prepared-typed-color-encoder-rtl PreparedTypedColorEncoder \
  --stage synthesis --period-ps 1000 --io-delay 0.25 --timeout 14400 \
  --output .artifacts/prepared-typed-color-encoder-synthesis
```
