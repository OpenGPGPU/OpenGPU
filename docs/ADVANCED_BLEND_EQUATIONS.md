# Advanced blend equations

Status: the serialized service and an opt-in serial typed tile storage path
have directed tests. The native render pipeline, parallel tile implementation
and driver/API exposure remain open.

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
`TypedTileBlend`, the existing typed encoder and the serial tile word writer.
The option requires the serial owner; the default parallel/native capacities
and throughput configuration are unchanged. Destination decode linearizes sRGB
RGB channels before blending; the store encodes the linear result back to sRGB
and applies attachment and indexed component masks. Each selected MSAA sample
uses its own resident or refilled destination. It does not resolve samples.

This internal tile interface uses `TypedBlendState.version=3`. Each buffer
retains version 2's bits 0..26, adds advanced enable at bit 27 and the equation
id 0..14 at bits 31..28. An advanced buffer requires blend enable, zero ordinary
factors/equations and a non-integer attachment. Non-advanced buffers retain
ordinary factor/equation semantics and require bits 31..27 zero. Shared enable,
factors and equations must be zero; blend constants remain shared. Integer
advanced attachments and malformed controls are rejected for the entire quad
before its first write, including when an earlier MRT would otherwise be legal.

Version 3 is **not a native draw packet ABI**. Ordinary descriptor validation,
scalar selection and all native draw versions reject it; only the explicitly
enabled serial tile owner accepts and resolves it. Version 2 keeps its reserved
high-bit checks. Native/render-pipeline admission and discovery advertise no new
capability.

A quad retains its state through gather, arithmetic, encode and masked writes.
Cancellation drains offered reads and child arithmetic and suppresses new
writes; already committed prefix writes are not rolled back. Held quad results
remain immutable. End-of-tile admission waits until the quad result retires, so
it cannot overtake arithmetic, packing or word writes. This storage ordering is
not yet a native `glBlendBarrier` command path.

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

## Remaining work

The extension's complete precision/clamp rules and format coverage, parallel
and native tile/render-pipeline admission, native blend-barrier ordering,
driver/API exposure, physical timing and product qualification remain open.
This bounded serial integration does not close H8.
