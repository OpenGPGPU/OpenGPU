# Tessellation level preparation

`TessellationLevelPrepare` prepares FP32 outer and inner levels for the three
GLES 3.2 tessellation domains. It implements equal, fractional-even and
fractional-odd clamp and segment-count rules, the isoline first-outer-level
equal-spacing rule, relevant-outer zero/negative/NaN patch discard, and the
inner `1 + ε` promotion needed for nontrivial triangle and quad subdivision.
It retains clamped FP32 values and promotion flags for the later edge-coordinate
generator. Invalid domain or spacing encodings are reported, and a held result
blocks a new request. Inner NaNs are deterministically clamped to the minimum.

The implementation targets the GLES minimum maximum level of 64 and records the
minimum 32 patch vertices and 2,048 total control-output components. These are
limits for later patch and shader integration, not an API capability claim.
The [OpenGL ES 3.2 specification, §11.2.2 and Table 21.45](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf)
defines the level rules and minimum limits.

The compact CI test checks 900 directed and randomized FP32 cases across all
three domains and spacing modes, invalid encodings and output backpressure:

```sh
sbt 'testOnly opengpu.graphics.TessellationLevelPrepareSpec'
```

Fractional edge coordinates, primitive generation, patch storage, shader
execution and barriers, native API/compiler integration and H8 remain open.
