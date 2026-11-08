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
| E32-03 | Tessellated primitive generation | Partial | Isoline, triangle and quad jobs reach bin/replay and typed images in [bounded host tests](TESSELLATION_QUAD_EVALUATION.md). Quad tessellation preserves its vertex set, triangle count and signed area under 90/180/270-degree outer-level rotation and under reflection (30 directed checks); the emitted winding follows the explicit clockwise input, and the interior diagonal choice is deterministic but not rotation-covariant. Triangle-domain invariance, complete spacing variants and product qualification remain required. |
| E32-04a | Extended compositing equations | Open | [`TypedBlendState`](../src/main/scala/opengpu/graphics/TypedColorBlend.scala) accepts five ordinary equations. Extended operations and their ordering remain required. |
| E32-04b | Image-to-image region copies | Partial | [`TypedTileImageTransfer`](../src/main/scala/opengpu/graphics/TypedTileImageTransfer.scala) provides attachment transfer. A bounded [region-copy owner](TEXTURE_IMAGE_REGION_COPY.md) copies 2D, 2D-array, 3D, cube, cube-array and buffer rectangles between same-format packed levels up to 4096 texels, with checked origins, pitches and allocation spans, masked line writes and source/destination fault attribution; eight directed cases pass. Multisample and compressed-block copies, overlap detection, API/driver exposure and visibility remain unqualified. |
| E32-04c | Independent draw-buffer blend/mask state | Partial | [Versioned indexed controls](INDEXED_DRAW_BUFFER_BLEND.md) select independent enable, factors, equations and masks in serial/streaming MRT stores and native packets. Descriptor snapshots, AXI discovery and four bounded native host frames pass; composed timing, full product and API qualification remain required. |
| E32-04d | Debug output and object diagnostics | Partial | Host faults/completion exist. A [bounded message log](DEBUG_MESSAGE_LOG.md) stores id/source/type/severity/length plus eight payload words in insertion order, drops on full with a sticky overflow flag that clears when the log empties, and resets for context reuse; three directed cases pass. Object labels, debug groups, severity/type filtering, timestamps, driver callbacks and the `glDebugMessage*` entry points remain required. |
| E32-04e | Dynamic resource-array selection | Partial | Native texture/uniform bindings exist. Compiler indexing rules and all shader resource classes remain unqualified. |
| E32-04f | Precise arithmetic and explicit FMA | Partial | The [fragment TGSI emitter](FRAGMENT_FUSED_MULTIPLY_ADD.md) lowers a bounded set of FMA and MAD operations, including position-Y transforms, with directed CU execution. Full fragment-pipeline execution, compiler operation ordering and precise-consumer invariance remain unqualified. |
| E32-04g | Gather with runtime and independent offsets | Partial | The mip-level sampler returns one component from each of the four 2x2 footprint taps, each with its own runtime texel offset, under repeat, clamp, mirrored-repeat or clamp-to-border wrapping, without filtering or swizzle; 249 directed queries cover 2D and array targets, RGBA8/SNORM/integer formats, all four components, interior and edge offsets, and clamp-to-border taps with independent offsets that return the configured border color. Cube/cube-array/3D/comparison gather, shader/ISA encoding, driver admission and API exposure remain required. |
| E32-04h | ASTC LDR decoding | Open | [`TextureImageFormat`](../src/main/scala/opengpu/graphics/TextureImageFormat.scala) enumerates ETC2/EAC and fixed 4x4 blocks. ASTC formats, footprints and decoding remain required. |
| E32-04i | Submitted primitive bounds | Partial | [GLES 3.2 §13.2](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf) permits retaining fragments outside the hint. A [bounding-box owner](PRIMITIVE_BOUNDING_BOX.md) publishes the default/replacement eight-float state, validates finiteness and ordering, accepts a complete patch override, computes triangle clip bounds with NaN propagation, and culls only definite X/Y/Z/W disjointness; two directed cases pass. The sixteen-vertex volume clip and projection, window-space point/line expansion, tessellation `gl_BoundingBox` linkage, component-limit accounting and API exposure remain required. |
| E32-04j | Atomic image access | Partial | [Opt-in image atomics](SHADER_IMAGE_ATOMIC_SYSTEM.md) execute compute and fragment operations through real CUs and shared L2, including bare/Sv32 access, selected fault recovery and helper suppression. [CU operation cases](SHADER_IMAGE_ATOMIC_CU.md) cover additional atomic opcodes. The option is disabled by default; full operation and resource coverage, graphics admission, cross-domain ordering, API/compiler integration and physical qualification remain required. |
| E32-04k | Linked shader interface blocks | Partial | Versioned vertex/fragment payloads exist. Compiler block linkage and later-stage interfaces remain required. |
| E32-04l | Explicit interpolation locations | Partial | [`Fp32FragmentInterpolator`](../src/main/scala/opengpu/graphics/Fp32FragmentInterpolator.scala) supports ordinary/centroid preparation. Shader-requested interpolation locations remain unqualified. |
| E32-04m | Shading at a controlled sample rate | Partial | Stored multisampling exists. A [sample-shading scheduler](SAMPLE_RATE_SHADING.md) emits one invocation per covered sample in ascending order for 1x/2x/4x modes, with sample ids, programmed positions, per-sample masks and a pixel-center fallback; 58 configurations and rejection/cancel cases pass. Fragment-owner execution, interpolation at the emitted positions, per-sample output merging and API exposure remain required. |
| E32-04n | Shader sample inputs/outputs | Partial | [`FragmentShaderStage`](../src/main/scala/opengpu/graphics/FragmentShaderStage.scala) transports coverage/sample mode. Complete sample variables and output semantics remain required. |
| E32-04o | Buffer-backed textures | Partial | [Native target 5 views](BUFFER_TEXTURES.md) retain full-width ranges/counts, 30 sampler formats, fetch/size and checked GIU1 addressing. Versioned Linux admission and bounded Gallium compute lowering assemble 32 textures, four full SSBOs, four independent images and GUM1 sources. Actual frontend/callback/kernel-snapshot CPU composition and serialized publication pass; general compiler/API coverage, complete visibility and composed native qualification remain required. Existing capacities stay unchanged and API capabilities stay off. |
| E32-04p | Border-color sampling | Partial | [`TextureSamplerState`](../src/main/scala/opengpu/graphics/TextureSamplerBindingUnit.scala) admits repeat, clamp-to-edge, mirrored-repeat and clamp-to-border. Outside taps return the border texel without a memory read; 2D, array and 3D sampling, integer formats, filtering edges and comparison samplers are directed-qualified. A GXS2 sampler record carries float and integer `TEXTURE_BORDER_COLOR` variants in a second 64-byte line: UNORM8, SNORM8, binary16 and FP32 round-trip exactly, sRGB runs the same nearest-byte encode/decode as stores, UNORM8 compressed bases round-trip, depth and signed compressed formats clamp, integer formats clamp to component width, and comparison samplers compare the converted depth. API/compiler exposure, shared-exponent border conversion and product qualification remain required. |
| E32-04q | Arrays of cube textures | Partial | [Image-level addressing](TEXTURE_IMAGE_ADDRESSING.md) and GXI1 admission accept target 4 with `layer*6+face` plane selection, square faces and a 16-bit plane-count ceiling. Floating cube-array sampling now remaps seamless edges across all six faces of every layer and rejects out-of-range layers before reads; three formats, three layers, six faces and edge/corner coordinates are directed-qualified. The composed sample-preparation and mip path also projects cube-array directions, rounds and clamps the layer, and samples the selected `layer*6+face` plane; 366 composed queries cover five targets including cube arrays across all formats and mip modes. API/shader builtins and product limits remain required. |
| E32-04r | Stencil-only texture access | Partial | S8 attachment storage exists. Storage ID 64 `STENCIL_INDEX8` adds a one-byte unsigned catalog entry limited to 2D and 2D-array targets; nearest typed fetch and composed sampling return the stencil byte in component 0 with `(0,0,1)` defaults (60 directed fetches). The catalog ID field widened to seven bits, GTF1 reports 65 IDs and the two-word mask covers IDs below 64. GXI1 image units still accept only read-only sampling of S8; the shader image and buffer catalogs exclude it by construction (the target-legal harness checks both, along with the one-byte, non-filterable, non-blocked storage class). API/driver admission of `STENCIL_INDEX8` texture objects remains required; stencil-only textures are single-sample, so multisample stencil sampling is intentionally excluded. |
| E32-04s | Arrays of multisample textures | Partial | GXI1 and GXL level views now carry a 2- or 4-sample count and a per-sample plane pitch; targets 6 (2DMS) and 7 (2DMSArray) validate sample/layer bounds, sample-plane addressing, exclusive level spans and one-level records, and the normalized sampling path rejects them. A `tex.fetchms`/`vtex.fetchms` instruction carries the sample index in coordinate word 6 and selects the component in funct3 bits 1:0; the native execute path fetches sample planes 0-3 from captured operands in both stages. API/driver admission and multisample sampling modes remain required. |
| E32-04t | Indexed drawing with vertex bias | Partial | [`VertexInputFetcher`](../src/main/scala/opengpu/graphics/VertexInputFetcher.scala) carries signed `baseVertex`; complete draw/API edge coverage remains unqualified. |
| E32-04u | Floating attachment rendering | Partial | [`TypedColorFormat`](../src/main/scala/opengpu/graphics/TypedColorFormat.scala) includes half/full floats. Required renderability, composition and API format coverage remain unqualified. |
| E32-04v | Robust access and context flags | Partial | Allocation/fault ownership exists. A [context robustness owner](CONTEXT_ROBUSTNESS.md) latches the first reset cause under `LOSE_CONTEXT_ON_RESET`, distinguishes guilty/innocent/unknown contexts, reports no reset under `NO_RESET_NOTIFICATION`, clears on status query and restore, and publishes the robust-access flag; two directed cases pass. Robust memory behavior at each resource owner, API object lifetime, `glReadnPixels` clamping and reset delivery from every fault owner remain required. |
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
