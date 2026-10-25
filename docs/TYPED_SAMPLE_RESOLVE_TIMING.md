# Exact typed resolve timing

`TypedSampleResolve` pipelines packed extraction, numeric decoding and alignment
before accumulating samples in exact signed units of 2^-149. Two 281-bit
carry-save rails retain the full sum. One shared 32-bit adder propagates carry
and converts negative sums to magnitude. Registered groups supply first/last
set-bit indices for exact FP32 rounding; the prepared encoder packs the result.

Raw binary32 retains all nine carry words. UNORM and sRGB need only words 3–4;
binary16 needs words 3–5. Their finite decoded terms have zero bits 95:0, and
four terms fit the selected signed window. A preparation clock captures the
exact carry from the omitted low words: the two low rails sum to either zero
or 2^96, so a nonzero low sum rail identifies that carry. High words are sign
extended. Negative binary16 magnitude conversion uses the same bounded window.
No precision or rounding bits are discarded.

Every typed format, 1x/2x/4x samples, linear-light sRGB means, raw integer/depth
selection, subnormal RNE, signed zero and nonfinite behavior remain supported.
Cancellation drains owned work; held results remain immutable. The block uses
no FPU or SRAM macro.

Four numeric cases and seventeen surrounding transfer cases pass. The original
format matrix executes 912 requests; 156 additional bounded-format requests
cover signed half cancellation, extremes, subnormals, nonfinite values and
packed normalized colors. All five directed profiles cover every cancellation
offset, 34 reset offsets, poisoned live operands and healthy reuse. The original
150-clock completion guard remains.

| Directed 4x profile | Clocks from acceptance to output |
|---|---:|
| RGBA8 | 70 |
| sRGB8 + UNORM alpha | 94 |
| Negative RGBA16F | 90 |
| Full-range positive R32F | 94 |
| Full-range negative RGBA32F | 134 |

These are output latencies, not sustained issue rates. The binary32 latency
checks retain their preceding values.

Complete standalone ASAP7 TC/SLVT synthesis passes 1000 ps with +127.643 ps
core and +651.959 ps I/O setup slack. After synthesis repair, the component has
27,286 standard cells and 3,735.64 µm² of standard-cell area, with no omitted
operators or unresolved drivers. Constraints use 25% I/O delay and no retiming
or false paths. Enclosing image-transfer/renderer/host timing, whole-host
resources and product frame rates require separate qualification.

```sh
sbt -J-Xmx4G -Dopengpu.productQualification=0 -Dopengpu.simJobs=2 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simSeparateBuild=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TypedSampleResolveSpec opengpu.graphics.TypedTileImageTransferSpec'
sbt -J-Xmx4G \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-typed-sample-resolve .artifacts/typed-resolve-rtl'
CHIPAGENT_DIR="$PWD/../chipagent" ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/typed-resolve-rtl TypedSampleResolve --clock clock \
  --period-ps 1000 --io-delay 0.25 --output .artifacts/typed-resolve-synthesis
```
