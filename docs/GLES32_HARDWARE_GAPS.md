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
| E32-01 | Programmable geometry | Open | [`GraphicsStage`](../src/main/scala/opengpu/graphics/TileRenderPass.scala) reserves a tag. Invocation inputs, adjacency and bounded output emission remain required. |
| E32-02 | Programmable patch control/evaluation | Open | Tags are reserved; patch storage, invocation scheduling and synchronization remain required. |
| E32-03 | Tessellated primitive generation | Open | Domain generation, spacing, winding, invariance and output ownership remain required before binning. |
| E32-04a | Extended compositing equations | Open | [`TypedBlendState`](../src/main/scala/opengpu/graphics/TypedColorBlend.scala) accepts five ordinary equations. Extended operations and their ordering remain required. |
| E32-04b | Image-to-image region copies | Partial | [`TypedTileImageTransfer`](../src/main/scala/opengpu/graphics/TypedTileImageTransfer.scala) provides attachment transfer. General image-copy compatibility, block layouts and visibility remain unqualified. |
| E32-04c | Independent draw-buffer blend/mask state | Partial | [Versioned indexed controls](INDEXED_DRAW_BUFFER_BLEND.md) select independent enable, factors, equations and masks in serial/streaming MRT stores and native packets. Descriptor snapshots, AXI discovery and four bounded native host frames pass; composed timing, full product and API qualification remain required. |
| E32-04d | Debug output and object diagnostics | Open | Host faults/completion exist. The API message, label and group contract remains unqualified. |
| E32-04e | Dynamic resource-array selection | Partial | Native texture/uniform bindings exist. Compiler indexing rules and all shader resource classes remain unqualified. |
| E32-04f | Precise arithmetic and explicit FMA | Partial | [`Fp32FmaLane`](../src/main/scala/opengpu/core/execute/fpu/Fp32FmaLane.scala) exists. Compiler operation ordering and precise-consumer invariance remain unqualified. |
| E32-04g | Gather with runtime and independent offsets | Open | Native sampling/offset paths exist; the required gather result contract remains unqualified. |
| E32-04h | ASTC LDR decoding | Open | [`TextureImageFormat`](../src/main/scala/opengpu/graphics/TextureImageFormat.scala) enumerates ETC2/EAC and fixed 4x4 blocks. ASTC formats, footprints and decoding remain required. |
| E32-04i | Submitted primitive bounds | Open | Internal raster/bin bounds exist; the API bounding-box contract remains unqualified. |
| E32-04j | Atomic image access | Open | Typed image addressing, atomic execution and cross-client visibility remain unqualified together. |
| E32-04k | Linked shader interface blocks | Partial | Versioned vertex/fragment payloads exist. Compiler block linkage and later-stage interfaces remain required. |
| E32-04l | Explicit interpolation locations | Partial | [`Fp32FragmentInterpolator`](../src/main/scala/opengpu/graphics/Fp32FragmentInterpolator.scala) supports ordinary/centroid preparation. Shader-requested interpolation locations remain unqualified. |
| E32-04m | Shading at a controlled sample rate | Open | Stored multisampling exists; sample-rate invocation scheduling remains required. |
| E32-04n | Shader sample inputs/outputs | Partial | [`FragmentShaderStage`](../src/main/scala/opengpu/graphics/FragmentShaderStage.scala) transports coverage/sample mode. Complete sample variables and output semantics remain required. |
| E32-04o | Buffer-backed textures | Open | [`TextureImageTarget`](../src/main/scala/opengpu/graphics/TextureImageAddressUnit.scala) has no buffer target; binding, fetch and limits remain required. |
| E32-04p | Border-color sampling | Open | [`TextureSamplerState`](../src/main/scala/opengpu/graphics/TextureSamplerBindingUnit.scala) exposes three wrap modes without a border-color field. |
| E32-04q | Arrays of cube textures | Open | Four current image targets include individual cubes; cube-array addressing and sampling remain required. |
| E32-04r | Stencil-only texture access | Partial | S8 attachment storage exists; the texture-format catalog has no stencil-only entry. Sampling remains required. |
| E32-04s | Arrays of multisample textures | Open | Current image descriptors lack the target/sample-index fetch contract. |
| E32-04t | Indexed drawing with vertex bias | Partial | [`VertexInputFetcher`](../src/main/scala/opengpu/graphics/VertexInputFetcher.scala) carries signed `baseVertex`; complete draw/API edge coverage remains unqualified. |
| E32-04u | Floating attachment rendering | Partial | [`TypedColorFormat`](../src/main/scala/opengpu/graphics/TypedColorFormat.scala) includes half/full floats. Required renderability, composition and API format coverage remain unqualified. |
| E32-04v | Robust access and context flags | Partial | Allocation/fault ownership exists. The context-level robustness and software flag contract remain unqualified. |
| E32-04w | Multisample line-width reporting | Open | Point/line rasterization exists; supported ranges, granularity and matching queries remain unqualified. |

The arithmetic, indexing and gather rows expand F.1's shader-functionality
entry. Geometry/tessellation interfaces and sample features have their own rows.
Robustness must follow the core contract; F.1 does not incorporate the additional
defined behavior of `KHR_robust_buffer_access`.

The [geometry/tessellation resource contract](GLES32_STAGE_RESOURCE_LIMITS.md)
maps the stage limits, coupled output budgets and combined binding namespaces
to required native resources. Use it before selecting patch/queue sizes;
boundary and fault behavior must still pass through the native host before
advertising support.
Lane/warp counts alone do not establish shader-visible limits. This inventory
does not close H0, E32-01–04 or H8, and leaves the H6 six-workload clock/rate gates
unchanged.
