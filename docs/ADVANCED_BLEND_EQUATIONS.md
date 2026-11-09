# Advanced blend equations

Status: standalone integrated component with directed tests on local main.
It is not wired into the typed blend owner or an API path.

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

`TypedBlendServiceSpec` passes two cases: ordinary add, separable multiply, HSL
hue and unsupported-equation routing with the barrier gate
(`BLEND_SERVICE`), and held immutable results keeping the barrier closed until
retirement (`BLEND_SERVICE_HOLD`).

## Blend barrier commands

`BlendBarrierCommandUnit` orders draw and `glBlendBarrier` commands. Draws are
released immediately and may overlap outstanding blending; a barrier waits for
the blend service's barrier-ready gate and holds every later command by
backpressure until it completes. Reserved command codes are rejected. One
directed case covers early draw release, barrier waiting, backpressure and
reserved codes (`BLEND_BARRIER`).

## Remaining work

sRGB-aware blending, the extension's precision and clamp rules, tile/render
pipeline hookup, driver/API exposure and product qualification remain open.
