# Vertex stage arithmetic

The vertex stage retains the existing descriptor, kernarg, payload and memory
interfaces. Its arithmetic keeps full address overflow and numeric rounding
information while separating long carry and normalization paths.

- Image pitch products use balanced carry-save reduction with a prefix finish
  when `pipelineProducts` is enabled. All 47/49 useful bits survive until bounds
  checks, including malformed 33-bit pitches and 16-bit layer coefficients.
  The existing optional cut stores compressed rows before prefix finishing,
  with wider register data in place of captured coefficients.
  Existing three-/four-/five-cycle profiles and one-address-per-cycle service
  are unchanged.
- Vertex element, component and exclusive-end sums use prefix adders. Their
  65/66-bit results still reject overflow before any memory request.
- Private index address, bounds and cache-hit registers capture on the existing
  preparation phase independently of cancellation. A stopped task drains before
  consuming those values, keeping cancellation out of their arithmetic enables.
  No phase, memory transfer, launch/export queue or state bit is added.
- Global staging-clear endpoints use 26-bit line indices and prefix adders.
  Admission requires a 64-byte-aligned scratch base. Carry-save reduction
  combines the base, relative exclusive end and last-line correction before
  one prefix finish, avoiding serial add/decrement paths. The independent full exclusive prefix end retains
  its 33rd bit for admission and bounds checks. Byte masks preserve adjacent
  input/uniform values, and clearing never wraps into address zero at the
  allocation boundary. No phase, state bit or transfer is added.
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
- Texture completion uses the existing accepted instruction and reply debts.
  Every memory offer must retain an accepted instruction owner, checked by an
  assertion. Removing the redundant sampler memory-offer term keeps its state
  cone out of completion readiness. Unaccepted scalar/vector handoffs and
  accepted replies still drain. No state, phase, transfer or latency is added.
- Image decoding captures the full 80-bit normalized fraction and its leading
  count before shifting. SNORM, normalized depth and EAC components take one
  extra cycle each, or four per texel. Other encodings retain their latency.
  RNE, defaults, compressed-block ownership and held-result behavior remain;
  enclosing fetch/sampling owners drain the added phase on cancellation.

Functional checks include all four address profiles, complete vertex
conversion/fetch, independent coordinate/filter references, depth and EAC
normalization, complete image sampling, and actual shared-CU cancellation and
local/global vertex execution.

**69 distinct directed checks pass**, including twelve multiplier checks of
both completed products and compressed rows, 217,680 exact vertex-conversion
cases, 5,982 depth/format queries, all 8,190 EAC normalization cases, cached and
uncached coordinate references, cancellation/fault recovery and cache-enabled
actual two-CU reuse. Host script checks also pass.
The global staging checks cover an allocation ending at 4 GiB, partial first
and last clear lines, preserved uniform tail, offered writes and late faults.
The clear-line helper additionally passes 7,360 independent full-integer byte
range comparisons. Actual global execution checks four scratch bases crossing
address carry boundaries, including the exclusive 4 GiB allocation end; all
twenty invalid-layout cases still reject before input access. The fast helper
suite is included in the required PR catalog.
Indexed cancellation also covers 72 phase offsets across 8-/16-/32-bit indices,
preserved accepted record prefixes, owned request drain and healthy reuse.

```sh
sbt -Dopengpu.simJobs=4 'set Test / parallelExecution := false' \
  'testOnly opengpu.util.CarrySaveEventCounterSpec opengpu.util.CarrySaveMultiplierSpec opengpu.graphics.TextureImageAddressUnitSpec opengpu.graphics.VertexInputConversionPipelineSpec opengpu.graphics.VertexInputFetcherSpec' \
  'testOnly opengpu.graphics.TextureCoordinatePipelineSpec opengpu.graphics.TextureCoordinatePolicySpec opengpu.graphics.TexturePreparedCoordinatePolicySpec' \
  'testOnly opengpu.graphics.TextureImageFormatSpec -- -z "cover depth24 rounding"' \
  'testOnly opengpu.graphics.TextureCompressedImageDecoderSpec -- -z "normalize every EAC11"' \
  'testOnly opengpu.graphics.TextureImageSampleUnitSpec' \
  'testOnly opengpu.graphics.VertexShaderStageSpec -- -z "execute ordered indexed instanced"' \
  'testOnly opengpu.graphics.VertexShaderStageSpec -- -z "clear partial global output"' \
  'testOnly opengpu.graphics.VertexShaderClearLinesSpec' \
  'testOnly opengpu.graphics.VertexShaderStageSpec -- -z "drain offered global writes"' \
  'testOnly opengpu.graphics.VertexShaderStageSpec -- -z "drain held launches"' \
  'testOnly opengpu.graphics.VertexShaderStageSpec -- -z "sample vertex textures"' \
  'testOnly opengpu.graphics.VertexShaderStageSpec -- -z "reuse pure indexed"'
```

All six changed components pass 1000 ps TC/SLVT synthesis with 25% I/O,
standard ABC, no retiming/false paths and zero skipped operators. No SRAM
macros occur in these component tops. Areas are synthesis library areas.

| Top/profile | Core / I/O slack (ps) | Cells | Library area |
| --- | ---: | ---: | ---: |
| TextureImageAddressUnit, both pipeline cuts | +129.805 / +333.006 | 25,297 | 3,417.10 |
| VertexInputConversionPipeline | +304.670 / +631.152 | 5,003 | 691.734 |
| VertexInputFetcher, sixteen attributes/bindings | +87.107 / +479.132 | 88,121 | 11,507.6 |
| TextureCoordinatePipeline, policy enabled | +102.839 / +317.379 | 9,605 | 1,311.41 |
| TextureUnit, prepared/overlap/policy enabled | +61.910 / +106.876 | 36,622 | 4,966.2 |
| TextureImageFetchDecoder | +299.372 / +660.229 | 11,154 | 1,449.35 |

The combinational `VertexShaderClearLines` helper also passes the same setup
gate with +113.683 ps I/O slack, 391 cells and library area 49.2367. It has no
internal register path or SRAM. Its profile is `mobile-vertex-clear-lines`.
Eleven current-stage controls cover carry-boundary global clearing, all twenty
invalid layouts, accepted-response cancellation, indexed reuse, held launches
and exports, staging faults, and both local/global native operation-family
ownership profiles. The bounded native profiles use sixteen draws per mode:

```sh
sbt -Dopengpu.nativeResourceAvailabilityOnly=true -Dopengpu.simJobs=4 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.VertexShaderStageSpec -- -z "drain"'
```

Emit the component profiles with `mobile-texture-composed-address`,
`mobile-vertex-input-conversion`, `mobile-vertex-input-fetch`,
`texture-coordinates-policy`, `texture-sampler-prepared-coordinate-policy`
and `mobile-texture-typed-fetch`. For the last directory, qualify the internal
`TextureImageFetchDecoder` top.

Emit `mobile-vertex-shader-reuse` for the complete default vertex-stage screen.
The complete stage passes the same 1000 ps TC/SLVT synthesis setup gate:
core-clock-group slack +20.435 ps and I/O-group slack +122.766 ps, with zero
skipped operators or unresolved mapping diagnostics. It contains 1,341,719
instances and synthesis library area 208,267, including eighteen SRAM instances:
ten 256x64 and eight 256x32 macros. The required PR catalog passes 68 tests
across fifteen suites with zero ignored, pending, canceled or failed tests;
host script checks pass as well. Use
the pinned SRAM models and `scripts/qualify_ppa.py --stage synthesis
--period-ps 1000 --io-delay 0.25`, with standard ABC, no retiming/false paths
and no skipped operators. This is a mapped synthesis setup estimate for this
vertex-stage profile. Whole-CU/host/renderer timing, physical/product budgets,
frame rates, energy and the remaining H0–H8 requirements stay open.
