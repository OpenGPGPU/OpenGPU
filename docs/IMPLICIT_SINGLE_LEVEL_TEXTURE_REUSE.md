# Implicit LOD reuse for validated single-level RGBA8 bindings

This candidate retains the implicit sample instruction and its helper/derivative
contract. A validated 2D RGBA8 template records whether its effective first and
last levels coincide. Existing template admission also requires identical
nearest or linear min/mag filters, no shadow or mip blending, checked swizzle,
and aligned image rows. For such a binding, finite derivative-based LOD cannot
change the selected level or spatial filter.

Before reusing either a fresh or task-cached template, every selected quad must
have all four captured helper tuples and finite U/V coordinates, including
unselected helpers within that quad. Unselected quads and unused Z are ignored.
The existing extended-exponent gradient arithmetic represents every finite
FP32 row/column difference, including opposite maxima. No gradient fault is
removed by this shortcut. Missing/nonfinite helpers, multiple effective levels,
unequal filters, bias, shadow and unsupported offsets retain the general path.
Task identity, invalidation, no-fill, cancellation and lease ownership retain
their existing checks. The instruction encoding and GPU resource ABI are unchanged.

Five focused checks pass: nearest/linear reuse, exact swizzled components,
FP32 extremes, masked helper faults, multi-level/unequal-filter fallback and
cancellation with healthy reuse. Parameter pair zero still starts a fresh
captured tuple; callers must resubmit the remaining pairs after rewriting it.
Raw failure and retry evidence stays local under `.artifacts/`.

Four shared-window rate checks also pass. With sixteen lanes, eight warps,
three configured clients and one active client, both implicit and explicit LOD
deliver 448 steady-state four-tap groups in 448 clocks, with 92-clock reply
latency and no warm descriptor/data reads. All 512 offered groups complete in
public order with exact results. The four-credit and non-burst controls retain
their slower measured rates. This is a warm component result, not an aggregate
two-cluster, renderer, cold-data, power or product-frame qualification.

```sh
sbt 'testOnly opengpu.graphics.NativeTextureLaneStreamSpec -- -z "implicit single-level"'
sbt 'testOnly opengpu.graphics.NativeTextureLaneStreamSpec -- -z "measure four-tap shared window native throughput"'
sbt 'testOnly opengpu.graphics.NativeTextureLaneStreamSpec opengpu.graphics.NativeTextureSnapshotCacheSpec opengpu.graphics.NativeTextureInstructionStreamSpec opengpu.graphics.TextureQuadGradientUnitSpec -- -l opengpu.native.sharedwindow'
sbt 'runMain opengpu.elaboration.EmitNativeRgba8Clients native-vertex .artifacts/emitted/native-vertex'
sbt 'runMain opengpu.elaboration.EmitNativeRgba8Clients native-fragment .artifacts/emitted/native-fragment'
```

Fresh complete endpoint synthesis passes at 1000 ps TC/SLVT with 25% I/O:

| Native endpoint | Core setup slack (ps) | I/O setup slack (ps) |
|---|---:|---:|
| Vertex | +85.785 | +135.631 |
| Fragment | +68.451 | +126.766 |

Both runs map every operator with zero skipped instances or unresolved
diagnostics. They use standard Yosys/ABC mapping without retiming, arithmetic
operator substitutions or false paths. Reproduce after the emissions above
using ChipAgent's Python (the commands assume a sibling `chipagent` checkout):

```sh
../chipagent/.venv/bin/python scripts/qualify_ppa.py .artifacts/emitted/native-vertex NativeShaderTextureUnit \
  --synthesis-engine yosys --period-ps 1000 --io-delay 0.25 --output .artifacts/implicit-single-level-vertex
../chipagent/.venv/bin/python scripts/qualify_ppa.py .artifacts/emitted/native-fragment NativeShaderTextureUnit \
  --synthesis-engine yosys --period-ps 1000 --io-delay 0.25 --output .artifacts/implicit-single-level-fragment
```

Whole-host composition, full product images, general implicit/mipmap throughput
and the remaining H0–H8 gates stay open. These are synthesis-only endpoint
results; routed timing and power remain unqualified.
