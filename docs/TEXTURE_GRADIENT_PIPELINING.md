# Texture-gradient arithmetic timing

`TextureGradientProductDifferenceUnit` retains two exact 24-bit significand
products and one round-to-nearest/even subtraction. It returns the existing
24-bit significand and extended signed exponent; public requests, results,
status codes and native descriptor layouts are unchanged.
Both cube Jacobians and implicit helper-quad differences use this arithmetic.

Each multiplication captures four exact 12x12 partial products. Registered
cross terms and separate low/high joins reconstruct the full 48-bit product
without rounding. Exponent maximum/gaps are captured before alignment. A
discarded-product-bit reduction supplies the sticky bit for the bounded shift,
including exponent gaps up to 2046 and zero products. Signed conversion has its
own phase. The signed sum captures its low 28 bits and carry before joining the
sign-extended upper 29 bits. Absolute magnitude is captured before leading-bit
detection and normalization. Final packing retains the original single RNE.

These changes add seven internal phases. The unit remains a serial owner with
one accepted request; this does not increase throughput or buffering capacity.
Parents use the existing handshakes. Cancellation stops arithmetic before
completion, retains the original status/tag contract, and permits healthy
reuse. An already offered result stays immutable until accepted.

## Validation

The product reference checks 19,440 cases against exact BigInt arithmetic,
including cancellation, full finite exponent ranges and extended offsets.
Additional directed cases cover 23 exponent gaps with all sixteen sign
combinations and zero-product extremes. Twenty cancellation cuts cover every
arithmetic phase and held results. The cube parent checks 1,776 rational
Jacobians across all faces and 102 cancellation/reuse cuts. The helper-quad
parent checks 3,956 cases across lane/row/column selection and 128 cancellation
cuts with healthy reuse. All seven component tests pass.
All thirteen complete image-sampler tests also pass, covering stage bindings,
implicit helpers, gradients, mip/format/filter modes, late faults, cancellation,
immutable results and healthy reuse.
Two captured-instruction endpoint tests cover vertex and fragment stages with
scalar/vector parameter writes, masked samples and cube-direction scales from
the minimum FP32 normal to 2^126. Proportional derivatives must cancel exactly;
angular derivatives must select LINEAR minification. A single white texel gives
an independent exact 9/16 filtered result versus the NEAREST result of one.
Nine cancellation cuts per stage check both canceled work and immutable held
results, followed by healthy reuse.

```sh
sbt -Dopengpu.simJobs=4 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TextureGradientProductDifferenceUnitSpec opengpu.graphics.TextureCubeGradientUnitSpec opengpu.graphics.TextureQuadGradientUnitSpec' \
  'testOnly opengpu.graphics.TextureImageSampleUnitSpec'
sbt 'testOnly opengpu.graphics.SharedTextureSampleExecuteUnitSpec -- -z "retain exact cube Jacobians"'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-texture-gradient-product-difference .artifacts/product-difference-rtl' \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-texture-cube-gradient .artifacts/cube-gradient-rtl' \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-texture-quad-gradient .artifacts/quad-gradient-rtl'
```

All three emitted components pass 1000 ps TC/SLVT synthesis with 25% I/O, standard
ABC, no retiming or false paths, and zero skipped operators:

| Top | Core setup slack (ps) | I/O setup slack (ps) |
| --- | ---: | ---: |
| TextureGradientProductDifferenceUnit | +225.011 | +481.393 |
| TextureCubeGradientUnit | +210.469 | +478.663 |
| TextureQuadGradientUnit | +251.602 | +478.663 |

Run `scripts/qualify_ppa.py` for each emitted top with `--stage synthesis
--period-ps 1000 --io-delay 0.25`. Raw reports stay local under `.artifacts/`.
These results qualify the emitted components. Enclosing vertex/host timing,
rendered workload rates and H0–H8 require their separate gates.
