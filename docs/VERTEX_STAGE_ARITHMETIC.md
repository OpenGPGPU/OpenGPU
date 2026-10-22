# Vertex stage arithmetic

The vertex stage retains the existing descriptor, kernarg, payload and memory
interfaces. Its arithmetic keeps full address overflow and numeric rounding
information while separating long carry and normalization paths.

- Image pitch products use balanced carry-save reduction with a prefix finish
  when `pipelineProducts` is enabled. All 47/49 useful bits survive until bounds
  checks, including malformed 33-bit pitches and 16-bit layer coefficients.
  Existing three-/four-/five-cycle profiles and one-address-per-cycle service
  are unchanged.
- Vertex element, component and exclusive-end sums use prefix adders. Their
  65/66-bit results still reject overflow before any memory request.
- Input conversion negates extracted values modulo their original width using
  a prefix adder and constant masks. Only a signed minimum can exceed the
  normalized positive maximum. This removes variable sign-extension shifts and
  a comparison after negation; all five elastic stages and exact RNE remain.
- Legacy normalized coordinates multiply the low sixteen coordinate bits by
  the extent before wrap/clamp adjustment. Reflection uses
  `ceil((65536-r)*n/256) = 256*n - floor(r*n/256)`, preserving mirrored weights
  without subtraction before multiplication. Clamp saturation and signed
  negatives are selected afterward. The six-stage prepared pipeline and direct
  sampler state transitions are unchanged.
- Vertex, restart, total-batch, local-batch and spilled-batch counters use
  carry-save state with prefix observation. They count the same accepted
  exports/launches, clear on draw admission and wrap modulo 2^64. Five counters
  add 320 state bits. There is no new launch/export queue or observation cycle.
- Image decoding captures the full 80-bit normalized fraction and its leading
  count before shifting. SNORM, normalized depth and EAC components take one
  extra cycle each, or four per texel. Other encodings retain their latency.
  RNE, defaults, compressed-block ownership and held-result behavior remain;
  enclosing fetch/sampling owners drain the added phase on cancellation.

Functional checks include all four address profiles, complete vertex
conversion/fetch, independent coordinate/filter references, depth and EAC
normalization, complete image sampling, and actual shared-CU cancellation and
local/global vertex execution.

**54 distinct directed checks pass**, including 217,680 exact vertex-conversion
cases, 5,982 depth/format queries, all 8,190 EAC normalization cases, cached and
uncached coordinate references, cancellation/fault recovery and cache-enabled
actual two-CU reuse. Host script checks also pass.

```sh
sbt -Dopengpu.simJobs=4 'set Test / parallelExecution := false' \
  'testOnly opengpu.util.CarrySaveEventCounterSpec opengpu.graphics.TextureImageAddressUnitSpec opengpu.graphics.VertexInputConversionPipelineSpec opengpu.graphics.VertexInputFetcherSpec' \
  'testOnly opengpu.graphics.TextureCoordinatePipelineSpec opengpu.graphics.TextureCoordinatePolicySpec opengpu.graphics.TexturePreparedCoordinatePolicySpec' \
  'testOnly opengpu.graphics.TextureImageFormatSpec -- -z "cover depth24 rounding"' \
  'testOnly opengpu.graphics.TextureCompressedImageDecoderSpec -- -z "normalize every EAC11"' \
  'testOnly opengpu.graphics.TextureImageSampleUnitSpec' \
  'testOnly opengpu.graphics.VertexShaderStageSpec -- -z "execute ordered indexed instanced"' \
  'testOnly opengpu.graphics.VertexShaderStageSpec -- -z "drain held launches"' \
  'testOnly opengpu.graphics.VertexShaderStageSpec -- -z "sample vertex textures"' \
  'testOnly opengpu.graphics.VertexShaderStageSpec -- -z "reuse pure indexed"'
```

All six changed components pass 1000 ps TC/SLVT synthesis with 25% I/O,
standard ABC, no retiming/false paths and zero skipped operators. No SRAM
macros occur in these component tops. Areas are synthesis library areas.

| Top/profile | Core / I/O slack (ps) | Cells | Library area |
| --- | ---: | ---: | ---: |
| TextureImageAddressUnit, both pipeline cuts | +32.833 / +333.640 | 23,374 | 3,110.31 |
| VertexInputConversionPipeline | +304.670 / +631.152 | 5,003 | 691.734 |
| VertexInputFetcher, sixteen attributes/bindings | +51.727 / +477.318 | 95,967 | 13,508.2 |
| TextureCoordinatePipeline, policy enabled | +102.839 / +317.379 | 9,605 | 1,311.41 |
| TextureUnit, prepared/overlap/policy enabled | +61.910 / +106.876 | 36,622 | 4,966.2 |
| TextureImageFetchDecoder | +299.372 / +660.229 | 11,154 | 1,449.35 |

Emit the component profiles with `mobile-texture-composed-address`,
`mobile-vertex-input-conversion`, `mobile-vertex-input-fetch`,
`texture-coordinates-policy`, `texture-sampler-prepared-coordinate-policy`
and `mobile-texture-typed-fetch`. For the last directory, qualify the internal
`TextureImageFetchDecoder` top.

Emit `mobile-vertex-shader-reuse` for the complete default vertex-stage screen.
It includes eighteen SRAM instances: ten 256x64 and eight 256x32 macros. Use
the pinned SRAM models and `scripts/qualify_ppa.py --stage synthesis
--period-ps 1000 --io-delay 0.25`, with standard ABC, no retiming/false paths
and no skipped operators. Component timing alone cannot establish the vertex,
whole-host or renderer clock, frame rates, energy, or H0–H8 completion.
