# Mobile GPU hardware capability inventory

Source baseline: `d588b50`, 2026-10-19. This inventory supports H0 and the
[hardware alignment plan](MOBILE_GPU_HARDWARE_ALIGNMENT.md). It records hardware
contracts and evidence gaps; it does not advertise an API version. Compiler,
driver and API object management remain separate work, while the hardware
interfaces they require remain in scope.

`Partial` means relevant RTL exists but the complete requirement is unproven.
`Open` means the inspected production contract does not provide the required
interface or its verification. A module or capability bit alone cannot close a
row. Every row needs a frozen source/configuration, an independently checked
result and integrated fault/ordering evidence before promotion to verified.

The normative baselines are [GLES 3.0.6](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf),
[GLES 3.1](https://registry.khronos.org/OpenGL/specs/es/3.1/es_spec_3.1.pdf) and
[GLES 3.2](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf).
The tables group requirements for implementation. They must be expanded into
individual format, limit and operation cases before H6–H8 closure; they are not
an exhaustive specification-clause or conformance-test mapping.

## Source and evidence keys

| Key | Hardware contract | Relevant checks or frozen evidence |
|---|---|---|
| CFG | [Mobile configuration](../src/main/scala/opengpu/config/MobileGpuConfig.scala), [capability words](../src/main/scala/opengpu/graphics/GpuCapabilities.scala) | [Configuration checks](../src/test/scala/opengpu/config/MobileGpuConfigSpec.scala), [C ABI checks](../src/test/scala/opengpu/graphics/GpuAbiLayoutSpec.scala) |
| VTX | [Vertex task stage](../src/main/scala/opengpu/graphics/KernelVertStage.scala) | [Vertex execution](../src/test/scala/opengpu/graphics/KernelVertStageSpec.scala), [index-width regression](../src/test/scala/opengpu/graphics/KernelVertIndexWidthSpec.scala) |
| GEO | [Clip stage](../src/main/scala/opengpu/graphics/NearClipStage.scala), [prepared geometry](../src/main/scala/opengpu/graphics/PreparedGeometryStage.scala), [rasterizer](../src/main/scala/opengpu/graphics/Rasterizer.scala) | [Prepared geometry checks](../src/test/scala/opengpu/graphics/PreparedGeometryStageSpec.scala), [sample coverage checks](../src/test/scala/opengpu/graphics/PreparedSampleCoverageSpec.scala) |
| SHD | [Fragment tasks](../src/main/scala/opengpu/graphics/KernelFragStage.scala), [shared task router](../src/main/scala/opengpu/graphics/SharedShaderTaskRouter.scala), [CU](../src/main/scala/opengpu/core/GpuComputeUnit.scala) | [Quad export checkpoint](performance/mobile-shader-quad-exports-2026-10-19.json), [task routing checks](../src/test/scala/opengpu/graphics/SharedShaderTaskRouterSpec.scala) |
| TEX | [Sampler](../src/main/scala/opengpu/graphics/TextureUnit.scala), [coordinates](../src/main/scala/opengpu/graphics/TextureCoordinates.scala), [worker memory fabric](../src/main/scala/opengpu/graphics/FragmentWorkerMemory.scala) | [Sampler checks](../src/test/scala/opengpu/graphics/TextureUnitSpec.scala), [NPOT vector checks](../src/test/scala/opengpu/graphics/TextureNpotVectorSpec.scala), [coordinate checks](../src/test/scala/opengpu/graphics/TextureCoordinatePipelineSpec.scala) |
| PIX | [Merge state/arithmetic](../src/main/scala/opengpu/graphics/TileSampleMergePipeline.scala), [banked sample owner](../src/main/scala/opengpu/graphics/TileSampleQuadMerge.scala), [pixel formats](../src/main/scala/opengpu/graphics/PixelFormat.scala) | [Merge checks](../src/test/scala/opengpu/graphics/TileSampleMergePipelineSpec.scala), [ordering checks](../src/test/scala/opengpu/graphics/TileSampleQuadMergeSpec.scala), [resident backend checkpoint](performance/mobile-resident-sample-backend-2026-10-19.json) |
| TILE | [Persistent attachments](../src/main/scala/opengpu/graphics/PersistentTileAttachmentStore.scala), [memory-submitted bins](../src/main/scala/opengpu/graphics/MemoryTileBinRenderPipeline.scala) | [Tile-bin checks](../src/test/scala/opengpu/graphics/TileBinRenderPipelineSpec.scala), [native host integration checkpoint](performance/mobile-native-binned-host-integration-2026-10-19.json) |
| HOST | [AXI host/system](../src/main/scala/opengpu/system/GpuHostSystemAxi.scala), [shared CU interface](../src/main/scala/opengpu/system/SharedCuGraphicsPort.scala) | [Binned AXI checks](../src/test/scala/opengpu/graphics/BinnedTileHostAxiSpec.scala), [VM ownership checks](../src/test/scala/opengpu/system/GpuHostVmOwnershipSpec.scala), [texture visibility checks](../src/test/scala/opengpu/system/PersistentTileTextureVisibilitySpec.scala) |
| EXEC | [SIMT control](../src/main/scala/opengpu/core/frontend/simt/SimtControlFlow.scala), [FP backend](../src/main/scala/opengpu/core/backend/FpuBackend.scala), [shared atomics](../src/main/scala/opengpu/core/execute/memory/SharedAtomicExecuteStage.scala), [workgroup barrier](../src/main/scala/opengpu/core/system/WorkgroupBarrierController.scala) | [SIMT checks](../src/test/scala/opengpu/core/frontend/simt/SimtControlFlowSpec.scala), [FP checks](../src/test/scala/opengpu/core/backend/FpuBackendSpec.scala), [atomic checks](../src/test/scala/opengpu/core/execute/memory/SharedAtomicExecuteStageSpec.scala), [barrier checks](../src/test/scala/opengpu/core/system/WorkgroupBarrierControllerSpec.scala) |

These links identify the implementation and tests to inspect. They do not assert
that every linked suite was rerun on this baseline. The integration checkpoint
records 20 completed initial checks and explicitly leaves the broader native
regression and production hierarchy qualification pending.

## GLES 3.0 hardware work

The requirement groups below follow the GLES 3.0 specification's vertex,
rasterization, texture, framebuffer and synchronization chapters. Current-state
assessments are engineering conclusions from the linked RTL, not Khronos claims.

| ID | Requirement group | Current evidence and gap | State | Owner |
|---|---|---|---|---|
| V01 | Attribute formats | VTX reads a fixed eight-word vertex record. General attribute tables, conversion and component defaults need a bounded descriptor contract. | Partial | H5/H6 |
| V02 | Indices and restart | VTX batches triangle-list vertices. Its internal index-width test does not prove GPU element-buffer fetching or restart. Add native index types, bounds and restart ownership. | Open | H5 |
| V03 | Instancing and divisors | VTX descriptor has no general instance/divisor layout. Add instance/vertex IDs, per-binding rates and exact repeated-index tests. | Open | H5 |
| V04 | Vertex shader execution | VTX and SHD execute kernels; input/output layouts are fixed. Verify general varyings, resource bindings and supported vertex execution in the binned Mobile path. | Partial | H5/H6 |
| V05 | Uniforms and blocks | SHD/VTX expose a kernarg tail. Resource-table binding, layout, range protection and per-stage limits remain unqualified. | Partial | H0/H5/H6 |
| V06 | Varying interpolation | GEO/SHD carry selected color, UV and depth values. Add general component layouts and per-varying interpolation/provoking-vertex state. | Partial | H5/H6 |
| V07 | Transform feedback | No capture destination or capture lifetime appears in the inspected VTX/TILE descriptors. Add bounded output, pause/resume and count ownership. | Open | H5/H6 |
| G01 | Primitive assembly | GEO accepts triangles. Add the remaining primitive assembly modes and cross-batch strip/fan/restart state. | Partial | H5/H6 |
| G02 | Clipping and viewport | GEO implements staged clipping and viewport setup. Broader numeric, primitive and invariant coverage remains required. | Partial | H6 |
| G03 | Points and lines | GEO's triangle path does not establish native point/line raster contracts. Add coverage, endpoints, interpolation and multisample cases. | Open | H6 |
| G04 | Facing and polygon offset | GEO/PIX provide triangle/depth machinery. Audit the complete facing, culling and offset state through descriptors and per-sample results. | Partial | H6 |
| S01 | Numeric behavior | EXEC provides FP32 and integer execution. Publish operation-level precision, rounding and exceptional-value contracts; test compiler-generated instruction sequences separately. | Partial | H0/H6 |
| S02 | Flow and termination | EXEC provides SIMT branch machinery. Shader resource validation, reconvergence capacity, watchdog interruption and attributed fault drain need one general contract. | Partial | H5/H6 |
| S03 | Helpers and derivatives | SHD batches complete quads and exports covered lanes. Verify all relevant divergent/helper execution and side-effect masks with the general resource path. | Partial | H5/H6 |
| S04 | Fragment outputs | SHD exports one packed color, control and selected depth. General typed locations and attachment outputs remain required. | Partial | H3/H6 |
| T01 | Image/sampler bindings | TEX uses task-snapshotted legacy sampler fields. Add indexed image/sampler descriptors, per-stage limits and binding/context generations. | Partial | H0/H4 |
| T02 | Texture dimensions | TEX exposes a 2D base/extent. Cube, array and 3D addressing need explicit descriptors and operation coverage. | Partial | H4/H6 |
| T03 | Wrap and coordinates | TEX handles repeat/clamp and non-power-of-two extents in its unsigned coordinate contract. General signed coordinates and remaining wrap behavior still need qualification. | Partial | H4/H6 |
| T04 | Filtering and LOD | TEX implements bilinear and mip/trilinear paths. General fetch/LOD/gradient modes, precision and independent sampler state remain unqualified. | Partial | H4/H6 |
| T05 | Depth comparison | TEX has no general comparison-sampler descriptor. Add comparison before the appropriate filtering step and test border/LOD behavior. | Open | H4/H6 |
| T06 | Texture format families | PIX converts five legacy packed byte orders. Required normalized, integer, floating and depth fetch/decode families need a separate format table. | Partial | H4/H6 |
| T07 | sRGB and ETC2/EAC | The inspected TEX/PIX path does not implement these decode contracts. Add format-specific numeric and block-edge checks. | Open | H4/H6 |
| T08 | Stage texture access | SHD routes task-owned texture instructions. Vertex/fragment resource availability and limits need integrated verification in each supported product path. | Partial | H4/H5/H6 |
| T09 | Mips and transfers | TEX reads packed mip chains. General mip metadata, texture update/copy destinations and visibility remain to be specified and checked. | Partial | H4/H6 |
| F01 | Attachment formats | TILE implements legacy RGBA8 plus D24S8 storage. Required color/depth/stencil renderability and conversions remain a format-by-format gate. | Partial | H3/H6 |
| F02 | Multiple draw buffers | PIX/SHD carry one color result. Add typed MRT exports, attachment selection, masks and serialized or parallel ownership. | Open | H3/H6 |
| F03 | Depth and stencil | PIX implements legacy tests and operations. Complete API state, face selection and format-dependent semantics remain unproven. | Partial | H3/H6 |
| F04 | Blend state | PIX has one source/destination factor pair and one equation. Separate RGB/alpha state, constants and format-specific behavior remain required. | Partial | H3/H6 |
| F05 | Masks and dithering | PIX has color/depth/stencil masks. General dithering/precision behavior and all clear interactions remain unqualified. | Partial | H3/H6 |
| F06 | Multisampling | TILE/SHD exercise 1x/2x/4x stored samples and resolves. Full coverage/alpha/sample-state behavior across required formats remains open. | Partial | H3/H6 |
| F07 | Scissor and clears | TILE supports bounded partial clears and scissored work. Complete framebuffer state combinations and general target formats remain required. | Partial | H1/H3/H6 |
| F08 | Reads, copies and blits | HOST supports bounded DMA/copy paths and TILE exports/resolves. General format conversion, scaled blits and pixel-transfer layouts remain unqualified. | Partial | H6 |
| Q01 | Visibility queries | Counters in HOST/PIX observe activity. They do not implement query begin/end/result lifetime or all sample-count semantics. | Open | H6 |
| Q02 | Primitive queries | TILE counts accepted references/draws. Transform-feedback primitive accounting and API query ownership remain absent from its submission contract. | Open | H5/H6 |
| M01 | Ordered visibility | TILE/HOST implement explicit stores, publication and VM protection. Concurrent dependencies and general resource-domain fences remain open. | Partial | H1/H2/H6 |
| M02 | Fences and completion | HOST has command completion/reset drain. Audit a general fence contract across all resource owners and contexts before mapping API synchronization. | Partial | H0/H6 |
| M03 | Resource bounds | TILE/HOST check bounded descriptors and faults. Extend allocation, permission, context and generation checks to every new geometry/texture/payload interface. | Partial | H0/H4/H5/H6 |

## Selected capacity blockers

The GLES 3.0 minimum values below come from its implementation-dependent limit
tables. Each needs a discoverable implemented limit and a boundary test; a wide
address field is insufficient evidence.

| Capacity | GLES 3.0 minimum | Current production contract | Remaining evidence |
|---|---|---|---|
| Vertex attributes | 16 | Fixed VTX record | General descriptors and maximum-location execution |
| Draw buffers | 4 | One color output | MRT locations, state and exact stored results |
| Color attachment bindings | 4 | One color plus depth/stencil | General pass attachment table and capacity admission |
| 2D and cube dimensions | 2048 each | Legacy 2D extent fields; cube addressing unimplemented | Both dimensions, all faces, levels, bounds and faults |
| Array layers | 256 | No general layer descriptor | Layer pitches, selection and cross-layer guards |
| 3D dimension | 256 | No general volume descriptor | Slice addressing, filtering and bounds |

Uniform, varying, texture-unit, renderbuffer and per-stage resource limits still
need individual rows from the normative tables. Until that expansion and its
tests are complete, H0's general feature/limit contract and H6's capability gate
remain open. Proposal storage budgets and CU lane/warp counts cannot substitute
for shader-visible binding limits.

## Later capability stages

GLES 3.1 adds compute and storage/image operations with explicit memory control.
The existing compute backend is a starting point. These rows remain H7 work,
with their resource limits and numeric formats to be expanded from the 3.1
specification.

| ID | Requirement group | Existing starting point and remaining hardware contract | State |
|---|---|---|---|
| E31-01 | Compute resources | EXEC/HOST execute kernels. General dispatch geometry, built-ins, resources and advertised limits need qualification. | Partial |
| E31-02 | Storage buffers/images | CU memory execution exists. Add typed image/storage descriptors, bounds and format-dependent operations. | Partial |
| E31-03 | Atomics | EXEC implements shared atomic requests. General required storage classes, ordering and contention/fault coverage remain open. | Partial |
| E31-04 | Memory and workgroup barriers | EXEC has a workgroup barrier; HOST has publication. Complete execution/memory-domain ordering across images, textures, attachments and DMA remains open. | Partial |
| E31-05 | Indirect work | HOST fetches bounded command records. API indirect argument layouts, validation and producer/consumer dependencies remain unqualified. | Partial |

GLES 3.2 includes geometry and tessellation stages. H8 must provide bounded
amplification, patch storage, inter-stage ownership and stage ordering; reusing
CU arithmetic alone does not supply those contracts.

| ID | Requirement group | Existing starting point and remaining hardware contract | State |
|---|---|---|---|
| E32-01 | Geometry execution | SHD routes vertex/fragment tasks. Add geometry stage tags, invocation/input topology and bounded output assembly before bins. | Open |
| E32-02 | Tessellation execution | Add control/evaluation task scheduling, patch payloads, cross-invocation synchronization and limits. | Open |
| E32-03 | Primitive generation | Add tessellation topology, spacing, winding and bounded generated-vertex ownership. | Open |
| E32-04 | Remaining 3.2 features | Expand texture, raster, blend, numeric and resource-limit deltas from the 3.2 specification into individual implementation rows. | Open |

## Architectural gates beyond API feature presence

| Gate | Current evidence | Required next result |
|---|---|---|
| On-chip shader payloads | [Opt-in AXI renderer](performance/mobile-local-payload-renderer-2026-10-19.json) connects shared storage through both tile engines; exact resident/mixed/spill images, four-CU occupancy, publication, faults and live-reset reuse pass | Promote the Mobile default after broader qualification; production hierarchy, representative shaders/workloads, geometry and synthesis remain open |
| Texture cluster throughput | TEX issues four tap reads across the shared word fabric | Banked hits and filters sustain one bilinear sample/cycle/cluster; include conflicts, refills and faults |
| Pixel throughput | PIX's resident backend supports four sample commits/cycle in component tests | Native integrated workload gates, legal early/late equivalence and synthesis timing |
| Native Mobile integration | [74 native renderer/AXI checks and production hierarchy audit](performance/mobile-native-binned-host-2026-10-19.json) pass, including the four-CU host profile | Integrate the remaining payload/features and qualify representative workloads and synthesis |
| Representative frame budget | Earlier checkpoints measure specific component/frame workloads | Full agreed 720p suite with all phases, bounded bandwidth and qualified clock |
| Current PPA screen | Synthesis-only policy; integrated renderer synthesis remains unqualified | Complete mapped area/power and all-I/O setup reports on frozen sources |

Adding this inventory closes no implementation milestone. H0–H8 retain their
original deliverables and acceptance gates in the alignment plan.
