# Advanced blend equations

Status: standalone integrated component with directed tests on local main.
It is not wired into the typed blend owner or an API path.

`TypedAdvancedColorBlend` evaluates the separable
`KHR_blend_equation_advanced` modes on premultiplied source and destination
colors:

| Id | Equation |
|---|---|
| 0 | `MULTIPLY` |
| 1 | `SCREEN` |
| 2 | `OVERLAY` |
| 3 | `DARKEN` |
| 4 | `LIGHTEN` |
| 5 | `HARDLIGHT` |
| 6 | `DIFFERENCE` |
| 7 | `EXCLUSION` |

## Contract

- Inputs are premultiplied RGBA colors as the extension defines them. Each
  channel is unpremultiplied by its alpha (zero alpha yields zero), the mode
  function is evaluated on the unpremultiplied colors, and the result is
  recomposited as `f*As*Ad + Rs*(1-Ad) + Rd*(1-As)`.
- Alpha is `As + Ad - As*Ad`.
- Multiplication, addition and subtraction use the serialized FP32 FMA lane;
  unpremultiply uses the iterative divide lane. The micro-operation order is
  fixed and documented by the independent oracle. The mode functions use
  `2*Cs*Cd` and `1-2*(1-Cs)*(1-Cd)` for overlay/hardlight, `2*Cs*Cd` when the
  destination (overlay) or source (hardlight) is at most one half, component
  minimum/maximum for darken/lighten, and the absolute difference for
  difference.
- Equations 8..15 (color dodge, color burn, softlight and the non-separable
  HSL modes) are rejected with status 1 before any arithmetic.
- Results and arithmetic flags are held until accepted. Cancellation after
  acceptance marks the result cancelled and still drains the arithmetic lanes.

## Directed evidence

`TypedAdvancedColorBlendSpec` passes two cases:

- all eight equations over five channel values, eight alpha pairs and
  per-channel independent colors (1600 pixels), compared against an oracle
  that mirrors the same operation order on premultiplied inputs, including
  zero-alpha unpremultiply and the alpha composition;
- rejection of equations 8..15, held results under backpressure and the
  cancelled flag after mid-computation cancellation.

```sh
sbt 'testOnly opengpu.graphics.TypedAdvancedColorBlendSpec'
```

## Remaining work

Color dodge, color burn, softlight and the four non-separable HSL modes,
sRGB-aware blending, the extension's precision and clamp rules, integration
with the typed blend owner and blend-barrier ordering, driver/API exposure and
product qualification remain open.
