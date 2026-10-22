# Vertex input arithmetic pipelines

Vertex input conversion uses five elastic stages: component extraction,
signed magnitude and normalized clamping, fractional expansion, leading-bit
normalization, and round-to-nearest/even packing. Two-entry queues break both
data and ready paths. The standalone converter accepts one component per cycle
and has five-cycle unstalled latency. Its ten slots retain accepted work and
held output bits in order.

All [25 input format contracts](VERTEX_INPUT_FORMATS.md) remain unchanged,
including raw FP32/integers, half exceptional values, signed normalized minima,
packed REV component widths and fixed-point rounding. The normalized path
retains the original 64-bit repeated fraction. The integer path aligns its
magnitude into the same normalization/rounding datapath with its own exponent.

The fetcher captures an index word before restart/signed-bias validation,
registers the selected attribute and binding before address work,
captures the conversion format with its component word, and precomputes terminal
vertex/instance/divisor counts on draw admission. This separates descriptor
selection and loop-bound arithmetic from conversion, address checks and counter
updates. Descriptor formats, native ABI offsets and memory transaction counts
are unchanged.

Component start and exclusive-end addresses are captured together before bounds
comparisons, cache lookup and read/hit counter updates. Both additions retain
all overflow bits; checks still reject out-of-range spans before any transfer.
Index checks and cache-hit decisions are captured before use. Counter increments
are prepared before a request, cache hit or vertex offer; the actual handshake
copies that value exactly once. Cancellation does not count an unaccepted offer.

The fetcher remains a serial component owner; the converter's initiation
interval is not a vertex throughput claim. Index decoding, attribute/binding
selection, address capture and component conversion handshakes add cycles.
Draw, staging and complete frame budgets
must measure those costs. Cancellation suppresses new conversions, drains every
accepted conversion as well as memory responses, and retains an already offered
vertex record. Completion cannot permit draw reuse while old conversion work
remains.

The floating texture axis also separates wide alignment from signed arithmetic.
Six registered chunks of at most 32 bits retain the full 170-bit subtraction,
including all 149 fractional bits. Carries combine from captured local sums
before final tap selection. The complete five-bit offset domain for extents
1–7 is decoded at elaboration, avoiding unmapped remainder operators without
changing valid offset results or invalid-input status. Its ten two-entry queues
give ten-cycle latency, one-axis-per-cycle initiation and twenty buffered axes.
Parents still drain every accepted tag after cancellation.

## Reproducible checks

The converter is checked against an independent integer-rational reference for
217,680 numeric cases, including exhaustive half and normalized 8/16-bit inputs,
packed components and wide rounding boundaries. Separate random stalls check
ordered and immutable results. Fetcher checks cover all sixteen attributes,
nonidentity bindings, indices/restart, divisors, defaults, bounds, memory faults,
the 4 GiB exclusive limit and cancellation through conversion and held outputs.
The coordinate reference covers 135,760 queries across all FP32 exponents,
all nonzero 14-bit extents, offsets, wrap/filter modes and backpressure.

Nineteen distinct directed checks pass across conversion, fetch, coordinates,
selected-level/general sampling, shared-CU local/global vertex execution and
cache-enabled native indirect drawing. Sampling covers required late faults,
held offers, canceled child work and healthy recovery. Native coverage retains
GPU publication, denied parameter translation, exact MRT/depth/resolve images,
guards and released payload/VM ownership. Three generated ABI checks and the
test-selection/catalog checks also pass.

```sh
sbt -Dopengpu.simJobs=4 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.VertexInputConversionPipelineSpec opengpu.graphics.VertexInputFetcherSpec opengpu.graphics.TextureFloatAxisPipelineSpec' \
  'testOnly opengpu.graphics.TextureImageLevelSampleUnitSpec -- -z "prune zero-weight taps"' \
  'testOnly opengpu.graphics.TextureImageSampleUnitSpec -- -z "sample integer texel offsets"' \
  'testOnly opengpu.graphics.VertexShaderStageSpec -- -z "execute converted half"' \
  'testOnly opengpu.graphics.NativeTypedGraphicsHostAxiSpec -- -z "consume GPU-published indirect arrays and elements through translated native images local=true"'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-vertex-input-conversion .artifacts/conversion-rtl' \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-vertex-input-fetch .artifacts/fetch-rtl' \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-texture-float-axis .artifacts/axis-rtl' \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-vertex-shader-reuse .artifacts/vertex-rtl'
```

Use `scripts/qualify_ppa.py` on each emitted directory/top with synthesis,
1000 ps and 25% I/O. Raw reports and intermediate attempts stay local under
`.artifacts/`. Component results qualify their exact emitted top only; enclosing
vertex/host timing, workload throughput and the full H0–H8 gates require their
own evidence.

The standalone converter passes 1000 ps TC/SLVT synthesis with 25% I/O:
core +39.288 ps and I/O +632.513 ps. The complete ten-stage coordinate pipeline
passes the same gate at +127.689/+581.296 ps. The full sixteen-attribute/binding
fetcher passes at +2.351/+477.318 ps; its core margin is narrow. All three use
standard ABC, no retiming or false paths, and have zero skipped operators.
These component results do not qualify the enclosing vertex stage or host clock.
