# Advanced blend equations

Status: the serialized service and opt-in serial and parallel typed tile
storage paths have directed tests. Internal native pixel ownership routes
advanced work to the parallel general path. The separate opt-in
[native transport contract](NATIVE_ADVANCED_BLEND_ABI.md) defines draw/GFS
payload admission and safe discovery. GLES/API exposure and product
qualification remain open.

`TypedAdvancedColorBlend` evaluates every separable
`KHR_blend_equation_advanced` mode on premultiplied source and destination
colors:

| Id | Equation |
|---|---|
| 0 | `MULTIPLY` |
| 1 | `SCREEN` |
| 2 | `OVERLAY` |
| 3 | `DARKEN` |
| 4 | `LIGHTEN` |
| 5 | `COLORDODGE` |
| 6 | `COLORBURN` |
| 7 | `HARDLIGHT` |
| 8 | `SOFTLIGHT` |
| 9 | `DIFFERENCE` |
| 10 | `EXCLUSION` |

## Contract

- Inputs are premultiplied RGBA colors as the extension defines them. Each
  channel is unpremultiplied by its alpha (zero alpha yields zero), the mode
  function is evaluated on the unpremultiplied colors, and the result is
  recomposited as `f*As*Ad + Rs*(1-Ad) + Rd*(1-As)`.
- Alpha is `As + Ad - As*Ad`.
- Multiplication, addition and subtraction use the serialized FP32 FMA lane;
  unpremultiply and the dodge/burn quotients use the iterative divide lane,
  and softlight uses the square-root lane. The micro-operation order is fixed
  and documented by the independent oracle. Overlay/hardlight use
  `2*Cs*Cd` and `1-2*(1-Cs)*(1-Cd)` at the half threshold, darken/lighten use
  component minimum/maximum, difference uses the absolute difference, color
  dodge/burn clamp their quotients and zero-denominator guards, and softlight
  uses the piecewise `D(Cd)` curve including `sqrt(Cd)`.
- Operations used only by dodge, burn and softlight are skipped for the
  simpler equations.
- Equations 11..15 (the non-separable HSL modes) are rejected with status 1
  before any arithmetic.
- Results and arithmetic flags are held until accepted. Cancellation after
  acceptance marks the result cancelled and still drains the arithmetic lanes.

## Directed evidence

`TypedAdvancedColorBlendSpec` passes two cases:

- all eleven separable equations over five channel values, eight alpha pairs
  and per-channel independent colors (2200 pixels), compared against an oracle
  that mirrors the same operation order on premultiplied inputs, including
  zero-alpha unpremultiply and the alpha composition;
- rejection of equations 11..15, held results under backpressure and the
  cancelled flag after mid-computation cancellation.

```sh
sbt 'testOnly opengpu.graphics.TypedAdvancedColorBlendSpec'
```

## Non-separable modes

`TypedAdvancedHslBlend` evaluates the four non-separable modes on the same
premultiplied inputs:

| Id | Equation |
|---|---|
| 0 | `HSL_HUE` |
| 1 | `HSL_SATURATION` |
| 2 | `HSL_COLOR` |
| 3 | `HSL_LUMINOSITY` |

It implements the extension's `Lum(c) = 0.30 R + 0.59 G + 0.11 B`,
`Sat(c) = max(c) - min(c)`, `SetSat` scaling, `SetLum` and the sequential
`ClipColor` negative/overflow correction. `HSL_HUE` and `HSL_SATURATION` use
`SetLumSat`, whose saturation step divides by the base saturation (zero
saturation yields black) and then applies `ClipColor`. Three FMA lanes and
three divide lanes evaluate the RGB triplet in parallel, with lane results
captured independently because divide latency varies with special operands.
The KHR composition and alpha are shared with the separable owner.

`TypedAdvancedHslBlendSpec` passes two cases: 1024 pixels over four modes,
eight color triples, four alpha pairs, compared against an independent
`Lum`/`Sat`/`SetSat`/`SetLum`/`ClipColor` oracle; and held results with
cancellation completing into a marked result.

```sh
sbt 'testOnly opengpu.graphics.TypedAdvancedHslBlendSpec'
```

## Serialized service and barrier gate

`TypedBlendService` is the single entry point used to compose the three
owners. An ordinary request carries the typed-core source, encoded destination
word, format and `TypedBlendState`. An advanced request carries premultiplied
RGBA and an equation id: ids 0..10 route to the separable owner, 11..14 route
to the HSL owner, and other ids are rejected with status 1 before any child
work. One request may be outstanding at a time, so a later barrier command can
sample `barrierReady`, which is high only when every accepted blend has
retired.

`TypedBlendServiceSpec` checks ordinary add, separable multiply, all four HSL
routes against an independent oracle with distinct source/destination colors,
and unsupported-equation routing with the barrier gate (`BLEND_SERVICE`).
The public HSL ids 11..14 map respectively to hue, saturation, color and
luminosity; subtracting the separable-mode count gives the child id. Distinct
colors catch a prior permutation that identical-color tests could not detect.
Held immutable results keep the barrier closed until retirement
(`BLEND_SERVICE_HOLD`). A single-cycle cancellation immediately after service
admission also drains on all three routes and permits healthy reuse
(`BLEND_SERVICE_CANCEL`). The accepted child offer is delivered before the
latched cancellation, so the child's cancel-gated input cannot deadlock it.

Reproduce these three service tests with:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TypedBlendServiceSpec'
```

## Blend barrier commands

`BlendBarrierCommandUnit` orders draw and `glBlendBarrier` commands. Draws are
released immediately and may overlap outstanding blending; a barrier waits for
the blend service's barrier-ready gate and holds every later command by
backpressure until it completes. Reserved command codes are rejected. One
directed case covers early draw release, barrier waiting, backpressure and
reserved codes (`BLEND_BARRIER`).

## Typed tile storage integration

`TypedTileColorConfig(advancedBlend=true)` connects packed destination gather,
`TypedTileBlend`, the existing typed encoder and the tile word writer.
The default configuration leaves advanced arithmetic disabled. Destination decode linearizes sRGB
RGB channels before blending; the store encodes the linear result back to sRGB
and applies attachment and indexed component masks. Each selected MSAA sample
uses its own resident or refilled destination. It does not resolve samples.

Before advanced arithmetic, UNORM and sRGB source RGBA components use the same
clamp as ordinary typed blending: NaNs and negative values become zero, values
above one become one. Floating-point targets retain their unclamped sources.
The source is already linear; only the destination decode and final encoding
perform sRGB conversion. This follows the source/destination distinction in
[OpenGL ES 3.2, section 15.1.5](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf).
`TypedTileBlendSpec` checks 92 requests across all nine normalized and eight
floating-point internal formats, including RGB/alpha boundaries, NaN/Inf,
discriminating unclamped/incorrect-source-gamma alternatives, ordinary scalar
references and immutable accepted state. A second case checks three
single-cycle cancellation cuts, held-result immutability and immediate reuse.

This internal tile interface uses `TypedBlendState.version=3`. Each buffer
retains version 2's bits 0..26, adds advanced enable at bit 27 and the equation
id 0..14 at bits 31..28. An advanced buffer requires blend enable, zero ordinary
factors/equations and a non-integer attachment. Non-advanced buffers retain
ordinary factor/equation semantics and require bits 31..27 zero. Shared enable,
factors and equations must be zero; blend constants remain shared. Integer
advanced attachments and malformed controls are rejected for the entire quad
before its first write, including when an earlier MRT would otherwise be legal.

Internal blend-state version 3 alone does not enable a native packet ABI.
Scalar selection and legacy draw/GFS payload versions continue rejecting it.
The independent `nativeAdvancedDraws` opt-in requires the explicitly defined
native payload version and protected parallel owner; its GCR/GAB discovery
contract is separate from the frozen GDB1 words. Version 2 retains its reserved
high-bit checks. Default profiles keep the new admission and discovery disabled.

A quad retains its state through gather, arithmetic, encode and masked writes.
Cancellation drains offered reads and child arithmetic and suppresses new
writes; already committed prefix writes are not rolled back. Held quad results
remain immutable. The tile storage owner accepts its end command after the
quad result retires, so completion cannot overtake arithmetic, packing or word
writes. An enclosing native owner can retain an earlier EOF until this drain.
This storage ordering is not yet a native `glBlendBarrier` command path.

`AdvancedTypedTileColorStoreSpec` passes three cases: internal-only admission;
all fifteen equations across RGBA8, SRGB8_ALPHA8, RGBA16F and RGBA32F in four
MRTs with 4x storage, distinct destinations for selected samples 0 and 2,
independent ordinary blending, masks and exact uncovered-sample/neighbor
preservation; and integer/invalid-state rejection, cold-refill and arithmetic
cancellation, refill faults and healthy reuse. Each successful quad checks ten
word writes, 352 written bits, eight completed locations and two touched
samples against the final stored words. The fifteen equation groups finish in
4,506–12,570 clocks per quad before result backpressure; these are eight
serialized blends per quad, not a frame-rate qualification. The 100,000-clock
test bound covers eight owners of fewer than 128 arithmetic steps each,
27-iteration divide/sqrt lanes (under 64 clocks including setup/retirement),
and tile codec/ownership overhead. Ordinary store tests retain their existing
10,000-clock wait bound. The ordinary 36-format blend and cancellation tests,
plus indexed MRT serial/four-port/eight-port stores and native sample-packet
selection, also pass with their original images and semantics.
Three additional four-MRT groups verify out-of-range RGB/alpha, negative alpha
and normalized NaN sources against the actual stored words, with unclamped
floating-point MRT controls. The same masks, independent sample destinations,
uncovered samples and packed neighbors are checked in the serial, four-port
and eight-port stores.

The source filter bounds local test compilation to the selected suites and
their actual shared fixtures; it does not change CI selection.

```sh
sbt -J-Xmx2G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "WindowPrimitiveRecordsSpec.scala", "TypedColorFormatSpec.scala", "TypedColorBlendSpec.scala", "TypedAdvancedColorBlendSpec.scala", "TypedAdvancedHslBlendSpec.scala", "TypedBlendServiceSpec.scala", "TypedTileColorStoreSpec.scala", "IndexedDrawBufferBlendSpec.scala").contains(f.getName))' \
  'testOnly opengpu.graphics.TypedBlendServiceSpec opengpu.graphics.AdvancedTypedTileColorStoreSpec opengpu.graphics.IndexedDrawBufferBlendSpec' \
  'testOnly opengpu.graphics.TypedTileColorStoreSpec -- -z blend'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

## Parallel and internal native pixel ownership

The same option enables a separate advanced arithmetic route in
`StreamingTypedTileColorLanes`. Ordinary candidates continue to feed the
existing II=1 pipeline; an advanced request waiting for serialized arithmetic
does not occupy the ordinary offer register. Both routes keep the original
64 pixel/sample/location owners, four MRTs, 4x samples and tile capacity.
Four-port and eight-port configurations use the same typed decode/encode and
masked word ownership rules. A round-robin result merger retires one result
per clock. A continuously valid route waits at most one competing retirement;
its held result must retain the complete tuple, including during cancellation.
Completion waits for both arithmetic routes, codec and word owners to drain.

`NativeTypedSamplePixelBackend` sends internal version 3 quads to the parallel
general owner. It drains an older native cohort before that handoff and drains
the general owner before the next native cohort. Advanced state never becomes
ordinary native sample-packet arithmetic. Native packet admission additionally
requires the separate [versioned transport](NATIVE_ADVANCED_BLEND_ABI.md).

The four-MRT fixtures test internal state isolation. A future GLES API must
enforce the single color-output-zero restriction and matching fragment-shader
`blend_support` qualifier, and implement the blend-pass barrier contract.
See [OpenGL ES 3.2, advanced blending](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf),
section 15.1.5.3. Internal MRT capacity does not imply that these combinations
are legal GLES advanced-blend draws.

`AdvancedParallelTypedTileColorStoreSpec` and
`AdvancedEightPortTypedTileColorStoreSpec` each pass the same three admission,
stored-image and fault/cancel tests as the serial integration. The fifteen
equation groups take 4,303–12,367 clocks per eight-blend quad. The advanced
native pixel suite passes nine cases, including queued native/general/native
cohorts, held outputs, early EOF, canonical sample words and healthy rebind.
Its nine-quad mixed cohort takes 33,368 clocks. With advanced arithmetic built
in, the original ordinary native test still completes 128 consecutive quads
at intervals of 1, 2 and 4 clocks for 1x, 2x and 4x storage, respectively.
The original 36-format, masked-neighbor, fault/cancel, four/eight-port and
bounded-prefix checks also pass. These are component results; advanced
arithmetic remains serialized and has no product-rate qualification.

The streaming owner suite completes all 64 advanced owners and mixed
advanced/ordinary owners with reordered held word replies. The mixed fixtures
take 31,510 and 31,448 clocks for four and eight word ports. Delaying actual
ordinary destination replies creates two result collisions: each route is
blocked once and accepts on the next clock. Cancellation on a collision keeps
the held tuple stable, creates no new writes and permits reuse. Offer, busy
and returning-result cancellation, word faults and the original ordinary
36-format/sample/mask regressions also pass. The advanced fixture's
800,000-clock bound covers 64 owners with fewer than 128 arithmetic steps and
64 clocks per step, plus codec and memory handshakes. Ordinary fixture bounds
remain 10,000 clocks.

Reproduce the parallel storage, native pixel and ordinary regressions with:

```sh
sbt -J-Xmx2G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "WindowPrimitiveRecordsSpec.scala", "TypedColorFormatSpec.scala", "TypedColorBlendSpec.scala", "TypedAdvancedColorBlendSpec.scala", "TypedAdvancedHslBlendSpec.scala", "TypedBlendServiceSpec.scala", "TypedTileBlendSpec.scala", "TypedTileColorStoreSpec.scala", "IndexedDrawBufferBlendSpec.scala", "StreamingTypedTileColorLanesSpec.scala", "StreamingTypedColorBlendSpec.scala", "TypedTilePixelBackendSpec.scala", "TypedDepthStencilSpec.scala").contains(f.getName))' \
  'testOnly opengpu.graphics.TypedTileBlendSpec' \
  'testOnly opengpu.graphics.AdvancedParallelTypedTileColorStoreSpec opengpu.graphics.AdvancedEightPortTypedTileColorStoreSpec opengpu.graphics.AdvancedNativeSampleTypedTilePixelBackendSpec' \
  'testOnly opengpu.graphics.StreamingTypedTileColorLanesSpec' \
  'testOnly opengpu.graphics.StreamingTypedColorBlendSpec -- -z sustain' \
  'testOnly opengpu.graphics.IndexedDrawBufferBlendSpec'
```

## Remaining work

Full GLES blending conformance, including precision, exceptional inputs and
the complete equation-by-format matrix, remains unqualified. Complete native
job/product qualification, native blend-barrier API ordering, GLES frontend
exposure and physical timing remain open. The opt-in transport and typed tile
integration do not close H8.
