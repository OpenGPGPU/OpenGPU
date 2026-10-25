# Exact typed resolve timing

`TypedSampleResolve` pipelines packed extraction, numeric decoding and alignment
before accumulating samples in exact signed units of 2^-149. Two 281-bit
carry-save rails retain the full sum. One shared 32-bit adder propagates carry
after the samples and converts negative sums to magnitude. Registered groups of
at most 32 bits supply the first/last-set-bit indices for exact FP32 rounding.
The prepared typed encoder then packs the result.

The block retains every typed format, 1x/2x/4x samples, linear-light sRGB means,
raw integer/depth selection, subnormal RNE, signed zero and nonfinite behavior.
Cancellation drains owned work before reporting its result; held results remain
immutable. It uses no FPU or SRAM macro.

Three numeric checks pass. The original format matrix executes 912 requests;
the original extreme-value and 150-clock completion checks remain. Directed
positive R32F and all-negative RGBA32F 4x cases complete in 94 and 134 clocks.
Every cancellation offset through those results, 34 reset offsets per profile,
live-input corruption and healthy reuse are checked.

Complete standalone ASAP7 TC/SLVT synthesis passes 1000 ps with +199.801 ps
core and +642.948 ps I/O setup slack. The mapped component contains 25,618
standard cells and 3,508.46 µm² of standard-cell area, with no omitted operators
or unresolved drivers. Constraints use 25% I/O delay and no retiming or false
paths. This does not qualify enclosing image-transfer/renderer/host timing,
whole-host resources or product frame rates.

```sh
sbt -J-Xmx4G -Dopengpu.simJobs=2 \
  'testOnly opengpu.graphics.TypedSampleResolveSpec'
sbt -J-Xmx4G \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-typed-sample-resolve .artifacts/typed-resolve-rtl'
CHIPAGENT_DIR="$PWD/../chipagent" ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/typed-resolve-rtl TypedSampleResolve --clock clock \
  --period-ps 1000 --io-delay 0.25 --output .artifacts/typed-resolve-synthesis
```
