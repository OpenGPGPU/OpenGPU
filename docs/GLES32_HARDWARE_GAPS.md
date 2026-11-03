# GLES 3.2 hardware gaps

H8 includes the full 3.2 feature delta. The source inventory below expands
E32-04 in the [capability matrix](MOBILE_GPU_CAPABILITY_MATRIX.md); geometry,
tessellation execution and generated-primitive ownership retain E32-01–03.

The feature scope follows [GLES 3.2, Appendix F.1](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf#page=554).
Shader details follow [GLSL ES 3.20](https://registry.khronos.org/OpenGL/specs/es/3.2/GLSL_ES_Specification_3.20.html)
and the incorporated [EXT_gpu_shader5 changes](https://registry.khronos.org/OpenGL/extensions/EXT/EXT_gpu_shader5.txt).
The optional sRGB decode control excluded by F.1 is outside this core delta.

This is a feature inventory, not a clause-by-clause conformance result. The
baseline inventory describes RTL at `8240cb09`; linked feature notes record
later scoped implementation. **Partial** means an existing
component can contribute, and **Open** means the required path has no qualified
implementation identified here. Neither state qualifies a feature. API/compiler
rows also need software contracts; they do not all require dedicated hardware.

| ID | Feature to qualify | State | Current evidence and remaining owner |
|---|---|---|---|
| E32-01 | Programmable geometry | Partial | [Shared-CU geometry through window bins](GEOMETRY_WINDOW_BIN_PASS.md) provides bounded task and output ownership. Native draw admission, adjacency/general topology and layered image ownership remain required. |
| E32-02 | Programmable patch control/evaluation | Partial | [Three-domain native host tests](TESSELLATION_QUAD_EVALUATION.md) execute bounded isoline, triangle and quad control/evaluation jobs. Driver admission, resource provenance, general domain semantics and product limits remain required. |
| E32-03 | Tessellated primitive generation | Partial | Isoline, triangle and quad jobs reach bin/replay and typed images in [bounded host tests](TESSELLATION_QUAD_EVALUATION.md). Complete spacing, winding, invariance and product qualification remain required. |
| E32-04a | Extended compositing equations | Open | [`TypedBlendState`](../src/main/scala/opengpu/graphics/TypedColorBlend.scala) accepts five ordinary equations. Extended operations and their ordering remain required. |
| E32-04b | Image-to-image region copies | Partial | [`TypedTileImageTransfer`](../src/main/scala/opengpu/graphics/TypedTileImageTransfer.scala) provides attachment transfer. General image-copy compatibility, block layouts and visibility remain unqualified. |
| E32-04c | Independent draw-buffer blend/mask state | Partial | [Versioned indexed controls](INDEXED_DRAW_BUFFER_BLEND.md) select independent enable, factors, equations and masks in serial/streaming MRT stores and native packets. Descriptor snapshots, AXI discovery and four bounded native host frames pass; composed timing, full product and API qualification remain required. |
| E32-04d | Debug output and object diagnostics | Open | Host faults/completion exist. The API message, label and group contract remains unqualified. |
| E32-04e | Dynamic resource-array selection | Partial | Native texture/uniform bindings exist. Compiler indexing rules and all shader resource classes remain unqualified. |
| E32-04f | Precise arithmetic and explicit FMA | Partial | The [fragment TGSI emitter](FRAGMENT_FUSED_MULTIPLY_ADD.md) lowers a bounded set of FMA and MAD operations. Full fragment execution, compiler operation ordering and precise-consumer invariance remain unqualified. |
| E32-04g | Gather with runtime and independent offsets | Open | Native sampling/offset paths exist; the required gather result contract remains unqualified. |
| E32-04h | ASTC LDR decoding | Open | [`TextureImageFormat`](../src/main/scala/opengpu/graphics/TextureImageFormat.scala) enumerates ETC2/EAC and fixed 4x4 blocks. ASTC formats, footprints and decoding remain required. |
| E32-04i | Submitted primitive bounds | Open | [GLES 3.2 §13.2](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf) permits retaining fragments outside the hint. OpenGPU can render normally without using its values for culling; API state, tessellation shader linkage and component-limit accounting remain unqualified. |
| E32-04j | Atomic image access | Partial | [Opt-in image atomics](SHADER_IMAGE_ATOMIC_SYSTEM.md) execute compute and fragment operations through real CUs and shared L2, including bare/Sv32 access, selected fault recovery and helper suppression. [CU operation cases](SHADER_IMAGE_ATOMIC_CU.md) cover additional atomic opcodes. The option is disabled by default; full operation and resource coverage, graphics admission, cross-domain ordering, API/compiler integration and physical qualification remain required. |
| E32-04k | Linked shader interface blocks | Partial | Versioned vertex/fragment payloads exist. Compiler block linkage and later-stage interfaces remain required. |
| E32-04l | Explicit interpolation locations | Partial | [`Fp32FragmentInterpolator`](../src/main/scala/opengpu/graphics/Fp32FragmentInterpolator.scala) supports ordinary/centroid preparation. Shader-requested interpolation locations remain unqualified. |
| E32-04m | Shading at a controlled sample rate | Open | Stored multisampling exists; sample-rate invocation scheduling remains required. |
| E32-04n | Shader sample inputs/outputs | Partial | [`FragmentShaderStage`](../src/main/scala/opengpu/graphics/FragmentShaderStage.scala) transports coverage/sample mode. Complete sample variables and output semantics remain required. |
| E32-04o | Buffer-backed textures | Open | [`TextureImageTarget`](../src/main/scala/opengpu/graphics/TextureImageAddressUnit.scala) has no buffer target; binding, fetch and limits remain required. |
| E32-04p | Border-color sampling | Open | [`TextureSamplerState`](../src/main/scala/opengpu/graphics/TextureSamplerBindingUnit.scala) exposes three wrap modes without a border-color field. |
| E32-04q | Arrays of cube textures | Partial | [Image-level addressing](TEXTURE_IMAGE_ADDRESSING.md) and GXI1 admission accept target 4 with `layer*6+face` plane selection, square faces and a 16-bit plane-count ceiling. Floating cube-array sampling, seamless edges and API/shader qualification remain required. |
| E32-04r | Stencil-only texture access | Partial | S8 attachment storage exists; the texture-format catalog has no stencil-only entry. Sampling remains required. |
| E32-04s | Arrays of multisample textures | Open | Current image descriptors lack the target/sample-index fetch contract. |
| E32-04t | Indexed drawing with vertex bias | Partial | [`VertexInputFetcher`](../src/main/scala/opengpu/graphics/VertexInputFetcher.scala) carries signed `baseVertex`; complete draw/API edge coverage remains unqualified. |
| E32-04u | Floating attachment rendering | Partial | [`TypedColorFormat`](../src/main/scala/opengpu/graphics/TypedColorFormat.scala) includes half/full floats. Required renderability, composition and API format coverage remain unqualified. |
| E32-04v | Robust access and context flags | Partial | Allocation/fault ownership exists. The context-level robustness and software flag contract remain unqualified. |
| E32-04w | Multisample line-width reporting | Partial | Point/line rasterization clamps sizes to `[1,64]`. [GRI1 word 7](NATIVE_RASTER_CAPABILITIES.md) now publishes that integer min/max, granularity `1` and a multisample-same flag over AXI discovery; generated headers expose the field masks. Full API query wiring and product qualification remain required. |

The arithmetic, indexing and gather rows expand F.1's shader-functionality
entry. Geometry/tessellation interfaces and sample features have their own rows.
Robustness must follow the core contract; F.1 does not incorporate the additional
defined behavior of `KHR_robust_buffer_access`.

### Primitive-bounds implementation choice

The primitive bounding box is an optimization hint, not a required scissor.
An implementation that ignores its values must still produce every fragment
required by the ordinary rasterization rules; it is permitted to retain
fragments beyond the hint. This choice avoids adding a native bounds descriptor
or patch-value storage solely for E32-04i. If bounds-based pruning is added
later, its clip-space conversion and expanded window-space bounds must follow
§13.2.

The Mesa 22.3.6 checkout used by the [Gallium spike](GALLIUM_SPIKE.md), at
`234055882d7a25f843369c1022a1f5ed149b90cd`, has front-end code to store and
query the eight API values and a `NoPrimitiveBoundingBoxOutput` compiler path
that discards the shader hint. The current OpenGPU Gallium screen reports ESSL
1.00, so that source inspection is not evidence of an exposed GLES 3.2 API or
a working tessellation compiler. Do not advertise the feature on this basis.
The following read-only source audit checks the cited checkout and relevant
files; it does not exercise an OpenGPU context:

```sh
test "$(git -C depends/mesa rev-parse HEAD)" = 234055882d7a25f843369c1022a1f5ed149b90cd
git -C depends/mesa diff --exit-code HEAD -- src/mesa/main/bbox.c \
  src/mesa/main/get_hash_params.py src/mesa/state_tracker/st_extensions.c \
  src/compiler/glsl/builtin_variables.cpp
rg -n 'PrimitiveBoundingBox|PRIMITIVE_BOUNDING_BOX' \
  depends/mesa/src/mesa/main/bbox.c depends/mesa/src/mesa/main/get_hash_params.py
rg -n 'NoPrimitiveBoundingBoxOutput|bbox_slot|gl_BoundingBox' \
  depends/mesa/src/mesa/state_tracker/st_extensions.c \
  depends/mesa/src/compiler/glsl/builtin_variables.cpp
rg -n -A 2 'PIPE_CAP_ESSL_FEATURE_LEVEL' userspace/gallium/opengpu_screen.c
```

Before closing the row, test the default and updated API values through the
actual OpenGPU context, and test `gl_BoundingBox` shader linkage and the
statically assigned output's contribution to patch-component limits. With
the ignore-hint implementation, bounded geometry and tessellation draws must
retain their normal images for different hints, including hints smaller than
the generated primitives. The [stage-resource contract](GLES32_STAGE_RESOURCE_LIMITS.md)
still applies to component accounting.

The [geometry/tessellation resource contract](GLES32_STAGE_RESOURCE_LIMITS.md)
maps the stage limits, coupled output budgets and combined binding namespaces
to required native resources. Use it before selecting patch/queue sizes;
boundary and fault behavior must still pass through the native host before
advertising support.
Lane/warp counts alone do not establish shader-visible limits. This inventory
does not close H0, E32-01–04 or H8, and leaves the H6 six-workload clock/rate gates
unchanged.
