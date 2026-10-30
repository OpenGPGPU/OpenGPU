# GLES 3.2 geometry and tessellation resource contract

This is the storage-sizing prerequisite for E32-01–03 in the
[H8 inventory](GLES32_HARDWARE_GAPS.md). All implementation and qualification
gates below remain **open**. These API requirements do not change native
discovery or establish an SRAM allocation.

Numeric floors come from [GLES 3.2 (May 5, 2022), tables 21.45–21.52](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf#page=509).
The [GLSL ES 3.20 language contract](https://registry.khronos.org/OpenGL/specs/es/3.2/GLSL_ES_Specification_3.20.html)
also applies to linkage, built-ins, barriers and emission. TCS, TES and GS mean
tessellation control, tessellation evaluation and geometry shader respectively.
Each `MAX_*` value is a required minimum supported limit, not a mandate for
that many concurrent hardware lanes. Component counts include the applicable
built-ins and packing rules; they are not counts of user varyings alone.

## Stage and amplification limits

| API limit | Floor | Native resource or operation to qualify |
|---|---:|---|
| `MAX_PATCH_VERTICES` | 32 | Input/output patch ownership and invocation indices |
| `MAX_TESS_GEN_LEVEL` | 64 | Domain generator, subdivision and output backpressure |
| `MAX_TESS_CONTROL_INPUT_COMPONENTS` | 64 | Per-input-vertex patch reads |
| `MAX_TESS_CONTROL_OUTPUT_COMPONENTS` | 64 | Per-output-vertex writes and barrier visibility |
| `MAX_TESS_PATCH_COMPONENTS` | 120 | Shared patch outputs |
| `MAX_TESS_CONTROL_TOTAL_OUTPUT_COMPONENTS` | 2048 | Combined patch-output accounting |
| `MAX_TESS_EVALUATION_INPUT_COMPONENTS` | 64 | Patch reads indexed by generated coordinates |
| `MAX_TESS_EVALUATION_OUTPUT_COMPONENTS` | 64 | Generated-vertex export |
| `MAX_GEOMETRY_INPUT_COMPONENTS` | 64 | Input topology, including six-vertex triangle adjacency |
| `MAX_GEOMETRY_OUTPUT_COMPONENTS` | 64 | Export record and downstream interface |
| `MAX_GEOMETRY_OUTPUT_VERTICES` | 256 | Per-invocation emission counter and primitive boundaries |
| `MAX_GEOMETRY_TOTAL_OUTPUT_COMPONENTS` | 1024 | Per-invocation output accounting |
| `MAX_GEOMETRY_SHADER_INVOCATIONS` | 32 | Ordered invocation ownership for each input primitive |

Sections 11.2.1.2 and 11.3.4 constrain combinations: TCS output vertices times
per-vertex components plus per-patch components must fit the total limit; GS
declared output vertices times active output components must fit its total.
Tessellation levels are excluded from the patch-component count, while a
written bounding box counts. Store their metadata explicitly. TCS barriers
must publish preceding patch-output writes to all participating invocations.
GS output order follows input primitive then invocation order, even with
out-of-order CU completion.

The following are design calculations for a minimum-limit profile, using
four bytes per component. They are **logical payload sizes**, excluding
alignment, banking, identity, validity, tessellation levels and allocator state:

| Payload calculation | Bytes | Consequence for the implementation |
|---|---:|---|
| One full input patch: `32 * 64 * 4` | 8192 | Retain inputs while any invocation can read them |
| One maximum counted TCS output: `2048 * 4` | 8192 | Account for per-vertex and per-patch data together |
| One adjacency GS input: `6 * 64 * 4` | 1536 | Share immutable inputs across invocation owners |
| One maximum counted GS output: `1024 * 4` | 4096 | Stream or retain with exact emission ownership |

For example, `32 * 64 + 0 = 2048` and `32 * 60 + 120 = 2040` fit the
TCS total, whereas `32 * 61 + 120 = 2072` does not. A GS can fit
`256 * 4 = 1024` or `16 * 64 = 1024`; multiplying all independent maxima
would overstate the required payload. These examples assume the component
counts already include every counted output.

Choose a resident patch count only after measuring input/output overlap and
barrier scheduling. A suspended invocation must not occupy all execution
credits needed by its peers. Serial GS invocations may share an output window;
concurrent invocations need separate ownership until ordered consumption.
Level 64 is a subdivision limit, not a 64-vertex queue size. The generator must
stream the complete domain through bounded credits without truncation.

## Per-stage and combined resource namespaces

`STAGE` below expands separately to `TESS_CONTROL`, `TESS_EVALUATION` and
`GEOMETRY`. These rows are requirements, not new native feature flags.

| API limit | Floor or ceiling | Integration owner |
|---|---:|---|
| `MAX_STAGE_UNIFORM_COMPONENTS` | 1024 each | Stage-local default views |
| `MAX_STAGE_UNIFORM_BLOCKS` | 12 each | Program block maps |
| `MAX_STAGE_TEXTURE_IMAGE_UNITS` | 16 each | Stage-owned sampler roots |
| `MAX_COMBINED_STAGE_UNIFORM_COMPONENTS` | 50176 at minimum limits | Default plus block storage; recalculate from advertised limits |
| `MAX_UNIFORM_BUFFER_BINDINGS` | 72 | Context binding namespace |
| `MAX_COMBINED_UNIFORM_BLOCKS` | 60 | Linked-program accounting |
| `MAX_UNIFORM_BLOCK_SIZE` | 16384 bytes | Checked view extent |
| `UNIFORM_BUFFER_OFFSET_ALIGNMENT` | At most 256 bytes | Binding admission |
| `MAX_COMBINED_TEXTURE_IMAGE_UNITS` | 96 | Cross-stage active-unit accounting |
| `MAX_FRAGMENT_UNIFORM_COMPONENTS` / `MAX_FRAGMENT_UNIFORM_VECTORS` | 1024 / 256 | Increase the earlier fragment default-block contract |
| `MAX_STAGE_ATOMIC_COUNTER_BUFFERS`, `MAX_STAGE_ATOMIC_COUNTERS`, `MAX_STAGE_SHADER_STORAGE_BLOCKS`, `MAX_STAGE_IMAGE_UNIFORMS` | 0 each | Report actual support; positive advertised values require qualification |

The combined uniform calculation is `blocks * blockBytes / 4 + defaultComponents`.
Texture access to the same unit from different stages counts separately toward
the combined limit (§11.1.3.5). Zero minima for the three later stages do not
remove H7's positive compute storage, image and atomic requirements. GLES 3.1
also permits zero vertex/fragment minima for these resources; see the
[3.1 resource map](GLES31_RESOURCE_LIMITS.md). Positive advertised graphics
support requires separate qualification.

The existing [uniform ABI](../src/main/scala/opengpu/graphics/ShaderUniformResources.scala)
has 24 bindings, five-bit map entries, a one-bit stage and a two-bit stage mask;
both stages now permit 1,024 default components through the
[checked word-load path](UNIFORM_BUFFER_RESOURCES.md#expanded-fragment-default-capacity).
A compatible versioned successor must address the larger namespace and additional stages. Merely
raising `bindings` would leave the serialized fields too narrow. The existing
[32-slot texture namespace](../src/main/scala/opengpu/graphics/NativeTextureResources.scala)
can supply a stage-local map, but does not prove a 96-unit API namespace.
[GUC1](../src/main/scala/opengpu/graphics/NativeUniformCapabilities.scala)
must continue reporting the implemented vertex/fragment contract until a
qualified successor exists.

## Implementation and acceptance sequence

The [reserved stage tags](../src/main/scala/opengpu/graphics/TileRenderPass.scala)
are geometry=3, TCS=4 and TES=5. The current
[shared-CU interface](../src/main/scala/opengpu/system/SharedCuGraphicsPort.scala)
provides task transport; its vertex/fragment client allocation does not yet
provide these stage lifetimes.

| Work item | Required evidence before acceptance |
|---|---|
| Versioned descriptor and capacity admission | Exact-limit acceptance, over-limit rejection before memory access, checked address arithmetic, old ABI compatibility and truthful discovery |
| Patch ownership and TCS execution | Independent input/output patch sizes through 32; full counted payload; repeated barriers across scheduled invocations; held responses, faults, cancellation, reset and healthy reuse |
| TES and domain generation | Triangles/quads/isolines, equal/fractional spacing, winding and point mode; zero/edge/max levels, invariance and exact ordered topology under stalls |
| GS input/output execution | Every input topology, adjacency, 32 invocations, coupled output limits, emit/end boundaries, incomplete strips, IDs/layers and ordered draining |
| Native graphics composition | Real CU execution into bins/raster/attachments; final-stage transform feedback and queries; simultaneous resource mappings, VM ownership and cross-stage faults |
| Product qualification | Full emitted storage/macro inventory, enclosing timing, measured rate/traffic and the existing six-scene product targets |

Use bounded native fixtures and the standard `GraphicsTestResolution` policy
for implementation regressions. Large numeric limit inputs do not require
large render targets. Keep raw qualification data local under `.artifacts/`;
record only final results and reproducible commands when these gates pass.
This contract closes the limit-mapping prerequisite only; E32-01–03 and H8
remain open, alongside the [remaining 3.2 feature delta](GLES32_HARDWARE_GAPS.md).
