# Mobile shader and framebuffer capacity contract

This expands the resource-limit gap in the [capability inventory](MOBILE_GPU_CAPABILITY_MATRIX.md).
It is an H0/H6 requirement map, not an API capability declaration. Read the
native profile's discovery records before constructing descriptors; reduced
elaborations may expose fewer slots than the Mobile target.

The normative bounds below use [GLES 3.0.6, tables 6.28 and 6.31–6.35](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf#page=285).
Values are lower bounds except the alignment ceiling. U/V/T/F identify the
implementation evidence and missing gates described below.

| Required limit | Bound | Contract |
|---|---:|---|
| MAX_VERTEX_UNIFORM_COMPONENTS | 1024 | U |
| MAX_VERTEX_UNIFORM_VECTORS | 256 | U |
| MAX_FRAGMENT_UNIFORM_COMPONENTS | 896 | U |
| MAX_FRAGMENT_UNIFORM_VECTORS | 224 | U |
| MAX_VERTEX_UNIFORM_BLOCKS | 12 | U |
| MAX_FRAGMENT_UNIFORM_BLOCKS | 12 | U |
| MAX_COMBINED_UNIFORM_BLOCKS | 24 | U |
| MAX_UNIFORM_BUFFER_BINDINGS | 24 | U |
| MAX_UNIFORM_BLOCK_SIZE | 16384 bytes | U |
| UNIFORM_BUFFER_OFFSET_ALIGNMENT | at most 256 bytes | U |
| MAX_COMBINED_VERTEX_UNIFORM_COMPONENTS | 50176 floor; formula below | U |
| MAX_COMBINED_FRAGMENT_UNIFORM_COMPONENTS | 50048 floor; formula below | U |
| MAX_VERTEX_OUTPUT_COMPONENTS | 64 | V |
| MAX_FRAGMENT_INPUT_COMPONENTS | 60 | V |
| MAX_VARYING_COMPONENTS | 60 | V |
| MAX_VARYING_VECTORS | 15 | V |
| MAX_VERTEX_TEXTURE_IMAGE_UNITS | 16 | T |
| MAX_TEXTURE_IMAGE_UNITS | 16 | T |
| MAX_COMBINED_TEXTURE_IMAGE_UNITS | 32 | T |
| MAX_RENDERBUFFER_SIZE | 2048 pixels per axis | F |
| MAX_SAMPLES | 4 | F |

The combined uniform bound is calculated per stage from that implementation's
block count × block bytes / 4 + default-block component count; larger advertised
values raise the bound. Viewport dimensions must accommodate the renderbuffer
and display dimensions as specified in §2.13.1. These are separate contracts
from texture dimensions, tile edge length and SRAM capacity.

## U: uniform storage and blocks — native boundaries checked

[Vertex](../src/main/scala/opengpu/graphics/VertexShaderStage.scala) and
[fragment](../src/main/scala/opengpu/graphics/FragmentShaderStage.scala) tasks
receive the scratch base through the existing kernarg ABI. Uniform data follows
the invocation input/output prefix in GPU memory. Local payload allocation maps
only the private prefix; its spill threshold is not a uniform-storage limit.
Existing [vertex](../src/test/scala/opengpu/graphics/VertexShaderStageSpec.scala)
and [fragment](../src/test/scala/opengpu/graphics/FragmentShaderStageSpec.scala)
legacy kernarg checks preserve a scalar uniform tail. Those ordinary loads
alone do not establish checked block mappings, permissions or simultaneous
stage capacity; the native evidence below covers separate resource contracts.

GTC1/GNC2/GTO1 retain their existing contracts. The separate
[GUC1 discovery](NATIVE_UNIFORM_DISCOVERY.md) reports native uniform word-load
bounds and semantics without an API conformance claim. Ordinary CU loads alone
do not establish the binding, range,
publication and lifetime contract. The [checked uniform load service](UNIFORM_BUFFER_RESOURCES.md)
defines GUR1/GUB1 roots and read-only views, twelve stage mappings, twenty-four
bindings and default storage outside the private prefix. Its standalone checks
cover complete 16 KiB/default ranges, boundary failures, snapshots, faults and
cancellation drain for both stages. The [native binding/execution path](NATIVE_UNIFORM_BINDINGS.md)
adds task-owned uniform instructions, versioned draw/state roots and separate
128-thread vertex/fragment local/spill checks of all twelve mappings and the
final block/default words. [Concurrent native stage checks](NATIVE_UNIFORM_CONCURRENT_CAPACITY.md)
exercise both 128-thread stages against the same twenty-four-binding table at
a fixed payload budget, including Sv32 and GPU-produced mapping/view/data
publication before active draw admission. [Compact translated host checks](NATIVE_UNIFORM_HOST_OWNERSHIP.md)
cover held-completion VM lifetime, ASID remapping/restoration and accepted
root/view/data reset drain. [Typed renderer capacity checks](NATIVE_UNIFORM_RENDERER_CAPACITY.md)
cover both payload profiles at 128 threads with all sixty-four varying components
on two and four CUs, including mobile/default caches. Complete product and
full-capacity binned runtime, concurrent
reset/remapping, general coherence and rate budgets remain open.
Driver/compiler API integration remains separate. Preserve the kernarg ABI.

## V: stage interfaces — partial

GTC1 and GNC2 report the configured varying-slot count. Each slot contains four
raw components; the stage configurations permit up to sixteen slots. Position
and optional point size have their own output arrays. Fragment position,
front-face and optional point coordinates also have distinct ABI fields.
These hardware counts still require the specification's built-in/component
counting and linker packing rules before exposing API limits.

The default 128-thread [vertex-stage check](../src/test/scala/opengpu/graphics/VertexShaderStageSpec.scala)
exports all sixty-four user components with sixteen attributes and controlled
spill. [Typed renderer capacity checks](NATIVE_UNIFORM_RENDERER_CAPACITY.md)
verify all sixty-four staged components and actual fragment-shader consumption
in 128 lanes through both payload profiles. Those point draws use constant
per-binding attributes. [Full-width native checks](NATIVE_FULL_VARYING_INTERPOLATION.md)
add nonconstant affine and W=1/2/4 perspective triangle interpolation with
actual shader consumption of all sixty-four components in 128 fragment lanes
at 1×/4× MSAA through both payload profiles. Independent exact rational values,
fragment reciprocal W and shader checksums cover the perspective composition.
Mixed centroid/flat qualifiers, limit rejection and complete product runtime
remain qualification gates.

The [standalone interpolator check](../src/test/scala/opengpu/graphics/Fp32FragmentInterpolatorSpec.scala)
also validates all sixty-four smooth components in a four-lane quad, including
an affine dataset with sixteen distinct attribute groups and unit reciprocal W.
Every affine value matches both the rounded rational model and a closed-form
binary reference. This fixture takes 29,836 cycles from request to output;
thirty-two serialized quads alone account for about 955,000 cycles before native
staging, shader execution and drain. This component result does not establish
native renderer throughput or a physically qualified clock.

```sh
sbt -Dopengpu.simJobs=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.Fp32FragmentInterpolatorSpec -- -z "full sixty-four-component"'
```

## T: texture bindings — native endpoint boundary checked

[GTO1 word 10](NATIVE_TEXTURE_OPERATION_CAPABILITIES.md) reports thirty-two
bindings. [ShaderTextureResources.maxBindings](../src/main/scala/opengpu/graphics/NativeTextureResources.scala)
defines that namespace for discovery and all three root-admission paths:
packet/captured fetch, floating sampling and size queries. Each stage binds an
independent GXR1 root; resource identity and stage tags remain checked. This
does not advertise sixty-four combined API units: API unit sharing and active
sampler accounting still require a program/binding contract.

Four focused native endpoint checks install all thirty-two distinct bindings
at once for each stage. They exercise legacy packet fetch, captured integer
fetch, floating sample and metadata-only size retirement with a binding table
ending exactly at the exclusive 4 GiB boundary. Invalid counts, full-width
indices, truncated/short limits and misalignment fail before selected binding
reads; valid binding 31 succeeds again after rejection. Both memory offers and
held results encounter backpressure. These tests do not execute a whole CU or
prove simultaneous vertex/fragment program limits or full-host capacity.

```sh
sbt 'testOnly opengpu.graphics.NativeShaderTextureUnitSpec -- -z "fetch all 32 resident"'
sbt 'testOnly opengpu.graphics.TextureSampleExecuteUnitSpec -- -z "admit all 32 resident"'
```

## F: framebuffer and viewport extent — partial

[GNC2](NATIVE_BINNED_GRAPHICS_PIPELINE.md) reports the configured frame extent;
[GTC1](NATIVE_TYPED_GRAPHICS_ABI.md) reports sample and resident attachment
capacities. The current emitted product's 1280×720 extent does not meet a
2048×2048 renderbuffer capacity requirement. Larger descriptor fields and small
tile storage do not prove a larger native target. Qualify a supported full-size
configuration, extreme viewport/scissor coordinates, format/sample-specific
admission and complete backing/resolve bounds before advertising these limits.

The remaining format, geometry, compute/storage and GLES 3.2 limits remain in
the original H0–H8 plan. This capacity map alone closes no milestone. The separate
[H0 acceptance record](MOBILE_GPU_MILESTONE_ACCEPTANCE.md) covers the contract
and measurement foundation; the implementation gaps above remain required.
