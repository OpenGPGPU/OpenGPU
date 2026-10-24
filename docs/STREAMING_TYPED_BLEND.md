# Streaming general typed blending

`StreamingTypedColorBlend` accepts one typed RGBA request per clock and returns
one result per clock after a 31-clock unstalled latency. A streaming destination
decoder feeds eight factor-inversion, eight product and four combination FP32
pipelines. Products and sums retain separate RNE operations and the serial
service's zero/one shortcuts. Normalized/sRGB targets clamp at the existing
boundaries; floating targets retain their extended values. Integer targets and
disabled blending return the original source through the same owned pipeline.

Each request retains its format, tag, state and arithmetic flags. Admission has
64 credits. Cancellation blocks fresh admission until the accepted cohort
drains; an already offered reply remains immutable.

Five tests pass: 557 serial/reference comparisons cover formats, factors,
equations, special values and cancellation; 672 queued requests prove dense
mixed-format rate, held cancellation and healthy reuse. Another 128 requests
are first evaluated serially, then replayed concurrently under backpressure:
seven distinct exception flags remain exact through 75 held-reply clocks,
19 input-stall clocks and 57 simultaneous owners. No tests are failed,
canceled, ignored or pending.

Standalone synthesis at 1,000 ps with 25% I/O budgets passes at +125.366 ps core
and +472.864 ps I/O margin, with zero skipped or unresolved mappings. This
qualifies the blend service. Pixel integration still needs enough concurrent
sample/location owners and packing/memory service to sustain this rate;
whole-renderer clock and FPS remain separate gates.

```sh
sbt -J-Xmx4G -Dopengpu.simJobs=1 -Dopengpu.simHierarchy=Rgba8BlendFmaEngine \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.StreamingTypedColorBlendSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-streaming-typed-blend .artifacts/streaming-blend-rtl'
python scripts/qualify_ppa.py .artifacts/streaming-blend-rtl StreamingTypedColorBlend \
  --stage synthesis --period-ps 1000 --io-delay 0.25 \
  --output .artifacts/streaming-blend-synthesis
```
