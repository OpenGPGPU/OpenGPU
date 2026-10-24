# Streaming RGBA8 blend arithmetic

`StreamingRgba8Blend` accepts and retires one complete RGBA8 pixel per clock
through twenty FP32 arithmetic pipelines: eight factor inversions, eight products
and four combinations. Products and combinations retain separate FP32 RNE steps,
zero/one bypasses, arithmetic flags and the serial service's blend semantics.
The shared `TypedColorBlendIO` also retains the existing serial implementation.
The arithmetic component and renderer selection have separate qualification gates.

Twenty independent FMA instances reuse one cached Chisel definition during
elaboration. Scala macro annotations expose the wrapper ports to those
instances; each instance retains its own arithmetic state and verification.

Seven executed checks pass with a 4 GiB Java heap and zero skipped or pending tests. A 1,536-pixel
dense stream has admission/response interval one and 29-clock initial latency.
It covers all 225 factor pairs, five equations, separate alpha, UNORM8 destination
codes and special source values. Nineteen serial comparisons preserve exact FP32
flags. Backpressure fills 57 owned slots; cancellation drains accepted pixels,
retains held responses and permits healthy reuse. Existing decoder/blend checks
retain all 65,536 binary16 patterns, all 36 formats, signed zero and nonfinite
values, normalized/sRGB interpretation, integer bypass and invalid controls.

Complete standalone synthesis passes 1,000 ps including 25% I/O delay, with
+125.913 ps core/+455.902 ps I/O slack and zero skipped/unresolved mappings.
All 26 synthesis RTL files match the qualified snapshot after removing source
comments. Verification predicates and bindings also match; only assertion
source-line diagnostics change. This is one arithmetic service; complete
pixel-engine timing, canonical SRAM ownership,
renderer throughput and 720p/30 FPS require their own qualification.

```sh
sbt -J-Xmx4G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simHierarchy=Rgba8BlendFmaEngine \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.StreamingRgba8BlendSpec opengpu.graphics.TypedColorBlendSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-streaming-rgba8-blend .artifacts/streaming-blend-rtl'
python scripts/qualify_ppa.py .artifacts/streaming-blend-rtl StreamingRgba8Blend \
  --stage synthesis --period-ps 1000 --io-delay 0.25 \
  --output .artifacts/streaming-blend-synthesis
```
