# Mobile GPU hardware alignment proposal

Status: proposed hardware architecture with H0/H1/H2/H3/H5/H6 implementation in progress, 2026-10-20. Baseline: `d038228`. Current PPA evaluation uses synthesis only; functional hardware development remains in progress.

Evolve OpenGPU into a mobile rendering architecture by extending the shared CU pool with persistent tile storage, GPU geometry fetching, scalable texture and pixel backends, and general graphics resource descriptors. Keep shader arithmetic on the existing CUs. The first functional target is hardware sufficient for a complete GLES 3.0 implementation; a Mali-G31-class commercial comparison requires further feature, performance, and physical qualification.

The configuration and rates below are design targets, not measured capabilities. Implementation should proceed in bounded milestones, with correctness, throughput and timing gates before integration. Compiler and driver implementation are outside this plan; hardware interfaces needed by them are included.

The device compiler is already underway in a sibling tree, not in this repository. LLVM/clang on `../llvm-project` branch `opengpu` targets the current CU ISA as the RISC-V vendor extension `XOpengpu` (triple `opengpu-unknown-none-elf`). Assembler, codegen, clang and a fatbin loader in `userspace/libopengpu` are in place. The 2026-10-19 vertex+fragment Verilator guest ran `examples/compute_offload` and checked `kadd` (`1 + 2 + 3 = 6`). See [LLVM_TOOLCHAIN_PLAN.md](LLVM_TOOLCHAIN_PLAN.md). Keep the decoder encodings, custom opcodes (`cease`, `barrier`, texture sample, `vquad.dfdx`/`dfdy`), reserved registers `x1`/`x8`/`x9`/`x30`/`x31`, kernarg addressing, and `vsetvli` `e32,m1,tu,mu` stable, or update that branch in the same change. The driver validator in `driver/opengpu_shader_validator.h` is still what admits a shader.

## Target and scope

Use Mali-G31 as an older commercial reference class, rather than treating it as the definition of a current entry-level GPU. Arm lists GLES 3.2 support for that device. GLES 3.0 is therefore an intermediate capability target. [Arm Mali-G31](https://support.arm.com/compute-ip/mali-g31)

The initial OpenGPU product target is four shared CUs, with a 720p workload suite targeting 30 FPS first and 60 FPS subsequently at a physically qualified clock. Test desktop composition, textured 3D, opaque overdraw, transparent layers, small triangles, and 4x MSAA separately. These targets do not imply measured performance parity with a particular commercial device.

The hardware contract must cover shader execution, geometry, texture resources, rasterization, framebuffer operations, synchronization and faults. Display scanout remains a consumer of completed images. Its refresh rate is separate from rendering throughput.

## Current hardware and gaps

| Area | Current RTL or interface | Required evolution |
|---|---|---|
| Shader execution | Shared compute CUs already execute vertex and fragment tasks; vector FP32 arithmetic, including FMA, is connected | Improve task input/output transport, occupancy, control-flow/resource contracts and sustained issue/writeback throughput |
| Configuration | `GpuConfig` defaults to 16 lanes and eight warps; the mobile CU-count constant is four | One compute/graphics execution configuration; discoverable resource limits and explicit supported configuration combinations |
| Interstage transport | Eligible Mobile fragments default to local payloads through the parallel/tile/AXI hierarchy; a composed generic vertex/raster/fragment owner qualifies two-client payload and shared-CU task lifetime | Integrate native bin/host and typed attachment ownership; budget composed SRAM and qualify broader resources and sustained transport |
| Geometry | Bounded GPU fetch, indexed/instanced shared-CU vertex execution, seven-topology assembly, FP32 clipping/viewport, owned point/line/triangle raster/interpolation and fragment shaders connect GPU vertex work to native GTJ1 submission and bounded typed image publication | General native binning/resource ownership, qualifier/guard-band closure, full Mobile runtime and post-transform reuse |
| Tiling | Optional pass-lifetime RGBA8 plus D24S8 attachments with two SRAM slots per engine; paged rendering, two engines, automatic continuation, masked line transfers, bounded unified AXI host submission/reset/VM and sequential GPU input publication | Concurrent producer dependencies, physical allocation ownership and representative transfer/frame throughput |
| Texturing | Per-worker samplers; four physical tap-read credits per sampler; bilinear and mip/trilinear paths; a shared single-word request/response fabric | Banked texture hits, scalable filtering throughput, general image/sampler descriptors, more dimensions and formats |
| Pixel operations | Mobile binned rendering defaults to banked RGBA8/D24S8 attachments with fixed/programmed quad ingress; initial AXI checks exercise four-sample commits on both engines | Integrated synthesis/timing, legal early rejection, representative throughput, multiple color outputs and complete format semantics |
| Memory | Shared banked L2, Sv32 translation, GPU cache invalidation and AXI memory master | Aggregate bandwidth and outstanding budgets, context-tagged tile/texture state, scoped barriers and robust bin/payload memory |

The constructor default AXI beat is eight bytes, while emission supports 4 through 64 bytes. Benchmark configurations must record the selected width; there is no single width shared by every existing artifact. See [GpuHostSystemAxi](../src/main/scala/opengpu/system/GpuHostSystemAxi.scala) and [the emitter](../src/main/scala/opengpu/elaboration/EmitGpuHostSystemAxi.scala).

Program length limits also belong to different layers: the checked-in Gallium emitter has a 256-word code container, while the driver validator admits at most 512 instructions and four forward branch states. These are not a demonstrated 256-instruction CU fetch limit. General shader support requires a new submission and protection contract, rather than merely enlarging an RTL array. See [the validator](../driver/opengpu_shader_validator.h), [Gallium emitter](../userspace/gallium/opengpu_fs_emit.c) and [kernel shader launch](../src/main/scala/opengpu/graphics/KernelShaderStage.scala).

## Proposed block organization

```mermaid
flowchart TD
    CMD[Command processor and resource descriptors] --> GEO[Index and attribute fetch]
    GEO --> VS[Vertex tasks]
    VS --> CU[Shared CU pool]
    CU --> EXPORT[Tagged shader export queues]
    EXPORT --> SETUP[Primitive assembly clipping and setup]
    SETUP --> BIN[Primitive cache and bin allocator]
    BIN --> TS[Tile scheduler]
    TS --> TILE[Replicated tile engines]
    TILE --> FS[Fragment task queues]
    FS --> CU
    CU <--> TEX[Texture clusters with banked caches and filters]
    EXPORT --> PIX[Tile ordered pixel backends]
    PIX <--> SRAM[Banked color depth and stencil SRAM]
    TILE <--> SRAM
    SRAM --> STORE[Attachment load store and resolve]
    GEO --> MEM[Shared translation L2 and memory fabric]
    BIN --> MEM
    TEX --> MEM
    STORE --> MEM
    CU --> MEM
    MEM --> AXI[AXI memory master]
```

A tile engine contains raster work generation, fragment dispatch state, a pixel backend and attachment storage. It can dispatch work to the shared CUs without owning a separate shader core. Texture clusters initially have locality affinity to pairs of CUs; scheduling may steal work when the destination has the necessary descriptors and payload access.

Shader lane count comes only from `GpuConfig`. A raster quad remains four pixels because derivatives use a 2x2 neighborhood. Raster quads, texture taps, SRAM banks and pixel pipelines are throughput resources with different units; none introduces a second shader VLEN.

## Initial four CU sizing

Keep these values in one proposed hardware configuration record. Derive transaction IDs, queue indices and SRAM addressing from it. Architectural limits, runtime bindings and elaboration-time capacities must be separate fields.

| Resource | Initial proposal | Meaning and scaling rule |
|---|---|---|
| Shared CUs | 4 CUs, 16 lanes, 8 resident warps per CU | Reuse existing execution profile; retain supported lane/warp parameterization |
| Tile engines | 2, initially 1 for bring-up | Default `ceil(CUs / 2)` for balanced configurations; independently selectable for isolation experiments |
| Raster output | 1 quad/cycle/engine | Eight candidate pixels/cycle at two engines; coverage and interpolation availability can reduce useful output |
| Texture clusters | 2 | Initially one per pair of CUs; requests include task, lane, descriptor and destination tags |
| Texture throughput | 1 RGBA8 bilinear sample/cycle/cluster | Conflict-free resident-data target; four tap words/cycle/cluster. Trilinear requires two bilinear groups |
| Texture cache | 8 KiB data/cluster, 64-byte lines, at least 4 word banks | Starting point for a capacity/bank sweep, including refill and response bandwidth |
| Texture outstanding fills | Start at 16 line fills/cluster; sweep 8/16/32 | Final value follows measured miss demand and memory latency; separate line fills from sample/tap credits |
| Pixel throughput | 4 sample updates/cycle/engine | RGBA8 plus D24S8, conflict-free resident data; includes the required old-value reads and writebacks |
| Tile attachment SRAM | 64 KiB data/engine | Two 32 KiB slots for rendering and attachment transfer; tags, queues and metadata are additional |
| Shader payload SRAM | 64 KiB shared, banked | Sweep 32/64/128 KiB; admission accounts for live input/output bytes and reserves a spill or drain path |
| Primitive and state cache | 32 KiB shared | Cache compact primitive/state records; software-allocated bin pages provide bounded backing storage |
| Color attachments | Architectural capacity 4 | MRT can serialize attachment work; the single-target throughput target does not imply four-target peak throughput |
| Shared L2 | Initially 128 KiB and 4 banks; sweep 64/128/256 KiB | Include bin, payload, texture and instruction contention in the comparison |
| External AXI | Initial integration candidate 256 bits; retain width parameter | Compare 128/256/512 at equal memory latency, controller limits and realistic bus efficiency |

At two texture clusters, the bilinear target needs eight 32-bit taps/cycle, or 32 bytes/cycle of cache-hit delivery. At two pixel engines, the target is eight sample updates/cycle: at most eight pixels/cycle at 1x, or two pixels/cycle at 4x when every sample is processed. Neither rate is a whole-frame guarantee.

The tile data calculation is:

`bytes per tile = width * height * samples * (sum(color attachment bytes) + depth/stencil bytes)`

| Tile layout | Data bytes | Data capacity |
|---|---:|---:|
| One RGBA8 plus D24S8, 4x | 8,192 | 8 KiB |
| Four RGBA8 plus D24S8, 4x | 20,480 | 20 KiB |
| Four RGBA16F plus D24S8, 4x | 36,864 | 36 KiB |
| Four RGBA16F plus D24S8, 4x, using 8x8 tiles | 9,216 | 9 KiB |

A 32 KiB slot therefore handles four RGBA8 attachments at 4x but requires smaller tiles for the illustrated four-RGBA16F layout. Support 8x8 and 16x16 tile classes through a shared descriptor and address calculation. Select a fitting tile class at pass admission. RGBA16F rendering/blending is a later product feature with explicit semantics; it is not implied to be required by base GLES 3.0.

The proposed tile, texture, payload, primitive and 128 KiB L2 arrays, plus four 64-byte attachment read-cache lines per slot, total 369 KiB of data storage. The read caches contribute 1 KiB over two engines and two slots per engine. This excludes CU register files, instruction/data caches, shared memory, tags, queues and other state, including 512 bytes of registered attachment write-gather data. SRAM area and energy depend on the selected technology and port organization; this is a capacity budget rather than a chip-area estimate. Earlier frozen evidence retains its original 368 KiB budget.

Payload occupancy must also be counted in bytes. For example, 64 FP32 varying components across 128 resident threads occupy 32 KiB of input values per CU before outputs or metadata. A 64 KiB shared payload budget cannot hold that case for all four CUs simultaneously. Admission must reduce concurrency, interpolate values on demand, or spill through the defined fallback; it must never assume maximum varying count and maximum task residency are free to combine.

## Hardware work packages

### Render pass descriptors and tile lifetime

Add versioned descriptors for attachments, image views, samplers, shader resources, vertex layouts and render passes. Attachment views carry base, allocation bounds, format, extent, row/layer pitch, mip/layer selection and sample count. A pass snapshots its descriptors and assigns a context, pass ID and generation so later host updates cannot change in-flight work.

Keep attachments resident across compatible draws. Implement load, clear and discard on entry; store, discard and resolve on exit. A partial clear or scissor must preserve untouched samples. Clear tags may defer SRAM initialization, but reads must materialize the specified clear value. Resolve samples in tile storage and export only the selected result where permitted.

Texture reads of prior render output, DMA access, CPU visibility, incompatible attachments, queries requiring completion, and explicit barriers establish dependency boundaries. Store required dirty state and publish it to the relevant GPU cache domain before admitting dependent reads. Do not equate an L2 write with immediate CPU-cache coherence.

### Primitive storage and tile scheduling

Replace the hardcoded eight-triangle limit with bounded on-chip primitive/state caches and software-allocated bin pages. Store compact references to immutable primitive and draw state. Allocate pages with checked bounds and tagged ownership; overflow either spills to valid backing storage or stops at a defined restart point. No primitive may be silently dropped or executed twice.

Maintain submission order for operations affecting the same pixel/sample. Assign each active tile one attachment owner. Independent tiles can retire out of order; within a tile, primitive sequence numbers and completion masks prevent later fragments from overtaking earlier depth/stencil/blend work. Shading may run ahead only when its side effects and query semantics permit it.

Use load-balanced tile assignment, double-buffered attachment transfer and bounded fairness between vertex, fragment and compute work. Avoid a global fragment-retirement queue that makes one slow tile stop every engine. A full export queue must not prevent the scheduler from running the task needed to drain it; reserve progress credits and verify this dependency explicitly.

### On chip shader payload and exports

Introduce a banked payload SRAM addressed by task/payload handles. Raster/interpolation produces fragment inputs there; vertex and fragment tasks consume or export values through bounded CU-local ports. Start with SRAM payloads and explicit exports, then evaluate direct register delivery where register-file ports and occupancy justify it.

Retain a memory-backed path for oversized payloads and controlled spills. Reserve output capacity before dispatch and keep payload/state ownership until completion, cancellation or fault drain. Descriptors specify varying layout, masks, interpolation qualifiers, output locations and scratch bounds. Fragment exports carry tile, primitive order, coverage, discard, optional depth and attachment outputs.

Keep helper-lane execution distinct from coverage and side-effect masks. Derivative/quad exchange must work with partial warps and divergence, without allowing helper lanes to update memory or framebuffer state. Lane counts that cannot execute a full quad need an explicit supported grouping scheme or must reject graphics elaboration.

### Texture clusters

Replace the shared single-word bottleneck with banked cache access and tagged multiword replies. Specify bank mapping for adjacent 2x2 taps; measure conflicts on other patterns. Provide response queues, same-line miss joining, replacement rules and refill arbitration. Filter pipelines must sustain the proposed rate after warm-up; adding request ports alone is insufficient.

Cache identity includes physical line and relevant context/generation attributes. Preserve read/write permission faults and cache invalidation across image rebinding, translation changes and render-to-texture transitions. Retain pending fill ownership until cancellation is drained; do not interpret a failed translation as a successful zero texel.

Generalize descriptors and address generation for multiple images, cube/array/3D resources, explicit and implicit LOD, gradient-based sampling, depth comparison, and all required wrap modes. Remove the power-of-two-only repeat assumption. Use per-level metadata or cached mip offsets instead of requiring a serial level walk on every sample.

Add format-aware fetch/decode before filtering, including signed/unsigned and normalized values, required sRGB behavior, and ETC2/EAC block decode. Cache decoded blocks where useful. Integer fetches bypass inappropriate filtering. Packed FP16 arithmetic and ASTC are later efficiency/product extensions with separate area and compatibility decisions.

### Pixel pipelines and visibility rejection

Replace global address hazard searches with tile-local bank/row ownership and pipelined sample scoreboards. SRAM organization must support the promised read/modify/write rate, including color blending, depth/stencil, coverage and masks. Bank conflicts and repeated writes to one sample must serialize without corrupting order.

Introduce conservative early depth rejection, followed by hierarchical depth summaries where profitable. Track summary validity across clears, depth writes and tile transfers. Distinguish early testing from committing depth/stencil: shader discard, depth override, stencil outcomes, side effects and queries may require deferred updates or a late path. Correctness decides eligibility.

Extend outputs and format packing for MRT, integer targets, required depth formats, sRGB conversion and format-dependent blending. The existing 32-bit packed-color word must not become the universal shader export representation. Pipeline address calculation and arbitration to remove long coordinate-to-ready combinational paths.

### Geometry and general execution

Add GPU index reads, attribute descriptors and format conversion, vertex/instance IDs, divisors, restart and post-transform vertex reuse. Include points, lines, clipping, flat interpolation and provoking-vertex rules in the functional closure matrix; triangles alone do not complete the target. Provide transform-feedback export and query/count hooks with defined ordering.

Reuse existing FP32 FMA, conversion, division and square-root machinery. Measure register-file, issue, scoreboard and writeback limits before adding arithmetic lanes. Define shader-visible numeric behavior, rounding, denormals and precision by operation. Add reciprocal/other approximations only with measured error bounds and valid compiler contracts.

General loops and richer memory access require bounded scratch/resource descriptors, fetch/execute permissions, SIMT reconvergence overflow handling, watchdog interruption and fault attribution. Existing CU branch machinery and the restrictive shader validator are different layers. Increasing code length alone does not resolve safe execution or termination.

### Memory fabric and physical design

Keep a shared L2 and translation fabric, with request classes for instructions, texture fills, geometry/bin data, payload spills and attachment transfer. Partition or reserve credits so bulk binning/store traffic cannot starve fault completion or shader progress. Carry context and generation through requests and responses, with no transaction-ID aliasing during drain/reset.

Size miss windows from `outstanding lines >= sustained miss bytes/cycle * latency cycles / line bytes`. For example, 16 bytes/cycle of aggregate miss demand at 200-cycle latency needs at least 50 outstanding 64-byte lines, before headroom. This is an illustrative latency model, not a recommendation to allocate 50 slots in every sampler.

Use SRAM macros or characterized SRAM models for tile and cache arrays. Include read latency, port count, banking and arbitration in both functional timing and area. Add valid-based clock enables to CU, texture, tile and transfer pipelines; quantify switching on representative workloads. Physical power domains and retention can follow SoC integration requirements.

## Capability closure contract

The [source-linked capability inventory](MOBILE_GPU_CAPABILITY_MATRIX.md) records
the current partial interfaces, unimplemented contracts and required evidence
for H0–H8. It is an initial implementation inventory; its grouped rows still
require individual format, operation and limit cases before capability closure.

The first hardware feature matrix should trace each GLES 3.0 requirement to native hardware, CU-executed support, or an explicit unsupported milestone. Selected minimum limits include four draw buffers, 16 vertex attributes, 2048-square 2D/cube textures, 256 array layers and a 256-texel 3D dimension. Cover uniforms, shader texture access in both stages, varying precision, integer operations, required formats, transform feedback, queries and synchronization in the complete matrix. [GLES 3.0 specification](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf)

Architectural capacity does not require fully parallel hardware for each binding. Descriptor tables and serialized operations may satisfy functional capacity while a smaller number of texture/filter/pixel pipelines provide throughput. Hardware capabilities must describe implemented limits accurately; Mesa exposure and conformance follow as a separate integration effort.

Commercial feature alignment continues after the initial GLES 3.0 hardware milestone:

| Capability stage | Additional hardware work | Architecture consequence |
|---|---|---|
| GLES 3.1 | Shader storage/image descriptors and access, required atomics, memory barriers, indirect work submission and compute resource limits | Extend existing compute/atomic machinery; define visibility across shader, texture, pixel and DMA clients |
| GLES 3.2 | Geometry and tessellation stage execution, primitive amplification, patch storage and synchronization, and the remaining texture/raster/blending requirements | Run programmable stages on the shared CUs; add bounded patch/output queues and a tessellation primitive-generation path before binning |
| Vulkan product target | Selected-version resource, synchronization, robustness and scheduling contract, followed by driver/conformance integration | Reuse tile/CU resources while validating the chosen API requirements independently |

Geometry and tessellation are part of the GLES 3.2 specification, so they belong in the longer commercial-alignment plan even if they follow the first mobile rendering backend. [GLES 3.2 specification](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf) Reserve stage tags, descriptor versioning and resource-table space in H0. Detail the later-stage limit matrix before committing their storage sizes or claiming API support.

## Performance model and acceptance gates

For the current development stage, the user selected synthesis-only PPA
screening on 2026-10-18. Use mapped synthesis timing (including I/O), area and
explicit SRAM assumptions to guide RTL iterations. Do not require new
place-and-route runs for these iterations. Existing routed reports remain
historical evidence for their frozen emissions. Full physical and product
qualification below remains a later milestone gate, not a claim supported by
synthesis alone.

For a selected workload, calculate a lower bound in cycles:

`C >= max(warp instructions / sustained issue rate, FP operations / sustained FP rate, texture samples / sustained sample rate, sample updates / sustained pixel rate, setup work / setup rate, payload bytes / payload service rate, external bytes / sustained bytes per cycle)`

Use measured effective rates for the actual instruction/format mix. Add serial phases, launch overhead, dependency latency and pipeline drain to the scheduling model. The maximum of these terms is a lower bound, not a cycle prediction by itself.

At an assumed 500 MHz, 30 FPS permits about 16.67 million cycles/frame and 60 FPS about 8.33 million. A single RGBA8 1280x720 final store is 3,686,400 bytes/frame, or 221,184,000 bytes/second at 60 FPS. This excludes texture, geometry, bin, attachment-load, spill and display-read traffic. The proposed two-cluster texture cache-hit bandwidth is 16 GB/s at that assumed clock. A 256-bit AXI read channel also has a 16 GB/s ideal beat rate at 500 MHz, but actual DRAM service and utilization must be modeled separately.

Tile storage reduces repeated external attachment traffic by retaining intermediate values on chip. This is a mobile bandwidth/power design principle, not a guaranteed speedup for every scene. [Arm Mobile GPU 101](https://developer.arm.com/cfs-file/__key/communityserver-blogs-components-weblogfiles/00-00-00-20-66/siggraph_5F00_mmg_5F00_2024_5F00_Mobile101.pdf)

| Gate | Acceptance criterion |
|---|---|
| Functional | Reference-image/numeric checks for formats, MSAA, order, hazards, faults and overflow; randomized backpressure and reordered memory responses |
| Texture throughput | Each cluster reaches one bilinear RGBA8 result/cycle on a long conflict-free resident-data stream; separately report trilinear, misses, bank conflicts and dependent samples |
| Pixel throughput | Each engine reaches four RGBA8+D24S8 sample updates/cycle on a long conflict-free resident stream; include enabled blend/depth/stencil cases and report supported rates per mode |
| Attachment locality | An eligible clear/discard-start pass avoids old attachment reads; blending/depth intermediates stay on chip; final stores/resolves match the requested outputs |
| CU scaling | Target at least 3.2x for a specified long shader-bound 1-to-4-CU test with fixed non-CU resources and identical work; attribute the remaining gap with counters |
| Balanced scaling | Separately test 1/2/4 CUs with proportional texture/tile resources; retain a realistic fixed external-bandwidth case and identify its saturation point |
| Physical | Meet setup/hold and I/O constraints at the selected clock, with SRAM assumptions and implementation stage reported; compare area and energy/frame |
| Product workload | Meet the agreed 720p frame budget on each selected workload at a qualified clock, including binning, geometry, rendering and store/resolve |

The current qualified textured scene is 32,341 cycles on one CU and 11,566 on four, or about 2.80x. It is a retained regression workload, not a general theoretical scaling limit. [Qualification handoff](performance/optimization-pause-2026-10-17.json)

Current texture traces found a peak of 10 occupied slots out of 16 and about 0.06 percent cross-CU duplicate candidates in the captured desktop workload. Prioritize service bandwidth and bank conflicts over owner-table growth or duplicate multicast for that workload; these opportunity counters do not establish a causal speedup. [Texture contention measurements](performance/texture-arbiter-contention-2026-10-17.json)

Existing OM screening also failed the 1 GHz timing gate for both baseline and sharing candidates. Use that result to justify pipelined admission/address/hazard control. A design with fewer cycles must also improve or preserve elapsed time at its achievable clock. [Paused physical screening](performance/optimization-pause-2026-10-17.json)

## Implementation milestones

| Milestone | Deliverable | Exit evidence | Primary RTL touchpoints |
|---|---|---|---|
| H0 | Hardware capability/configuration record, descriptor definitions, counters and analytic workload budgets | Units and limits documented; supported elaboration combinations checked; baseline counters explain stage utilization | `GpuConfig`, `GraphicsConfig`, `JobConfig`, `SharedCuGraphicsPort`, performance bundles |
| H1 | One tile engine with pass-lifetime attachments and versioned state | Multiple ordered draws retain attachments; clear/load/store/discard/resolve and dependency boundaries are correct | `TileAttachmentStore`, `RenderPipeline`, render descriptors |
| H2 | Scalable bin pages and two independently progressing tile engines | Overflow/spill/restart, ordering, load balancing, double buffering and backpressure pass; attachment bytes/frame measured | Replacement/evolution of `TileDrawBinner`; new tile scheduler and transfer engine |
| H3 | Banked pixel backend, legal early rejection and pipelined hazards | Pixel throughput gate, adversarial same-sample ordering, early/late equivalence and physical timing | `OutputMerger`, tile SRAM, sample expansion and retirement |
| H4 | Banked texture clusters with general resource descriptors | Cache-hit throughput gate, miss/translation stress, multiple bindings and render-to-texture visibility | `TextureUnit`, `FragmentTextureArbiter`, `BufferedTextureWordPort`, translation routing |
| H5 | On-chip shader payload/export path and GPU geometry fetching | No mandatory per-fragment global staging in the common path; index/instance reuse, fault drain and task progress verified | `KernelFragStage`, `ParallelKernelFragStage`, shared task router, vertex stages and CU interfaces |
| H6 | Required format/primitive/resource coverage and product qualification | Hardware feature matrix closed, targeted API integration tests, 720p suite and physical reports | Format, texture, raster, execution, query and synchronization blocks |
| H7 | GLES 3.1 hardware closure | Storage/image access, atomics, barriers, indirect work and resource-limit matrix verified | CU memory, descriptor tables, command processor and shared cache/translation fabric |
| H8 | GLES 3.2 hardware closure | Geometry/tessellation, bounded amplification, stage ordering and remaining feature matrix verified | Shared task scheduling, patch SRAM, primitive generation and graphics backends |

H3 and H4 depend on the H0 interfaces but can be developed against unit harnesses before H2 completes. H5 payload and geometry changes should be separate reviewable series. Bring up one attachment and existing formats first; add MRT and the wider format contract after ownership and ordering are stable. Apply synthesis/timing gates throughout. Place-and-route and broader physical closure belong to later qualification under the current synthesis-only development policy.

The first implementation slice should be **H0 plus H1**: define the hardware contract, then make several compatible draws render into one resident tile without intermediate attachment writeback. This creates a measurable bandwidth result and a stable base for subsequent parallel engines.

For H1, use overlapping triangles from multiple draws with blending and depth/stencil enabled, all within one tile. After a clear-start pass, require zero external reads of the cleared attachment contents and no intermediate attachment writes before the pass ends. Compare final stored samples against the existing ordered path. Repeat with a load-start pass, partial clear, texture/DMA dependency boundary, cancellation and a store fault. These checks distinguish persistent tile lifetime from merely changing triangle traversal order.

## Verification and delivery discipline

Add counters for tile residency/evictions, attachment bytes by operation, bin allocation/spill, active engines, primitive occupancy, payload traffic, queue stalls, eligible/rejected early tests, texture bank conflicts, useful cache bytes, fill occupancy and per-CU active/helper lanes. Avoid summing overlapping stall counters as if they were exclusive causes.

Use arithmetic/ordering proofs and component tests first, followed by small cycle benchmarks, synthesis, native trace replay and selected full-system smoke tests. Run full desktop validation at integration milestones. Freeze source/configuration and workload hashes for comparisons; report simulator wall time separately from GPU cycles and estimated silicon frame time.

Deliver each milestone as independently reviewable changes with its functional and throughput evidence. Keep the currently qualified desktop path available during bring-up. This proposal does not promote the paused private OM experiments into production RTL.

## Implementation evidence: H0/H1 bring-up

The implementation is experimental and does not close either milestone. The
original requirements, resource targets and H0–H8 exit gates above remain in
force. Evidence and hashes are recorded in
[the H0/H1 measurement record](performance/mobile-h1-tile-lifetime-2026-10-17.json).

Implemented and tested components:

- `MobileGpuConfig` records the proposed execution/resource profile, derives
  capacity and identifier sizes, selects fitting 8x8/16x16 tile classes, and
  rejects unsupported capacity combinations. These values are not advertised
  as implemented device capabilities.
- `MobileGpuBudget` computes conservative frame-cycle lower bounds from
  explicit work counts and sustained rates, preserves rate provenance, and
  distinguishes a fitting lower bound from a frame-rate pass.
- Versioned tile-pass descriptors snapshot attachment bounds, pitches,
  formats, load/store/resolve choices and context/pass/generation identity.
  Staged admission validates address arithmetic without 32-bit wraparound,
  including the exclusive 4 GiB allocation boundary.
- `PersistentTileAttachmentStore` retains one RGBA8-compatible color plane
  and one D24S8 plane across draws. It supports load/clear/discard, final
  store/discard, masked rectangle clears, and byte-wise half-up on-tile
  resolve at 1x/2x/4x. Both tile classes share the same SRAM address layout.
  At the maximum sample count, the current engine contains 8 KiB of attachment
  data, rather than the final proposed 64 KiB engine allocation.
- Attachment data uses synchronous single-port SRAM banks, with behavioral
  and characterized-macro implementations sharing the same read latency.
  Transfers use registered addresses/data; cancellation retains offered or
  outstanding memory ownership until drain, and faults fail the tagged pass.
- The optional `RenderPipeline(persistentAttachments = true)` triangle-record
  path intersects draw scissors with the admitted tile and freezes attachment
  and viewport state. A queued clear lets older OM work drain before taking
  ownership. The existing host/unified-command path keeps its prior behavior.

Component checks cover invalid descriptors, old-response drain, cancellation,
load/store/resolve faults, backpressure, partial clears, every supported sample
capacity, both tile classes and all four 8x8 quadrants. Fixed-function pipeline
checks compare four overlapping draws against the existing ordered path with
blending, depth/stencil, scissoring and an intervening masked clear. Clear-start
passes assert zero old-attachment reads and zero intermediate stores, and final
color/depth/stencil samples match the reference.

These checks establish correctness and attachment-locality progress, not a
whole-system performance pass. The serial reference has one outstanding
external transaction and the SRAM service is serialized. Its small-scene cycle
counts still exceed the ordered reference. The reference
models partial clear as an immediate memory update after draw drain, so its
clear-operation cost is not a fair hardware performance comparison. Use a
dedicated matched workload when qualifying transfer/backend speedups.

Remaining H0/H1 work includes broader resource descriptors and implemented
capability discovery, host command/VM integration, cache-domain publication for
dependent texture/DMA access, programmable shared-CU pass validation, concurrent
attachment-transfer product qualification, post-route setup/hold and
power qualification, and the integration-milestone desktop smoke. H2–H8 remain
required. The standalone synthesis screens in the measurement record are
component estimates and do not qualify the integrated GPU clock or energy/frame.
The staged-admission/SRAM component passes its 1 GHz synthesis setup screen
at TC/SLVT with the stated 25% I/O budget: core slack +26.670 ps and reported
I/O slack +39.924 ps. The completed exploration-mode route has zero DRC and
antenna errors but fails 1 GHz setup/hold: worst setup -530.636 ps, worst hold
-7.686 ps. The reported core setup estimate is 668.381 MHz, not a qualified
operating clock. A timing-repair flow remains separate from this result.

A separate [initial transfer-credit candidate](performance/mobile-h1-transfer-credits-2026-10-17.json)
passes isolated tests for 1/3/8 credits, reordered acknowledgements, stable
backpressure, repeated-address serialization and cancellation/fault drain.
It sustains one request/cycle with a ready port after warm-up and passes its
standalone 1 GHz synthesis setup screen.

The [concurrent transfer integration](performance/mobile-h1-concurrent-transfers-2026-10-17.json)
adds optional bounded write credits while retaining the serial reference.
Private preparation acknowledgements transfer ownership to the write queue;
pass completion waits for every actual external acknowledgement. Late faults,
reordered replies, context reuse and randomized load/clear/discard/resolve
lifetimes are tested. The proposed mobile profile starts with eight credits;
the existing default remains the serial path during physical qualification.

In a matched 4x-MSAA component workload with identical hardware clear work,
936 stores, fixed 32-cycle word response latency and an eight-request external
window, eight credits reduce total cycles from 43,399 to 15,005. Pre-store draw
work stays at 10,757 cycles and final samples match. A 2/4/8/16/32-credit sweep
gives 27,617/19,201/15,005/14,656/14,656 cycles. This is a word-service model,
not an AXI/DRAM or whole-frame performance claim. Both eight- and sixteen-credit
components pass their standalone 1 GHz synthesis setup screens; post-route
timing and area/energy tradeoffs remain under qualification.

The completed eight-credit timing-repair route passes hold (+26.809 ps), DRC,
antenna and placement checks, but still fails 1 GHz setup (-124.621 ps including
I/O; core-only -20.28 ps). Its worst endpoint is the `clearRead` diagnostic
output. A [registered diagnostic candidate](performance/mobile-h1-registered-events-2026-10-17.json)
preserves exact event totals with a one-cycle delay and passes 16 concurrent
component checks. Its synthesis setup screen reports +8.170 ps core and
+256.364 ps I/O. Its completed route still fails 1 GHz setup by 2.460 ps on
one core path; hold is +27.297 ps, I/O setup is +68.84 ps, and DRC/antenna
checks pass. The remaining worst path is an admission partial product, rather
than the diagnostic output. This frozen component predates execution-fault input
changes, so it cannot qualify the latest component or the integrated GPU.

The [execution-failure path](performance/mobile-h1-execution-faults-2026-10-17.json)
now fails the resident pass on accepted fragment traps, unsuccessful task
completions and staging-memory faults. Completion retains context/pass/generation
through producer and memory drain; a later pass starts with a fresh status.
The experimental completion status is three bits, preserving values 0–3 and
adding execution failure as value 4. The existing host `shaderFault` output
also reports failed fragment completions and staging faults.

Thirty-nine checks pass on the recorded source, including single/parallel
shared-CU boundary failure cases, native CU-backed rendering, both tile classes,
randomized attachment lifetimes and the matched transfer benchmark. The shared
boundary harness models the shader ABI protocol; full system CU-pool and VM/cache
integration remain required. Legacy word staging also waits for the first quad's
producer write and correctly sizes indices for non-power-of-two batch capacities;
4/8/12/16/112/128-fragment configurations are checked with faults and reuse.

An [optional command-fetch ownership path](performance/mobile-h1-command-drain-2026-10-17.json)
snapshots the command base/count, discards unaccepted records on cancellation,
and drains offered or issued reads before admitting another binding. Its
registered sequential address advances only on a read handshake. Eleven checks
cover both record formats, queued-record discard, bus backpressure and reuse,
including the three existing decoder regressions. The subsequent command-core
integration below connects this prerequisite to `RenderCore`. At that checkpoint,
unified host submission, VM/allocation ownership and cache-domain publication
remained required; subsequent host checkpoints below address those interfaces.
With the same eight-record FIFO and 1 GHz synthesis constraints, the optional
reader reports +503.856 ps core and +456.246 ps I/O setup slack; the existing
reader reports +285.019 ps and +11.587 ps. These are setup synthesis screens,
not post-route clocks or whole-frame measurements.

The [bounded command-driven tile core](performance/mobile-h1-command-core-2026-10-17.json)
adds an optional `RenderCore(persistentAttachments = true)` job interface. A
`TileCommandJob` snapshots the pass, viewport, draw defaults and command binding;
the exclusive command limit is checked with 33-bit end arithmetic before fetch.
Commands start after pass admission, and final store/resolve waits for command
and render drain. Cancellation, command-memory faults and shader failures stop
prefetch while retaining outstanding read ownership. The controller latches one
tagged completion after all traffic drains and keeps it stable under backpressure.
Command-memory faults use completion status 5; other existing status values stay
unchanged.

Five controller checks and a sixteen-scenario command-driven rendering check
cover invalid/overflowing bounds, the exact 4 GiB boundary, empty jobs, descriptor
mutation, early and in-flight cancellation, command/store faults, final samples,
completion stability, default blending, per-record overrides and healthy reuse.
All resolved draw defaults are materialized from the job snapshot for records
without an override. Seven existing `RenderCore` regressions also pass in the
same thirteen-check cohort, including actual CU-backed shader rendering. At that
checkpoint the new job interface was separate from `RenderHost`/unified AXI
submission. The later host profile below connects it; mixed clear/draw host
commands and broader dependency publication remain required. Controller and
fixed-function core synthesis screens are recorded
separately; neither is a full-GPU physical or 720p frame-rate qualification.
The controller passes its 1 GHz synthesis setup screen (+462.011 ps core,
+413.436 ps I/O). The larger fixed-function core screen is rejected because
the `syn` engine omitted division operators; no renderer frequency follows
from that partial netlist. The subsequent
[Yosys mapping attempt](performance/mobile-h1-core-mapping-failure-2026-10-18.json)
also terminated without a mapped netlist: error 247 after 32:54, with peak memory
near 30 GB. Its exact termination cause is not proven by the retained logs.
It establishes neither renderer timing nor area; the division-heavy geometry
datapaths still need a complete, successfully mapped physical implementation.
The [mapping audit](performance/mobile-h1-mapping-audit-2026-10-18.json) records
the prior screens; the qualifier now rejects omitted operators and missing
mapping evidence, with 24 Python checks covering mapping and timing gates.

The [next admission pipeline](performance/mobile-h1-admission-pipeline-2026-10-18.json)
registers pitch-half products and splits address sums at bit 16 while retaining
their full high carries. It adds three admission cycles. Twenty-seven integration
checks pass, and an expanded 248-descriptor BigInt interval oracle covers upper
coordinates, high pitches, carry boundaries, overflow and exact 4 GiB allocation
ends. The matched eight-credit workload is now 15,008 cycles (serial 43,402),
with unchanged samples and traffic. The component passes its 1 GHz synthesis
setup and mapping screen at +73.113 ps core and +242.262 ps I/O. The completed
component route is recorded below; workload energy and integrated GPU
performance remain unqualified.

The experimental persistent core now has an accepted-response texture-fault
sideband. A failed texture response cancels command prefetch and attachment
export, drains outstanding work, and returns status 6 with the original pass
identity. A fault sideband without an accepted response has no effect. Secondary
shader failures during texture drain cannot hide that attribution; an already
published completion stays stable even if later fault inputs change.

The [shared-system validation](performance/mobile-h1-shared-system-2026-10-18.json)
connects persistent command jobs to actual system CUs, their coherent caches,
shared L2 and existing Sv32 clients, without private graphics CUs. Four
configuration checks cover two CUs in Bare and Sv32 modes, four CUs with
four lanes/two warps, and four CUs with sixteen lanes/eight warps. All assert
multi-CU graphics ownership, overlapping graphics/compute CU occupancy, a
colliding local command ID, exact stored color/depth samples, shader-trap drain
and healthy reuse. The Sv32 cases change roots, ASIDs and physical backing at
drained boundaries, reject an invalid command mapping, and then render again.
An overwrite draw makes stale shader inputs observable instead of hiding them
behind equal-depth rejection. Responses are delayed, reordered and backpressured;
transaction IDs cannot alias live requests.

Eleven distinct checks pass: four system configurations, six controller checks,
and one fixed-function job check containing seventeen scenarios. The latter
includes an accepted texture fault with zero attachment exports. Run the large
configuration with `sbt -J-Xmx8G 'testOnly opengpu.graphics.PersistentTileSharedSystemSpec'`;
it explicitly reports insufficient heap below 4 GiB. These small 8x8,
single-sample workloads use dispatch limits of eight or sixteen fragments and
do not establish full warp occupancy, scaling, bandwidth, FPS or energy. The
harness pins VM state externally until complete drain; production host/AXI
submission, context ownership, dependency publication and the remaining H0–H8
gates are still required.

The [integrated host now enforces a serialized VM boundary](performance/mobile-h1-vm-ownership-2026-10-18.json), advertised as
`GPU_CAP_PROTECTED_VM_STATE` (bit 29). SATP and TLB-flush writes are rejected
with AXI `SLVERR` while staged commands, executing graphics/compute/DMA,
cache invalidations, lower-memory traffic, reset or unconsumed results retain
ownership. Rejection preserves both SATPs and suppresses the flush pulse.
The AXI response retains a rejected beat through a backpressured burst result;
invalid bus addresses cannot drive the register file. This replaces the earlier
software-only quiescence precondition for the integrated host. Explicit persistent
job descriptors and host submission, concurrent context scheduling and scoped
dependency publication remain required.

Twenty-six checks pass on the final VM-guard source, including actual stalled
descriptor reads, translated compute, software-held completions, buffered and
unbuffered command clients, twelve-cycle response backpressure and a rejected
burst whose final beat succeeds. An earlier guard also passes the twenty-three
existing full-system regression checks; that run predates the final stricter
response-presentation check. Kernel-free driver tests pass. The protected MMIO
control block passes its standalone 1 GHz synthesis/mapping screen at +434.332 ps
core and +306.617 ps I/O; this excludes system quiescence aggregation, the AXI
adapter and post-route timing.

The frozen admission-pipeline tile store now
[passes its 1 GHz post-route qualification](performance/mobile-h1-post-route-2026-10-18.json)
at the selected TC/SLVT corner with the recorded 25% I/O timing budget. Global
setup slack is +19.219 ps, hold is +29.397 ps, and I/O setup is +63.10 ps.
Setup/hold violations, route DRC, antenna, placement and flow errors are zero;
mapping is complete. Its emitted RTL is byte-identical to the prior passing
synthesis candidate. The tool reports instance area 12,929.3 and estimated
power 0.161291 W; no workload-specific switching or frame energy was measured.
This qualifies the eight-credit attachment component with synchronous SRAM,
including admission, clear, transfer and resolve logic. Integrated renderer
mapping/timing, broader physical corners and the full performance/product
qualification remain required.

The [bounded persistent host profile](performance/mobile-h1-persistent-host-2026-10-18.json)
now connects memory descriptors to `TileCommandJob` through the unified AXI
host and the protected VM boundary. It is explicitly selected with
`persistentAttachments = true` or `--persistent-tile-jobs`; the default desktop
profile keeps its existing descriptor and capability contract. The new profile
advertises bit 30 and opcode 7, rejects the legacy render opcode, and fetches a
versioned 256-byte descriptor as four uncached cache-line reads. It validates the
full 33-bit allocation before fetch, rejects reserved/truncated encodings, then
retains attachment views and all draw defaults through completion. The descriptor
reuses four reserved DMA transaction IDs before staging starts, adding no
simultaneous ID owner. Reads can overlap and responses may return in any order;
cancellation or a line fault retains every stalled offer and live response until
drain. The C ABI and every field offset are checked.

Unified DMA/resolve/invalidate commands serialize against tile jobs in queue
order; unrelated compute remains dispatchable in the same VM. A small outstanding
memory-owner counter covers queued and executing commands through completion,
including reset. Native 256-bit AXI tests queue tile → strided DMA → tile: the
transfer sees all 512 exported tile bytes before the next descriptor fetch, and
the final blended pass loads and preserves that copy. Descriptor reset/cancellation,
Sv32 rebinding, failed descriptor/command/attachment reads, shader traps, texture
faults, stable results and healthy reuse run through the real hierarchy. The
programmable host tests cover two actual shared CUs with four lanes/two warps
and four shared CUs with sixteen lanes/eight warps. Final-image, traffic, fault,
reset and Sv32 checks run through the complete host path. Smaller whole-warp
fragment batches activate all configured CUs; the counter observes four active
CUs in the four-CU host test. This does not establish full warp occupancy or a
scaling result.

An attachment-load fault exposed a producer-drain deadlock: the store failed
while command prefetch kept a later draw live. Accepted attachment faults now
cancel prefetch, retain attachment-fault status 3 through secondary failures,
and drain before host retirement. Both fixed-function and shared-CU host tests
verify zero attachment exports for the failed job. A two-draw load-start pass
with depth `NEVER` fetches each covered old depth sample once (49 reads) and
exports nothing; a clear-start pass reads no old attachments and exports exactly
512 bytes after both draws.

The initial host implementation passed 52 distinct checks, as recorded above.
The [descriptor-window evidence](performance/mobile-h1-descriptor-window-2026-10-18.json)
records subsequent validation and physical checks. In a controlled reader test
with twelve-cycle responses, a transport limited to one outstanding line takes
54 cycles to offer the job; permitting four takes 18 cycles with identical
decoded state. These are component cycles, not an AXI/frame-rate measurement.
The final cohorts pass 56 distinct checks: 47 unchanged ABI/legacy/control checks,
six descriptor-reader checks and three host configurations. The host checks in
the earlier 50-test regression are repeated by the final nine-test cohort and
counted once.
The updated reader passes its standalone 1 GHz synthesis/mapping screen
(+294.981 ps core, +406.521 ps I/O) and its TC/SLVT 1 GHz routed check with a
25% I/O delay budget: +282.140 ps worst setup, +27.1948 ps worst hold and zero
setup/hold, DRC, antenna, placement or flow errors. Both checks cover only the
descriptor reader; they exclude the host, shared CUs and full renderer. The
reported power has no workload activity and does not qualify energy per frame.
The initial profile rejected command/texture buffering and framebuffer write
coalescing until their fault callbacks carried the required ownership. Command
buffering is now validated below; texture buffering and framebuffer combining
remain gated. Version-2
mixed clear/draw commands are described below. Broader resources, scoped dependency events,
driver adoption, multiple tile engines and the remaining H0–H8 gates remain
required. No frame-rate, energy or commercial-GPU parity follows from this
single-tile correctness workload.

## Implementation evidence: render-to-texture publication

The [native texture-publication record](performance/mobile-h1-texture-publication-2026-10-18.json)
adds three system checks covering fixed-function rendering, two shared CUs at
four lanes/two warps, and four shared CUs at sixteen lanes/eight warps. Each
profile uses the proposed 128 KiB four-bank L2 and repeats its sequence in Bare
and Sv32 modes: thirty jobs across six backend/VM combinations. This validates
that cache capacity in the native functional model, without qualifying its
physical timing or sustained bandwidth.

Two initial sampling jobs prove a physical texture line is resident: the first
causes one lower-memory source-line fetch and the second causes none. A producer
then renders new contents into that image while a dependent sampling job is
queued. The consumer cannot fetch its descriptor before producer result
publication and all 512 attachment bytes have been acknowledged. It refills the
source line once and samples the new contents. A following format-only rebind
decodes the same cached bytes as BGRA without another lower-memory source fetch.
Every job checks all 64 color/depth positions, including untouched clear pixels;
RGBA output packing, shader alpha, fixed-function modulation and ordered depth
rejection use independent expected values.

A controlled negative test temporarily disabled resident-line invalidation at
L2 store acceptance. The consumer then produced the old sampled color instead
of the new one. The production source was restored byte-for-byte before the
final passing run. Existing L2 publication and per-instruction shader tap-cache
invalidation already satisfy this tested path; no RTL change was needed.

This covers one hot-line render-to-texture dependency through the current
serialized host boundary. General bounded image resources, broader formats and
filtering, scoped dependencies, concurrent engines, the proposed texture-cache
clusters and throughput, scalable command storage and the other
H0–H8 exit gates remain required. These checks do not establish frame rate,
full warp occupancy, power or commercial-GPU parity.

## Implementation evidence: ordered clear/draw command streams

The [typed-command record](performance/mobile-h1-typed-commands-2026-10-18.json)
adds descriptor magic `0x47545002` and capability bit 31. Its 164-byte records
carry one type word and forty payload words. Draw payloads retain the full
existing inline layout, including scissors; clear payloads carry a tile-local
rectangle and packed color/depth masks and values. Version 1 and the default
desktop ABI retain their 160-byte draw records. Count/range admission checks
the selected stride with the full 33-bit end, including the exact 4 GiB case.

A shared FIFO stores a type and a union payload rather than duplicating a draw
and clear payload in each entry. Clear admission waits for older accepted
geometry and OM work before closing the draw gate, so an older clipped fan can
still progress. An accepted clear holds younger draws until completion.
Cancellation discards unaccepted tokens, drains offered/issued command reads
and waits for the accepted clear owner to retire. Invalid encodings or
rectangles fail with status 1; a clear-load fault retains attachment status 3.

Native AXI checks cover fixed-function rendering, two shared CUs at four
lanes/two warps and four shared CUs at sixteen lanes/eight warps, with both Bare
and Sv32 addressing. A six-command stream runs a no-op clear, three overlapping
draws and two masked clears. The checks independently compute additive and
destination-preserving blending, depth rejection, stencil comparison/inversion,
and preserved alpha/channels, including uncovered pixels. Successful passes
fetch 984 logical command bytes, read zero old attachment data and export only
512 final attachment bytes. Short bounds, unsupported types, reserved payload,
invalid rectangles, a masked-clear LOAD fault, reset during that read and healthy
reuse also pass. The final regression covers 78 distinct checks across twelve
suites; parser/scissor and expanded native replays count repeated checks once.
The driver/C layout tests verify both record sizes and clear-field offsets.

Fresh synthesis/mapping and routed checks are recorded separately for the
parser, controller and descriptor reader. They cover those components, with
explicit standalone floorplans and I/O budgets, rather than the integrated
renderer. The controller's initial floorplan could not place all exposed I/O;
its enlarged floorplan retains the clock and I/O constraints. No full GPU clock
or energy qualification follows. At TC/SLVT, 1 GHz and a 25% I/O delay budget,
all three routes have zero setup/hold violations, DRC, antenna, placement or
flow errors:

| Block | Worst setup slack (ps) | Worst hold slack (ps) |
|---|---:|---:|
| Typed command parser | +76.5107 | +29.5621 |
| Pass controller, enlarged standalone floorplan | +315.989 | +15.3648 |
| Descriptor reader | +244.566 | +31.3226 |

Typed host clear coverage here uses 1x and
the small test L2; the separate texture-publication checks exercise the proposed
128 KiB cache. Full product combinations, compact command/bin references,
broader resources and the remaining H0–H8 gates
remain required.

## Implementation evidence: buffered command replies and fault status

The [buffered-command record](performance/mobile-h1-buffered-commands-2026-10-18.json)
adds job-local command reuse to the persistent profile with
`--buffer-command-reads`. Fills still bypass L2; one immutable 64-byte line is
retained until replacement or invalidation. Idle/job boundaries, reset entry,
SATP changes and TLB flushes revoke reuse, including pending fills, while accepted
word replies keep their data and fault status through backpressure and drain.

The legacy translated-client fault pulse occurs when the lower line response
is consumed. A buffered word is offered later, so qualifying that earlier pulse
with word-response acceptance loses the fault. The command buffer now retains
fault status alongside its word, clears it on clean cache hits, and exposes it
to the persistent controller at that word's handshake. The legacy line event
is unchanged. A controlled negative test restored the old connection and a
command-faulted job incorrectly exported an attachment; the corrected source
was restored before the final passing runs.

Matched native host checks retain the same input records, bus delays and pixel
oracles in both cache modes. For two version-1 draws, physical command-line
reads fall from 80 to 5; for the six-command version-2 clear/draw stream they
fall from 246 to 16 (15.375 times fewer requests). Logical completion bytes
remain 320/984 respectively, with zero old reads for clear-start attachments
and 512 final export bytes. These source-line counts exclude other GPU traffic.

For the Sv32 healthy recovery job, measured cycles from after submission to
completion IRQ are:

| Backend | Unbuffered cycles | Buffered cycles | Reduction |
|---|---:|---:|---:|
| Fixed-function reference | 6,843 | 4,392 | 35.8% |
| Two shared CUs, 4 lanes/2 warps | 10,210 | 9,202 | 9.9% |
| Four shared CUs, 16 lanes/8 warps | 7,658 | 6,294 | 17.8% |

These timings cover one deterministic small-scene trace with the small test
L2, including the cache/arbitration effects of that trace. They are not isolated
cold starts, sustained scaling measurements or frame-rate estimates. Starting
a new job in the previous cached tail line after CPU replacement tests the
actual job invalidation boundary; merely starting on a different line would
evict the old line and miss that failure. First/last-line faults, reset during a
command fill, clear faults, VM rebinding, precise status and healthy reuse are
also checked. Texture buffering and framebuffer combining remain gated until
their logical fault ownership is implemented. The original H0–H8 resource,
throughput, physical and API-facing requirements remain in force.

The command bridge accepts only one outstanding word/miss. Its translator now
uses one registered, non-flowing pending entry instead of the default sixteen;
other clients retain their existing queue window. For the isolated generic
read/write client with 16 word IDs and 256 line IDs, mapped synthesis area falls
from 8,125.05 to 1,915 tool units (76.4%). Both netlists pass mapping and 1 GHz
synthesis setup at TC/SLVT with 25% I/O delays. This is a client-level comparison;
the integrated read-only command path may constant-fold more logic, and these
numbers do not establish whole-GPU area, power or clock.

At the same 1 GHz clock, corner, I/O budget and 10% standalone floorplan
utilization, routed results are:

| Pending entries | Mapped routed area, tool units | Sequential cells | Setup slack, ps | Hold slack, ps | DRC errors |
|---|---:|---:|---:|---:|---:|
| 16, original baseline | 8,101.44 | 11,904 | -3.26117 | +30.0672 | 0 |
| 1, revised client | 2,049.66 | 2,803 | +145.297 | +15.2867 | 0 |

The original baseline fails setup; the revised client passes the complete
mapping and routed gate, including I/O, setup/hold, placement, DRC and antenna.
Routed instance area decreases 74.7%. All 66 buffered typed-job trace records,
including cycle counts, are identical before and after queue reduction.

The regression covers 106 distinct checks in 17 suites. The 28 checks affected
by queue reduction are replayed on the final source and counted once, replacing
their earlier results. Final emitted RTL is byte-identical to the frozen
one-entry client used for physical qualification. Native typed MSAA/product
combinations and attachment-store AXI B-response faults remain separate work;
these results do not close H0/H1 or any later milestone.

## H1 product-profile MSAA and attachment write faults

[The native functional checkpoint](performance/mobile-h1-product-functional-2026-10-18.json)
records 135 passing checks in 22 suites. This includes the previous 106 checks,
25 raster checks, two product MSAA checks and two attachment-write fault checks;
repeated discovery runs are counted once.

The rasterizer now intersects its walk bounds with the snapshotted runtime
scissor in every configuration. Previously only bin replay enabled this
optimization, so a persistent tile could scan a screen-sized triangle for each
small tile. Odd bounds retain complete 2x2 helper groups; empty or inverted
scissors finish without requiring output readiness. Independent plane/sample
checks cover 84 submissions at a 1280x720 viewport, both scan orders and all
three sample counts. The prior implementation fails the bounded-walk check.

The native product matrix covers 24 jobs: fixed and shared shader backends,
1x/2x/4x MSAA, 8x8/16x16 tiles, Bare/Sv32 translation, and alternating clear/load
starts. Each job uses the proposed 128 KiB four-bank L2 and 256-bit AXI bus.
Shared shading uses all four CUs with 16 lanes and eight warps. Every stored
color/depth/stencil sample and resolved pixel is checked after an ordered typed
draw/partial-clear/blend stream. These are single-tile functional measurements,
not complete 720p frames or an FPS result.

A further 68 jobs inject first/last color, depth and resolve B-response errors,
reset during STORE, and reset combined with an error, followed by healthy
reuse. Completion waits for all accepted transfers to drain; errors report
status 3 and reset discards the old completion. Already accepted writes may
change backing memory. A negative control disconnecting attachment-fault
propagation fails the status assertion as expected.

Qualification remains incomplete. The real 1280x720 quad rasterizer baseline
maps fully but fails the routed 1 GHz setup gate at TC/SLVT with 25% I/O delays
and 25% utilization: setup -966.104 ps, hold +30.8655 ps, DRC errors zero.
The scissor-only revision also fails setup: -965.301 ps, with 1,863 violating
setup endpoints, hold +31.7091 ps and zero DRC errors. The fixed Linux DRM smoke
also failed a compute-fence timeout; this checkpoint does not claim a passing
desktop integration. Arithmetic/timing improvements and that investigation
continue separately. All original H0–H8 requirements remain in force.

## Exact wide arithmetic components

[The isolated arithmetic record](performance/mobile-exact-prefix-arithmetic-2026-10-18.json)
qualifies reusable block-prefix addition and carry-save three-term summation.
They preserve full unsigned results and exact signed modular results without
approximation. Twenty-two numeric checks cover exhaustive small widths,
partial carry groups, long carries and wider boundary/randomized inputs.

Equal registered-endpoint fixtures produce these routed results at 1 GHz,
ASAP7 TC/SLVT, 25% I/O delay and 25% utilization:

| Fixture | Implementation | Area, tool units | Sequential cells | Setup, ps | Hold, ps | DRC errors |
|---|---|---:|---:|---:|---:|---:|
| Two 64-bit terms plus carry-in | Native | 169.245 | 194 | +209.820 | +49.3737 | 0 |
| Two 64-bit terms plus carry-in | Block prefix | 146.325 | 194 | +470.042 | +41.1603 | 0 |
| Three 64-bit terms | Native | 228.337 | 258 | +0.802358 | +44.3334 | 0 |
| Three 64-bit terms | Carry save plus block prefix | 204.091 | 258 | +185.004 | +44.0361 | 0 |

All four fixtures pass mapping and the complete routed gate. These results
justify evaluating the primitives in wider raster datapaths. They do not
qualify an integrated rasterizer, renderer, CU or whole-GPU clock; integration
requires separate numeric, ordering, throughput and physical evidence.

## Native replay of the Linux compute smoke

[The compute replay record](performance/mobile-drm-compute-replay-2026-10-18.json)
checks the actual Linux smoke shader on both four-lane and sixteen-lane CU
profiles in Bare and private Sv32 modes. The 23-word shader defines the
slide-down destination and masks its last active lane, preserving the intended
reduction result without reading an undefined tail beyond VL=4. The native
test requires its instruction words to match the C smoke source exactly.

Both profile checks pass, including every kernarg word, masked/strided/indexed
memory operations, prior FILL event dependency, private code mapping and
physical code-cache publication before restoring the job VM. The aggregate
27-check run also retains the first queued raster revision's 24 product MSAA
jobs and 23 integer ALU checks. Later raster revisions need their own replay.
The first Linux guest run failed during paced flip-event delivery. A fresh
64x64 fixed-function run with the configurable emulator wait budget passes
the complete configured DRM smoke on the frozen scissor-only RTL. Subsequent
arithmetic, queue and resident-path revisions still need their own full-system
qualification; this is not a 720p or whole-GPU clock result.

## Queued quad rasterization and resident traversal

[The raster integration record](performance/mobile-raster-pipeline-2026-10-18.json)
tracks the staged setup, registered sample offsets and two non-flowing quad
queues. Filtering empty quads after the output register removes coverage
arithmetic from its write enable. Draw admission waits for the final queued
output, preserving its snapshotted context under backpressure.

Forty-six component checks pass on both V8 and V9, including 512 consecutive
quad outputs in each sample mode and 144 cycle-for-cycle comparisons between
tiled and row-major traversal on admitted tile scissors. Persistent passes
now use the row-major scanner: admission and draw-scissor intersection keep
each job inside one aligned 8x8/16x16 tile. Attachment storage remains on the
original engine configuration, and the general tiled walker remains available.

V8 passes 24 native product MSAA jobs on fixed and shared-CU backends with
the 128 KiB L2, 256-bit AXI and 1280x720 viewport. These are single-tile jobs.
Universal combinational proofs cover parallel bounds, sample offsets,
bottom-right quad sums, cross-product subtraction and V9 scan-step additions;
their individual source versions and arithmetic scopes are recorded.

At unchanged 1 GHz TC/SLVT synthesis constraints, the resident V7 and V8
screens fail setup at -318.114 ps and -235.250 ps respectively. V9 also fails,
at -258.308 ps: its critical path now runs from the area/cull calculation to
wide engine register enables. All three map completely. These are pre-layout
screens, not routed clock qualifications; V1-V5 use the general tiled profile
and must not be treated as an isolated arithmetic comparison with V6-V9.
Direct current-candidate product replay, Linux integration, post-route
qualification and the original H0-H8 gates remain open.

V10 removes the area/cull decision from scan-data register enables. Only
`active` gates output validity; inactive scan data can load because prior
outputs must drain before a new draw is admitted. Setup gains no extra cycle.
All 47 component checks pass, including degenerate/front/back rejection under
stalls followed by healthy coverage reuse. V10 also passes all 24 native
single-tile product jobs on fixed and shared-CU backends. Synthesis maps
completely but still fails at -222.905 ps; the next
verified path is vertex X through minimum selection and bound padding.

The signed32 prefix-padding experiment (V11) preserves all bound/offset
combinations formally and passes the same 47 component checks, but worsens
synthesis setup to -275.645 ps. That implementation was removed; its frozen
source, proof and negative timing result remain in the measurement record.

V12 retains native bound padding and uses a prefix carry-out with explicit
sign selection for the signed comparisons. All 47 component checks and the
universal 96-input-bit min/max proof pass. Synthesis maps completely and
improves worst setup to -170.732 ps, with I/O setup +229.416 ps. It still
fails 1 GHz. The next netlist-confirmed path is a signed32x32 cross-product
multiply, from `vHold_v1_y[8]` to `crossLeft_2[62]`.

The V8 STORE/reset/fault replay also passes all 68 jobs on fixed and shared
profiles. Those clear-only jobs verify ownership and accepted-write drain;
they do not demonstrate shader activity or qualify later source revisions.

V13 splits each signed32 cross-product multiply into registered half-word
products and an exact assembly stage. It adds one setup cycle and retains
512 consecutive quad outputs per sample mode. All 48 component checks pass,
including 2,673 full signed-product cases; all 24 native product jobs also
pass on the frozen V13 classes. A universal proof covers all 130 assembly
input bits. The separate full-multiply bit-level proof timed out after 30
seconds and remains unproven. Synthesis still fails at -148.267 ps.

V14 replaces wide coefficient negation on top-left flags with sign/zero
predicates, including the fixed-width minimum-negative corner. All 48
component checks and the universal 69-input-bit predicate proof pass. The
overall synthesis result still fails and worsens versus V13; the critical
path now reaches the area-product register from a plane coefficient. This
is a remaining native multiply, not a qualified clock or a throughput win.

## Exact programmable desktop image oracle

[The fragment image replay](performance/mobile-drm-fragment-image-2026-10-18.json)
explains a programmable 64x64 Linux smoke failure on the frozen scissor-only
baseline. The guest rendered 1,009 sampled-color pixels, while its count
formula required 1,008. Independent top-left coverage and nonempty-quad
enumeration predict 1,009: the shader discards warp zero, and edge quads do
not contain equal numbers of covered lanes.

A native replay checks all 4,096 pixels against that independent mask on both
initial rendering and recovery, and requires the fragment instructions to
match the actual 21-word C shader. Both images pass. The C smoke now derives
its exact count from coverage, quad order and batch capacity; its checks for
alternate colors and subsequent render/stencil/teardown results remain.
The corrected full Linux smoke passes on the identical frozen model and
powers off cleanly. Its archived log and exact binary/kernel/driver/test
hashes are recorded, including paced flip intervals of 65,980 and 37,802 us.
This four-lane legacy graphics evidence does not qualify the current raster
revision or the proposed shared-CU mobile product.

## Raster setup and queue retiming follow-up

[The retiming measurements](performance/mobile-raster-retiming-2026-10-18.json)
record V15–V18 independently, with frozen source hashes and direct product
replays. V15 stages the area multiply, V16 replaces pointer-selected quad
queues with registered-head queues, V17 uses prefix additions for adjacent
quad edges, and V18 registers area before the rejection and winding decision.
V15 and V18 each add one setup cycle; V16 and V17 add none. V16 preserves all
24 V15 product-job cycle counts exactly. The queue contract also passes a
24-step bounded proof against Chisel Queue2, randomized comparisons at three
payload widths, and reset/reuse tests. This is not an unbounded queue proof.

All 52 V18 component checks and 24 native product jobs pass. The fixed and
shared-CU product jobs cover one resident tile at a time, including 1x/2x/4x
samples and Bare/Sv32 memory. Synthesis maps completely, but worst setup
still fails at -83.529 ps at the unchanged 1 GHz target. The remaining path
reaches the row-origin register through the origin edge sum. No current
post-route or whole-renderer clock qualification follows from these results.

The fresh V15 fixed-function Linux smoke also passes and powers off cleanly.
Its archived binary and serial log identify the legacy 64x64 compatibility
configuration. That pass does not qualify later raster revisions, the
programmable configuration, or mobile whole-frame throughput. The original
H0–H8 capability, scaling and physical acceptance gates remain open.

V19 compresses the three origin terms into sum/carry bits during the existing
area stage, then resolves the carry during load. The same product registers
are reused at 64 bits, with the original signed59 product truncations explicit.
It adds no setup cycle versus V18. All 52 component checks pass, and a
universal 184-input-bit proof establishes the exact low64 origin result.
Synthesis maps completely and improves worst setup to -62.625 ps, with I/O
setup +642.831 ps. The 1 GHz gate still fails; the next OpenDB-confirmed path
is vertex X through minimum selection and padding into `paddedMinX[31]`.
All 24 V19 native product jobs pass with total cycle counts identical to V18.

V20 captures extrema before applying the original signed32 padding in the
existing cross-product assembly stage. It reuses the same bounds registers
and adds no setup cycle. All 52 component checks pass; the origin arithmetic
proof RTL is byte-identical to V19. Complete-mapping synthesis improves worst
setup to -18.552 ps, with I/O setup +644.731 ps. The remaining path reaches
the area register through the three-term area sum. This still fails 1 GHz;
V20 direct product and full-system qualification remain separate gates.

V21 compresses the assembled area products and the constant edge term in
the existing area stage. Load then resolves the registered sum/carry pair.
It reuses the area registers and adds no setup cycle. All 52 component checks
and a universal 200-input-bit area-sum proof pass. Complete-mapping synthesis
passes at the unchanged 1 GHz TC/SLVT target: core setup +34.557 ps and I/O
setup +634.440 ps, with 25% I/O delays and 25% core utilization. The resident
720p quad rasterizer is the measured component; this does not qualify the
whole renderer. All 24 native product jobs pass on the frozen V21 raster
with the original clipper; V19/V20/V21 total GPU-cycle counts are identical
for these jobs. Routed setup/hold, physical checks and full-system integration
remain open. These replays do not cover the subsequent iterative clipper.

## Exact clipping divider bring-up

[The clipping measurements](performance/mobile-clip-divider-2026-10-18.json)
replace one 192-bit inferred division with a shared iterative signed divider.
The selected signed32 or unsigned32 fields and their differences fit signed34;
the product with the signed34 edge distance fits signed68. The original34-bit
distance subtraction and low32 field results are retained. The divider uses
one quotient bit per cycle, separate sign correction and a held output. Reset
cancels both an active calculation and pending output.

Eleven component checks pass. An independent integer clipping model checks all
792 observable fields from 20 scenes against both the original clipper and the
new implementation, including all planes, extreme attributes, held results,
input mutation while busy and reset/reuse. The fully-inside path remains 28
cycles. Clipped scenes are substantially slower in simulated GPU cycles:
48,002 total versus 1,142 for the original combinational model. No elapsed-time
performance improvement is established.

The isolated68/34 divider maps completely and passes 1 GHz synthesis with
core setup +192.149 ps and I/O setup +484.045 ps. The complete clipper also
maps without omitted operators, but fails timing at -629.846 ps. Its verified
critical path is field selection through numerator production into
`numeratorReg[65]`. Pipelining that calculation and reducing intersection
latency remain required. Other inferred renderer divisions and the original
H0–H8 gates remain open; this component result does not qualify the renderer.

Clipping V2 reduces iteration count to32: a crossing-edge interpolation ratio
has magnitude at most one, and every same-field endpoint difference has
magnitude at most `2^32-1`. The initial high remainder is checked against the
divisor; zero divisors and out-of-capacity requests have explicit flags. Only
the original low32 quotient bits are stored. Zero and unit-divisor shortcuts
and registered17-bit multiply partials reduce the same20-scene total to22,427
cycles, while the fully-inside path remains28 cycles. All16 component checks
and the universal138-input-bit partial assembly proof pass, including4,338
full-product numeric cases. This proof covers assembly, not full multiplication
or the entire sequential clipper.

Complete-mapping clipper synthesis improves to -223.495 ps but still fails
1 GHz. The verified path is now `numeratorReg[1]` to `divider.quotient[8]`
through divider admission. The bounded divider alone also fails its synthesis
gate; the earlier full-width divider pass does not qualify this revision.
Separate native replays now include triangles crossing clip planes with an
independent final sample/resolve oracle. Their results and further admission
retiming remain pending.

Clipping V3 registers sign conversion before admission checks. Its bounded
divider passes complete-mapping 1 GHz synthesis at +155.710 ps core setup and
+481.521 ps I/O setup. All17 component checks pass, now including cancellation
in both full-width and bounded preparation/iteration/output states. The same
20-scene replay takes23,087 cycles and retains the28-cycle fully-inside path.
The full clipper still fails synthesis timing at -123.825 ps; its next verified
path goes from `edgeIndex[2]` to `next_1_x[0]`, through classification-controlled
polygon writes. This is not full-clipper timing closure.

Clipping V4 loads uncounted temporary polygon slots independently of edge
classification, preserving counts/order and all17 checks with no extra cycle.
Synthesis still fails at -101.386 ps. V5 then selects a coordinate before a
shared34-bit prefix distance calculation. All17 checks and the universal147-bit
distance proof pass. Complete-mapping synthesis passes1 GHz at +58.046 ps core
setup and +483.896 ps I/O setup. The20-scene total remains23,087 cycles. V5 full
route and direct native replay are running; synthesis is not routed closure.

The frozen V2 product replay passes48 distinct jobs with exact sample/resolve
images. Its audited logs contain72 executions across6 checks because the
multi-suite selector repeated24 inside-volume jobs. Every one of the24 clipped
jobs saves12,474 GPU cycles versus V1. This is a workload cycle comparison,
not full-frame FPS or an elapsed-time claim for an unqualified renderer.

The V21 resident rasterizer's full route finishes with one setup violation at
-5.14966 ps; hold slack is +29.4184 ps, with zero placement/DRC/antenna violations.
Its remaining path reaches a registered cross-product partial from vertex X.
It still fails the unchanged1 GHz gate, despite the small violation. These
component results do not establish whole-GPU timing, area or energy closure.

## Programmable Linux MSAA timeout investigation

[The V19 MSAA replay](performance/mobile-drm-msaa-replay-2026-10-18.json)
records a full Linux smoke failure waiting for the4x render syncobj after a
300-second guest deadline. Its exact model, initramfs, driver, test and serial
log are retained. This is a failed V19 integration run, separate from the old
scissor-only programmable Linux pass.

A focused native replay passes all three1x/2x/4x cases and their fresh-buffer
recovery renders, checking57,344 stored sample words. It uses the actual guest
shader/vertex instructions, UV gradients and packed mip chain, four general
16-lane/8-warp CUs and explicitly checked private4-lane/2-warp graphics cores.
The4x initial render completes in234,166 GPU cycles. Earlier diagnostic fixture
errors are retained separately and are not classified as RTL failures.

The Linux timeout remains unexplained. These native cases start each sample
mode from reset, so same-DUT mode changes and the full guest submission history
still need investigation. They do not qualify current-main Linux integration
or justify reporting the failed guest as passed.

The V5 clipper route at25% utilization stops before routing:6,937 I/O pins
exceed the floorplan's6,716 available positions. A22%-utilization retry expands
the perimeter while retaining the same RTL,1 GHz clock, TC/SLVT corner and25%
I/O delays. It remains unqualified until the complete routed checks finish.

The latest V5 clipper passes all48 distinct native inside/clipped product jobs
on fixed and shared-CU profiles. The same exact stored-sample/resolve oracle
checks every job; this remains one resident tile per submission.

The68/34/32 divider also passes the complete routed1 GHz gate at +150.318 ps
setup and +36.1654 ps hold, with zero timing/placement/DRC/antenna violations.
This isolated trial uses an explicit30 ps setup-repair margin. The qualifier's
new `--setup-slack-margin-ps` option records that optimization target; it does
not alter the final clock or acceptance checks. All30 Python qualifier checks
pass, including rejection of failed I/O timing with a positive repair margin.

The Linux test now logs elapsed time for the MSAA render and resolve waits.
Its default remains300 seconds; `ARTI_GPU_MSAA_WAIT_MS` can explicitly select
a diagnostic wall-clock budget. Default300-second and explicit900-second
binaries compile with warnings treated as errors, and zero, negative and
out-of-range budgets are rejected. A longer deadline does not qualify GPU
cycles, physical timing or image correctness; the failed Linux run remains
failed until a separately identified replay supplies its own evidence.

A frozen-V19 same-device diagnostic also passes five render jobs:1x,
recovery1x, then4x→1x→2x, with command-ID reuse and36,864 exact sample-word
checks. Transition jobs take234,107/172,229/178,718 GPU cycles. The fixture
flushes translation at mode changes and does not replay prior typed resolves
or the complete Linux history, so the archived Linux timeout is still open.

The22%-utilization V5 clipper route completes physical implementation but
fails the unchanged1 GHz setup gate:−28.6644 ps across56 paths. Hold slack
is+30.1467 ps with zero hold violations, and mapping/placement/DRC/antenna
checks pass. Final OpenDB identifies `edgeIndex[1] → outputCount[3]`; the
`finalCount[0] → io_out_5_y[14]` I/O path also fails at−27.51 ps. Both are
recorded as open timing work, despite the earlier passing synthesis estimate.

Viewport mapping now widens `coordinate + w` before addition. The previous
32-bit sum maps a valid `y = w = 2^30` vertex to−184,320 instead of+184,320
at height720/subpixel8. The new BigInt checks cover296 vertex cases across
four dimension pairs, including positive-w limits through `Int.MaxValue`;
both geometry unit tests pass. Explicit operand widths and a scale shift
reduce emitted viewport division expressions from139 to59 bits and reciprocal
expressions from64 to33 bits, without adding GPU cycles. Mapping/timing and
native integration checks remain pending; see
[the viewport record](performance/mobile-geometry-viewport-2026-10-18.json).

The V6 clipper registers edge distances before classification/count feedback
and registers per-triangle valid controls for the wide output fan. A complete-
inside check bypasses the polygon walk. All17 component checks pass, including
21 golden-model scenes/825 valid fields, zero invalid outputs and reset/reuse.
Fully inside latency falls from28 to1 cycle after admission; the common20
scene aggregate rises from23,087 to23,386 cycles because clipped edges gain
a stage. The distance, product-join and divider proof/fixture RTL is byte-
identical to V5. V6 physical timing and native integration are still pending.

The bounded viewport change passes all48 distinct native product jobs across
fixed/shared execution, inside/clipped triangles,1x/2x/4x samples, both tile
classes and Bare/Sv32. Every GPU cycle count equals the corresponding V5
clipper baseline. This is one resident tile per submission, not full-frame
qualification. The V6 clipper separately passes complete-mapping1 GHz
synthesis with+49.639 ps core setup and+624.781 ps reported I/O setup slack;
its routed/native checks remain open.

The bounded combinational viewport fixture completes Yosys mapping to117,140
cells but fails1 GHz synthesis setup by21,428.42 ps. Operand narrowing fixes
the numeric overflow and reduces widths; it does not solve combinational
division timing. The qualifier now reads the separate Yosys statistics report
alongside both engine logs, with37 checks covering missed reports and residual
internal operators. The original failed qualification is retained; a log-format
reassessment confirms mapping while preserving the timing failure.

`RenderPipeline` now prepares viewport geometry through an exact staged
engine, replacing its nine combinational viewport division operators. Six
coordinate and three reciprocal dividers run in parallel; an exact positive-
power-of-two-w shortcut takes3 cycles after admission, while the measured
general path takes at most64 cycles at subpixel8. Inputs/results and fan
ownership remain stable across stalls. All70 numeric jobs plus4 reset/reuse
renders pass, alongside18 direct pipeline checks covering clipping, depth,
programmable MSAA, persistent attachments and tile boundaries.

The first staged geometry misses synthesis setup by85.512 ps. Initializing
the divider remainder independently of unused shortcut predicates removes
that path without changing cycles or results. The revised engine passes
complete-mapping1 GHz synthesis at+19.1 ps core and+689.109 ps I/O setup.
Eight divider and six clipper checks also pass after this change. Routed
qualification and the staged native product matrix remain pending. The
`mobile-geometry` PPA emitter and
[the staged geometry record](performance/mobile-geometry-preparation-2026-10-18.json)
retain both versions and their limits; full renderer qualification remains open.

The V6 clipper also passes all48 distinct native product jobs. Compared with
V5 at identical viewport/raster/fixture settings, the24 inside-scene jobs save
67–85 GPU cycles each. Each of the24 clipped-scene jobs uses78 extra GPU
cycles. Both gains and regressions remain in the record.

A further divider candidate registers zero predicates in8-bit groups without
adding cycles. Ten arithmetic/reset checks and six clipper checks pass. The
current clipper synthesis margin improves to+125.344 ps; staged viewport
synthesis moves to its sign-correction path and narrowly fails at−1.755 ps.
Both components subsequently pass routed closure with explicit50 ps repair
headroom, as detailed below. Their1 GHz/TC/SLVT/25% I/O gates remain unchanged.

The instrumented frozen-V19 Linux replay has completed its MSAA render and
resolve waits in180,118 ms and71,933 ms respectively. Both are below the
original300-second limit, so this replay does not demonstrate that increasing
the deadline was necessary. The earlier timeout remains unexplained, and
the completed replay is reported below.

Perspective UV interpolation now preserves the existing unsigned32 Q16.16
input contract when multiplying by signed barycentric weights. Previously,
averaging `0x80000000` with two zeros returned `0xd5555556` instead of
`0x2aaaaaaa`. Zero extension before the signed cast fixes this. All260
BigInt cases/520 coordinate checks pass, including helper weights and large
coordinates. The stalled-output stream also checks seven fragments, with a
high unsigned UV selecting a known texel through the sampler. Current
RenderCore emission passes; physical interpolation throughput/timing and
general signed texture-coordinate support remain separate open work. See
[the unsigned UV record](performance/mobile-uv-unsigned-2026-10-18.json).

The frozen staged-geometry V3 native matrix passes all48 distinct jobs with
exact stored-sample and resolve checks. Against the same V6 clipper with
combinational viewport arithmetic, inside jobs add12–14 cycles and
clipped jobs add24–24 cycles. These are staging costs, not a frame-rate
result. This matrix predates the unsigned-UV fix and uses zero UV inputs.

The instrumented archived-V19 Linux replay also finishes its full DRM suite
and powers off successfully. It retains the exact archived QEMU and driver;
its two MSAA waits take180,118 ms and71,933 ms, both below the original
300-second limit. The positive replay and earlier timeout are retained
separately, with the original cause unresolved. This qualifies that frozen
legacy64x64 integration run, not current-main mobile rendering or timing.

A normal sbt build of the checked-in sources passes38 checks across10 suites
covering geometry, divider, clipping, UV and rendering integration. This
removes dependence on private class overlays for that regression. The old
compiled base classpath is copied and hashed for historical replay. See
[the current-source regression](performance/mobile-current-source-regression-2026-10-18.json).

The same frozen V21 raster RTL with30 ps setup-repair headroom still fails
1 GHz: one−3.50975 ps setup path, with+29.066 ps hold and zero physical
violations. Final OpenDB again identifies `vHold_v0_x[2]` to
`crossRightParts_1_midB[29]`, a native partial multiplier. The previous
margin0 result remains−5.14966 ps; neither run is qualified. A separate
carry-save/prefix multiplier experiment produces the V22 results below
without adding raster setup cycles.

Current staged geometry V3 passes the complete routed1 GHz component gate:
+50.3801 ps setup,+21.4209 ps hold, zero timing/placement/DRC/antenna
violations and complete mapping. It uses explicit50 ps repair headroom,
TC/SLVT,25% I/O delays and25% utilization; the final gates are unchanged.
The normal sbt/public `mobile-geometry` emitter produces byte-identical SV
to every routed input file. This qualifies viewport preparation, not the
remaining raster/texture/interpolation paths or whole GPU.

Current clipper V8 also passes its complete routed1 GHz component gate:
+33.5254 ps setup,+27.6347 ps hold, zero timing/placement/DRC/antenna
violations and complete mapping. It uses22% utilization and50 ps repair
headroom with unchanged final setup/hold and I/O gates. The normal public
`mobile-clip` emitter produces byte-identical SV to every routed input.
The [clipper record](performance/mobile-clip-divider-2026-10-18.json)
preserves the earlier failed routes and this component qualification.

An [H4 texture step](performance/mobile-texture-npot-2026-10-18.json)
removes the power-of-two REPEAT restriction for unsigned Q16.16 coordinates
and fixes large-coordinate CLAMP overflow. It uses bounded coordinate
reduction rather than a variable divider, retaining six coordinate stages
and the sampler's existing latency. Thirty-five checks across ten suites
pass, including1206 coordinate requests,512 consecutive metadata results,
NPOT mip/trilinear filtering, full unsigned UVs and actual masked vector
samples with reordered replies. The coordinate component synthesis margin
improves from+76.991 to+122.056 ps and reported design area falls from1497
to1234 µm². A first sampler integration fails at−62.597 ps; capturing its
clamp decision beside the partial products restores the1 GHz synthesis
pass at+21.443 ps, compared with the baseline's+24.246 ps, while reported
area falls from5202 to4885 µm². These are component synthesis results.
Banked texture-cluster throughput, resource descriptors, remaining texture
features and complete renderer physical qualification remain open.

An [H2 page-allocation component](performance/mobile-bin-page-pool-2026-10-18.json)
snapshots a versioned, context/pass/generation-tagged software arena and
allocates disjoint full pages within checked bounds, including an allocation
ending exactly at4 GiB. On exhaustion it returns the caller's unchanged
tile/token and stops admission, allowing the bin writer to retain an exact
restart request. End/cancel drains offered allocation replies before held
completion or rebinding. Ten hardware/configuration checks pass, including
64 consecutive replies at one/cycle, backpressure, malformed/empty arenas,
cancellation, rebinding and reset. The standalone1 GHz synthesis screen
passes at+204.350/+248.006 ps with complete mapping; reported design area
is330 µm². This is allocation control, without page memory transactions.
It is not yet connected to the bin writer or tile scheduler: the existing
eight-record binner limit, primitive/state cache, spill/restart integration
and independent tile engines remain required H2 work.

Raster V22 passes the complete routed1 GHz component gate with30 ps repair
headroom: +16.6418 ps setup,+28.1936 ps hold, zero timing/placement/DRC/antenna
violations and complete mapping. It replaces the two mixed-sign partial
products with carry-save/prefix trees. All23 selected checks and48 distinct
native sample/resolve jobs pass; all48 cycle counts match staged V21.
Sequential cell count stays9760; routed instance area rises from15395.5
to15638.3 µm². The vectorless power estimate also rises, so energy/frame
improvement is not established. The mixed-product SAT run times out and
remains unproven. Current normal public emission is byte-identical to every
routed V22 SV input. See the [raster record](performance/mobile-raster-retiming-2026-10-18.json).
This qualifies the raster component under the recorded constraints; whole
GPU timing, power, product FPS and current V22 Linux integration remain open.

A fresh [Linux regression of committed6aed4cd](performance/mobile-linux-staged-npot-2026-10-18.json)
passes its full configured programmable DRM suite and powers off successfully.
The isolated normal sbt build, model, driver and C test include staged
viewport arithmetic and the unsigned/NPOT texture changes, with baseline V21
raster. MSAA render and resolve waits finish in103,654 and34,150 ms under
the original300-second userspace limit. The shared QEMU source and binary
remain byte-identical; the test uses a separate linked executable. Its
legacy64x64 configuration has four general16-lane/8-warp CUs and private
4-lane/2-warp graphics execution, rather than the mobile shared-CU product.
This is compatibility evidence for that exact revision/profile. Fixed-path
and newer V22 integration, persistent tile locality,720p performance and
full hardware/API closure remain separate gates.

An [H2 memory-backed bin writer](performance/mobile-bin-page-writer-2026-10-18.json)
connects the page allocator to bounded write credits. Each page stores an
explicit ownership/header record and ordered16-byte primitive/state references.
It acknowledges header initialization before linking a page and all reference
words before committing their count. Exhaustion returns the exact uncommitted
reference and a usable prefix; memory faults invalidate the binding and drain
owned writes. A count store acknowledged during cancellation is counted once,
while the final binding remains cancelled. All29 selected checks pass,
including multi-page memory replay, fault/rebind ownership, late cancellation,
the4 GiB boundary and256 references across default4 KiB pages. The complete
routed1 GHz component gate passes at+42.3053 ps setup/+23.4914 ps hold with
zero timing/placement/DRC/antenna violations and complete mapping. This writer
still needs a hardware reader, multi-tile directory, primitive/state cache,
memory/cache-domain integration and scheduling. It does not yet replace the
legacy binner or implement two independently progressing tile engines.

An [H2 memory-backed bin reader and store](performance/mobile-bin-page-reader-store-2026-10-18.json)
now replays that page chain. The reader validates the descriptor and every
page header/forward link before exposing a reference, requires strictly
increasing reference sequences across pages, returns memory faults without
substituting references, and drains offered reads on cancellation. The store
places the writer and reader on one tagged64-byte memory client with a
round-robin request queue and a word-to-line adapter, and exposes only the
committed prefix handed off after the count acknowledgement. All51 selected
checks pass, including5/20/256-reference replay across64/128/4096-byte pages,
reordered acknowledgements at1/3/8 write credits, committed-prefix and
restart recovery, fault invalidation/rebind, and empty/failed bins. Retiming
stages one record while the previous one is admitted, keeping one reference
per cycle on line-resident records, and an elastic allocator counter keeps
the late memory-response path off the page-count carry chain. The complete
routed1 GHz component gate passes at+22.5776 ps setup/+22.8501 ps hold
(synthesis+107.298 ps) with zero timing/placement/DRC/antenna violations and
complete mapping. These are control-component workloads; the store still
needs a multi-tile directory, primitive/state fetch and cache, cache-domain
integration, spill/restart policy and a tile scheduler with two independently
progressing engines. It does not yet replace the legacy binner or qualify
bandwidth or product-frame time.

An [H2 multi-tile bin directory](performance/mobile-bin-tile-directory-2026-10-18.json)
now builds independently appendable, independently sealable tile chains in one
shared page arena. Each tile owns a head/tail page chain, per-page fill,
committed count and strictly increasing reference sequence, while allocation
ordinals interleave globally. A tile can be sealed mid-pass and replayed while
other tiles keep appending; later appends to it return the new `closed` status.
An ordering violation seals only the offending tile, arena exhaustion returns
that tile's exact restart reference with a usable committed prefix, and
cancellation or a memory fault drains irrevocable writes and reports every
unreported tile unusable. All54 selected checks pass across six suites
including8 directory checks, with two-tile interleaved allocations, three
independent chains, mid-pass sealing, empty seals and fault invalidation. The
first synthesis missed1 GHz by0.789 ps through the combinational8-way per-tile
count sum; a running committed-total register moves that sum off the
completion path. The complete routed1 GHz component gate passes at+47.0712 ps
setup/+28.2811 ps hold (synthesis+117.719 ps) with zero
timing/placement/DRC/antenna violations and complete mapping. The directory
still needs replay scheduling with two independently progressing engines,
primitive/state fetch and cache, cache-domain integration, a spill policy and
product-frame measurements. It does not yet replace the legacy binner.

An [H2 bin replay scheduler](performance/mobile-bin-replay-scheduler-2026-10-18.json)
now replays those completed tile chains on two independently progressing
engines. Each engine owns a running job, one buffered job and its own bin
reader, so a completion, backpressure or fault on one engine does not stop the
other from fetching and replaying. Engines take jobs from the pass-level input
in rotating order and share one tagged64-byte read port through a round-robin
arbiter that keeps one outstanding line per engine. References and completions
leave per engine, so per-tile order is preserved even when one output stalls;
a replay error overrides the directory status, while an exhausted build that
replays its committed prefix still reports exhausted. Tiles with no usable
prefix complete without memory traffic. Cancellation stops dispatch, drains
every started reader, reports buffered jobs cancelled and completes the pass
summary once the engines are empty; a pass without cancellation still requires
every per-tile completion to be drained before `end` is accepted. All60
selected checks pass across seven suites including6 scheduler checks, with
concurrent two-engine replay, four-tile buffering balanced2/2 across the
queues, empty and unusable tiles without memory traffic, fault isolation,
cancellation drain and per-engine output backpressure. The scheduler passed
synthesis on its first attempt at+31.578 ps (1032.608 MHz); the complete
routed1 GHz component gate passes at+40.6475 ps setup/+24.0397 ps hold
(1042.37 MHz) with zero timing/placement/DRC/antenna violations and complete
mapping. It still needs directory-to-scheduler wiring, primitive/state handle
fetch and cache, cache-domain publication, a full spill policy, downstream
tile-engine consumption and attachment-bytes/frame and product-frame
measurements. It does not yet replace the legacy binner.

An [H2 primitive and draw-state cache](performance/mobile-bin-state-cache-2026-10-18.json)
now resolves immutable handle records from a bounded arena. A handle is an
arena-relative64-byte line ordinal; an eight-entry direct-mapped cache fetches
each line once per residency and serves sixteen ordered32-bit words, one per
handshake. The arena has a33-bit exclusive limit, so a one-line arena can end
exactly at4 GiB, and an out-of-range handle completes with `invalidHandle` and
zero words without touching memory. Malformed descriptors admit no lookup, and
residency is pass-scoped: `begin` clears every entry and `invalidate` drops
resident lines mid-pass. A faulted line reports `memoryFault` with no
substituted words and stops admission; cancellation retains an offered read
until its owned response, delivers only an already offered word, and completes
the summary after the read, word and lookup completion drain. All69 selected
checks pass across eight suites including9 cache checks, with residency,
conflict eviction, invalid handles/descriptors, the4 GiB line, fault isolation,
both cancellation timings, invalidation and output backpressure. The cache
passed synthesis on its first attempt at+70.488 ps (1075.833 MHz); the complete
routed1 GHz component gate passes at+128.296 ps setup/+30.8866 ps hold
(1411.99 MHz) with zero timing/placement/DRC/antenna violations and complete
mapping. It still needs real handle-to-ordinal translation, wiring to replay
references and directory/scheduler/tile-engine integration, cache-domain
publication for primitive/state stores and product-frame measurements. It is
not yet connected to the legacy binner.

An [H2 bin replay pass](performance/mobile-bin-replay-pass-2026-10-18.json) now
connects the multi-tile directory to the two-engine scheduler over one tagged
64-byte memory client. Directory word traffic uses transaction ids0..7 and the
two replay readers use8..9, so responses demultiplex by id range without a
second port. A round-robin arbiter feeds a two-entry pipelined queue, letting
directory writes and replay reads share the client while each engine keeps one
outstanding line. A sealed tile flows straight from the directory into the
scheduler, so it can replay while other tiles still append, and one `begin`
admits both blocks only when both can accept it. The pass ends the scheduler
after the directory completes, merges statuses with `memoryFault` over
`cancelled` over the first non-success result, and reports directory and replay
counts in one summary. Cancellation drives both blocks and stays latched until
the completion handshake; the scheduler now waits for a `producerDone` signal
before summarizing, so a directory that reports tiles after the engines drain
is still counted in full. The offered per-tile result is also latched, so it no
longer changes when another engine's completion becomes valid under
backpressure. All78 selected checks pass across nine suites including8 pass
checks, with interleaved two-tile replay, replay of a sealed tile during
building, page-link traversal, an empty tile, malformed descriptors, cancel
accounting, replay-fault isolation and held-request/output stability. The first
synthesis missed1 GHz by9.66 ps through the reader's descriptor bound, where a
33-bit limit-minus-base subtract, a page shift and the constant per-page record
multiply resolved in one cycle; registering the arena page count before the
multiply moves synthesis to+32.841 ps (1033.956 MHz). The complete routed1 GHz
component gate passes at+33.8255 ps setup/+22.8047 ps hold (1035.01 MHz) with
199503 instances and8104.64 µm² of standard cells and zero
timing/placement/DRC/antenna/flow violations with complete mapping. It still
needs handle translation and cache insertion between replay references and the
tile engines, cache-domain publication for primitive/state stores, a full spill
and restart policy, downstream tile-engine consumption in `RenderPipeline`,
attachment-bytes/frame measurements and product-frame qualification. It does
not yet replace the legacy binner.

The separate fixed-function Linux run from the same committed6aed4cd source
also completes its full configured DRM suite and powers off. Its original
300-second MSAA waits finish in53,520 ms for render and30,791 ms for resolve.
Both legacy64x64 compatibility profiles are now recorded in the
[Linux evidence](performance/mobile-linux-staged-npot-2026-10-18.json), with
fresh models, drivers and tests and unchanged shared QEMU source/binary.
They use baseline V21 raster and do not qualify the newer V22/H2 integration,
the mobile shared-CU product,720p rendering budgets or full API closure.

## Implementation evidence: resolved primitive and state replay

The [resolved replay record](performance/mobile-bin-resolved-pass-2026-10-18.json)
connects `TileBinReplayPass` to one `PrimitiveStateCache` per engine through
`ResolvedTileBinReplayPass`. A versioned binding snapshots the bin arena,
immutable record arena, context/pass/generation and first handle. Handle
`firstHandle + n` names the 64-byte record at `records.base + 64*n`.
The complete handle interval must fit in 32 bits, both arenas must be disjoint
when nonempty, and record views must have aligned, checked 33-bit bounds and
the same identity as the bin pass. A malformed binding issues no memory traffic.
Both handles are validated before either lookup; an invalid pair is reported
with the distinct four-bit wrapper status `invalidHandle = 8`, preserving
the existing bin statuses, including `invalidPage = 6` and `closed = 7`.

Each engine assembles a complete primitive line and state line before offering
one tagged output. Partial or faulted pairs never become resolved records.
Per-engine result ports preserve ordered record retirement and report tile
completion only after the last offered pair drains. Each engine reserves
`binTileSlots` completion slots, enough for every tile the bounded directory
can report. Empty or cancelled tiles can therefore leave the shared replay
result port even when that engine's consumer stalls. A record read fault
poisons that engine's cache for the pass; the other engine continues.
Cancellation preserves offered reads and complete outputs, discards unfinished
pairs and retains ownership through every tile result and the held pass summary.
Cancelled unstarted tile results never inherit another tile's resolved count
or release its ownership. A regression reproduced that attribution bug before
the tile-identity check was added. Memory faults take priority over cancellation,
and fresh admission invalidates every engine cache before handles can be reused.

The mobile configuration now records `binRecordCacheEntries = 8`, consuming
1 KiB across two engines within the existing 32 KiB primitive/state capacity
budget. This is the implemented replay cache capacity, not qualification of
the full proposed storage. Directory writes, bin replay reads and record fills
use disjoint transaction IDs on one tagged 64-byte memory port. Counters report
resolved pairs, cache lookups/hits/misses and record line reads separately
from the existing bin traffic and replay-reference counts.

All 106 selected checks pass across eleven suites, including eighteen new
resolved-pass checks. Coverage includes one/two/three/four engines, multi-page
replay, blocked record and tile consumers, empty-tile completion isolation,
preflight handle rejection, bin and second-record faults, pass rebinding,
descriptor mutation, early/in-flight cancellation, late fault priority,
stable published results, cancellation attribution while another tile owns
records, and the last physical record and handle ending exactly at 4 GiB.
With mobile 4 KiB pages, eight write credits and eight cache
entries per engine, 259 references retire in 16,742 component cycles:
518 record lookups, 514 hits and four external record-line reads. This is
a deterministic component service model, not AXI/DRAM or frame throughput.

The first synthesis fails 1 GHz by 189.774 ps. OpenDB identifies a path from
engine phase selection through handle subtraction and cache-hit detection to
the cache miss counter. Registering both translated handles before lookup
removes that chain without changing workload cycles. The corrected final
revision passes the standalone 1 GHz synthesis/mapping gate at +10.148 ps
core and +38.251 ps I/O with the recorded TC/SLVT and 25% boundary budget.
The same emitted RTL passes the complete routed 1 GHz component gate with
30 ps repair margin: +13.6724 ps worst setup, +17.5442 ps worst hold and
+20.84 ps I/O setup. Timing, placement, DRC, antenna and flow-error counts
are zero, with complete mapping. Reported standard-cell area is 20,180.8 µm².
The power estimate has no workload switching and does not qualify frame energy;
this result covers the bounded replay/resolution component at the recorded
corner and interface budget, not the integrated GPU or proposed full SRAM capacity.

The record lines remain opaque to replay. Defining their raster/draw-state
layout and connecting the resolved outputs to `RenderPipeline`, cache-domain
publication of immutable records, full spill/restart policy, attachment
bytes/frame, desktop integration and product-frame/whole-GPU qualification
remain required. This component does not close H2 or the original H0–H8 gates.

## Implementation evidence: binned rendering on two tile engines

The [binned rendering record](performance/mobile-bin-render-pipeline-2026-10-18.json)
freezes the source/configuration, emitted RTL and workload evidence. All 145
selected checks pass across eighteen suites, including the existing shared-CU
system profiles through four CUs with sixteen lanes and eight warps. Those
compatibility profiles are distinct from the new two-engine integration tests,
which execute on a two-CU system.

`TileBinRenderPipeline` now connects the resolved bin stream to two independent
persistent `RenderPipeline` instances. This advances the integration work left
open by the preceding frozen replay-component result. Each tile has one
attachment owner. Its completion waits for ordered draw retirement, attachment
store acknowledgements and resolved-input drain. Per-engine completion queues
allow the other engine to progress while one consumer or attachment transfer
is stalled. Ending a pass also seals empty tiles, which still perform their
specified attachment load/clear/store operations.

The versioned draw ABI uses two consecutive 64-byte primitive lines and one
64-byte state line. Words are little-endian and explicitly decoded rather than
depending on Scala Bundle packing. The primitive stores a version/magic,
scissor, twelve full-width clip-coordinate words, three canonical RGBA colors,
three signed depths and six Q16.16 UV words. The state stores a version/magic,
depth/stencil/blend/culling flags, texture LOD state, shader PC, staging offset
and bank stride, and a texture base/extent/format binding. Reserved bits and
words must be zero. Invalid records return a distinct encoding error and
never become substitute triangles. Multi-line handle resolution checks the
whole primitive span before issuing either primitive or state reads.

Whole-grid admission validates every generated tile before any bin or image
traffic. Tiles form a disjoint row-major grid with partial right/bottom edges;
bin and record arenas must be separate from attachment allocations. In
programmable mode, the shader staging arena has disjoint per-engine windows,
also separate from bins, immutable records and images. State-record staging
addresses are offsets in those windows. Admission of a draw checks alignment,
bank stride and the complete staging span before relocating the offset to its
engine's window. This bounds the existing memory-backed shader ABI; it does
not implement the H5 on-chip payload/export path or general resource closure.

The shared-CU variant connects both render engines to the existing system CU
pool and shared memory/translation fabric. It allocates no private graphics
CUs. `SharedShaderTaskRouter` reserves a bounded task window per client,
preserves local tags through reordered completions and traps, and queues
offered launches so downstream backpressure cannot change their global tags.
Within a client, live local tags cannot be reused. Different clients may use
the same local tag. The existing default remains one task per client.
Invalidation requests also capture the requesting owner before being offered
to the pool, retaining that owner through the untagged acknowledgement. A
seeded-arbitration regression reproduces a new client replacing an earlier
stalled invalidate address before this boundary was registered.

Replay memory arbitration now has a two-entry request buffer. A three-engine
rotation test reproduces an offered transaction ID changing from two to zero
when a different reader becomes ready under external backpressure. Freezing
the arbitration result at this boundary fixes the unstable offer; reader
response ownership still drains before cancellation can complete.

The new binned path opts into exact rounded UNORM8 texture modulation for all
four RGBA components. The arithmetic is exhaustively checked against
`(a*b+127)/255` for all 65,536 input pairs. The legacy renderer retains its
existing default arithmetic profile. Native image tests use an independently
ordered renderer as the oracle for depth/stencil, blending, partial tiles,
4x MSAA and resolve. Texture rebinding also checks white identity, colored
texels, nonopaque alpha and texture-fault recovery. Shared-system tests execute
real fragment shader instructions in both bare-address and Sv32 modes, with
fresh address-space generations and physical backings, shader/staging faults,
an invalid bin mapping, and healthy pass reuse after faults.

Arena exhaustion now retains the first rejected reference, including primitive
and state handles, sequence and token, through replay/resolution and the final
tile result. A tile advertises a usable attachment prefix only after all its
draws retire and both attachment planes finish storing successfully. Discarded
attachments, encoding/read/export faults and cancelled tiles cannot advertise
this restart capability. The caller must also check the final pass status:
a later fault in another tile invalidates the pass image.

The native restart test exhausts two 128-byte bin pages after six references
per tile, then resumes both tiles at sequence seven in a new load-start pass.
Twelve prefix draws plus six resumed draws match the complete uninterrupted
nine-draw-per-tile blend/depth/stencil image. Prefix clear/store traffic is
1,024 bytes; the resumed depth/stencil-only path loads and stores 224 useful
bytes each, for 1,248 total stored bytes in 4,312 component cycles. These are
useful word bytes from the deterministic test memory service, not AXI/DRAM
traffic or a frame-rate measurement. Zero-reference exhaustion, discarded
attachments, export faults and healthy reuse are checked separately. This is
an explicit caller-driven restart contract; automatic spill allocation and
host submission of continuations remain open.

Following the synthesis-only screening decision, the current shared-task router
is emitted separately for two render clients, four system CUs and five task
slots per client. It passes the mapped 1 GHz synthesis gate at ASAP7 TC/SLVT
with the same 25% boundary timing budget: +479.851 ps core and +288.512 ps I/O
setup slack, complete mapping, 6,499 standard cells and 921.398 µm² of
synthesized cell area. This is a pre-layout component estimate; it does not
qualify integrated renderer timing or frame power. No new routing run is
required at the current development stage.

Two frozen component emissions pass full routed 1 GHz gates at ASAP7 TC/SLVT,
25% input/output delay and 30 ps setup-repair margin. Both have complete
mapping and zero setup/hold, placement, DRC, antenna or flow errors:

| Component | Worst setup slack | Worst hold slack | Standard-cell area |
|---|---:|---:|---:|
| Draw decoder | +401.850 ps | +38.4511 ps | 1,017.06 µm² |
| Eight-tile grid admission | +123.308 ps | +10.4814 ps | 7,348.47 µm² |

The first decoder floorplan fails because its 3,196 boundary pins exceed the
available perimeter. Reducing target core utilization from 25% to 10% provides
the required perimeter; it does not relax the clock or boundary timing budget.
These are wide standalone component boundaries, not a chip-level pin plan.
The frozen flip-flop-memory two-engine renderer synthesis attempt times out after 900 seconds,
before a qualified mapped netlist or timing/area result. It provides no
integrated-renderer clock claim. Neither component's power estimate includes
representative workload switching, so it does not qualify frame energy.

At that frozen revision the directory is bounded to at most 64 resident entries
(eight in the mobile emission profile), and attachment transfer uses one slot
per engine. A 1280x720 configuration sets coordinate limits; it does not prove
a complete 720p frame. The following checkpoint adds double buffering.
Full-frame directory paging, automatic spill/restart integration, producer
cache publication, host command integration and product-frame/whole-GPU
qualification remain open. H2 and the H0–H8 milestone gates are not closed.

## Implementation evidence: double-buffered attachment ownership

The [double-buffered attachment record](performance/mobile-double-buffered-attachments-2026-10-18.json)
freezes the source, SRAM model, emitted RTL, regression and synthesis inputs
for this checkpoint. All 221 tests in 24 suites pass. The regression includes
the legacy single-slot paths and real shared-system profiles through four
CUs with sixteen lanes and eight warps, alongside the new ownership checks.

`DoubleBufferedTileAttachmentStore` gives each binned render engine two
independent attachment SRAM slots while retaining one raster/shader/OM
producer. A drained tile end releases that producer before its stores or
resolves finish. The next tile can load and update the other slot while the
older slot prepares transfers and awaits acknowledgements. Each slot retains
its own descriptor, clear/dirty state, transfer credits and byte counters.
Ordered completion retains a slot until its result is consumed; a later
tile cannot inherit an older transfer's bytes or fault. The binned frontend
queues two retirement records per engine separately from live draw admission.

The implemented RGBA8 plus D24S8 layout uses 8 KiB per slot at 4x MSAA,
16 KiB per engine, within the proposed 64 KiB-per-engine budget. The wider
MRT/format capacities in that budget remain proposed. Two slots may share
identical image views only for disjoint tile rectangles; otherwise their
allocations must be disjoint. An overlapping younger owner is rejected
without image traffic. Context/translation state must remain pinned, and
the caller must exclude virtual aliases of overlapping physical storage.

Each slot exports a separate tagged line client. Combining those clients
before checking write hazards would hide a younger slot's load behind an
older blocked store. The binned arbiter therefore checks attachment requests
before combining them, reserves export credits per engine and retains those
credits through response consumption. Overlapping write bytes serialize;
disjoint masked word writes can proceed independently. Reads conflict with
the whole older write line, preserving coherent refill semantics.

The mobile execution profile now selects four write-through L2 store entries
per slice. The renderer reserves at most half for bulk exports, with one
reserved export credit per engine in the default two-engine profile. Shader
staging and unrelated compute retain the other headroom. The generic legacy
`GpuConfig` default remains two entries. L2 lower IDs derive from this count:
four load MSHRs, the configured store range, one blocking AMO ID and an
optional uncached-read ID. The old two-entry ID layout remains unchanged.
The store table permits concurrent aligned line writes only when their byte
masks are disjoint; narrow writes remain conservative. Whole-line read
hazards persist through the final upstream store response.

This reservation is required by the shared-system integration: the first
four-tile Sv32 overlap experiment stalls with an unacknowledged older export.
A younger same-line store can otherwise hold the L2 lookup controller and
block the shader traffic needed for the next tile. Slot-level arbitration,
byte hazards and explicit store headroom remove that dependency. The direct
native test and the real shared-CU/Sv32 test both keep older export
acknowledgements withheld while the next slots execute; each final image is
compared with a separate ordered unbinned renderer simulation. That reference
shares arithmetic/backend RTL, so it checks the binned integration and ordering
rather than independently proving the shared arithmetic. Cancellation during overlap,
late export-fault attribution, empty tiles ahead of prefetched draws, exact
MSAA/resolve accounting and reuse after drain are separate checks.

The native four-tile overlap scene executes twelve draws. Both engines accept
younger-slot loads while older-slot store acknowledgements remain withheld;
the exact final image uses 1,800 useful load bytes and 1,800 useful store
bytes in 8,869 deterministic component cycles. The four-tile shared-Sv32
case executes real shader instructions on two shared CUs and repeats with
fresh translation roots and physical backing, totaling 44,707 cycles for
the two healthy submissions. These are test-service measurements, not
representative DRAM throughput or 720p frame times.

Both new standalone components pass the mapped 1 GHz synthesis gate at
ASAP7 TC/SLVT with 25% boundary timing budgets and no omitted operators:

| Component | Core setup slack | I/O setup slack | Standard-cell area | SRAM macro area |
|---|---:|---:|---:|---:|
| Two attachment slots, one engine | +10.920 ps | +119.095 ps | 21,775.00 µm² | 6,643.81 µm² |
| Four-entry L2 store table | +465.547 ps | +350.498 ps | 1,875.99 µm² | 0 µm² |

The attachment-store emission contains sixteen characterized SRAM macros;
its small positive setup margin is a pre-layout result. The L2 table emission
excludes the rest of the cache. Neither result qualifies whole-renderer or
whole-GPU timing. Default-activity synthesis power does not measure frame
energy. Current-stage PPA work uses synthesis only, as requested; no new
placement or routing run is required.

The same checkpoint emits the bounded two-engine renderer with SRAM macros
enabled. Its synthesis run reaches the 900-second limit during logic
optimization, before complete mapping or a timing/area report. The leftover
container is stopped; the frozen run is recorded as unqualified. This attempt
does not establish a whole-renderer clock, area or power result. It includes
fixed-function rendering and eight resident tile entries, with CUs and the
actual shared L2 outside the synthesis top.

The default binned client now has 50 transaction IDs: twelve bin/record IDs,
two groups of eighteen attachment IDs (nine per slot), and one texture ID per
engine. The smaller test profile has twenty IDs. These are ownership
namespaces, not a claim of fifty simultaneous DRAM requests or qualified
memory bandwidth. At this checkpoint H2 still requires full-frame directory paging, automatic
spill/restart and host integration, producer publication, realistic transfer
throughput and the original workload gates. H3–H8 remain required.

## Implementation evidence: paged bin directory and bounded retirement

The [paged-directory record](performance/mobile-paged-bin-directory-2026-10-18.json)
freezes the next optional bin/replay checkpoint. `TileBinDirectory`,
`TileBinReplayPass` and `ResolvedTileBinReplayPass` accept a positive
`pagedCapacity`; `tileSlots` then specifies a bounded direct-mapped metadata
cache with a power-of-two entry count from 1 to 64, rather than the number of
tiles in a pass. The mobile emission uses
eight resident entries and capacity for 16,384 tiles, enough for the 3,600
16x16 or 14,400 8x8 tiles of a 1280x720 grid. The generic maximum is 65,535
tiles. The resident version-1 path remains available.

A version-2 bin descriptor binds `directoryBase` and the exclusive 33-bit
`directoryLimit` alongside its version-1 page pool. Hardware validates
alignment, the full tile-count span, 32-bit address bounds and separation
from the bin arena before memory traffic. Resolved replay also excludes
overlap with immutable primitive/state records. The caller owns and pins
these allocations and their translation context through pass completion;
virtual aliases of physically overlapping allocations remain a caller
precondition.

Hardware initializes every backing entry before admitting appends, including
when a caller reuses the same identity and addresses. Dirty victims write back
as full 64-byte uncached lines, and clean completion flushes the remaining
resident entries. Refill checks owner identity, tile, flags, status, counts
and page bounds before permitting further bin writes. An acknowledged line
must be visible to later reads in this memory domain. These transactions do
not provide CPU-cache coherence or publication of external record producers.

The explicit little-endian 64-byte entry layout is:

| Word | Contents |
|---|---|
| 0 | Entry-format magic `0x47444401`; an initialized empty entry is all zero |
| 1–3 | Context, pass, generation |
| 4 | Tile ID |
| 5 | Head-valid, opened, closed, reported and restart-valid bits 0–4; status bits 8–11; remaining bits zero |
| 6–7 | Head and tail page addresses |
| 8–10 | Committed reference count, tail-page count, last sequence |
| 11–14 | Exact restart primitive, state, sequence and token |
| 15 | Reserved zero |

The directory retains report state independently of faultable backing in a
packed one-bit-per-tile SRAM. Its 16,384-tile emission uses two characterized
256x32 banks, totaling 2 KiB. Updates serialize a two-cycle word read/modify/write;
fault scanning waits for an outstanding update. If backing fails, the bitmap
prevents re-reporting tiles already handed to replay. Unavailable nonresident
chain/count fields are zero and unusable in failed reports; the pass summary
still preserves the exact physically acknowledged commit total. Cancellation
drains offered metadata transactions and completes accepted-read validation,
so a late transport or integrity fault retains priority over cancellation.

Paged passes can also exceed the bounded per-engine result queues. Resolved
replay now reserves result capacity before scheduler assignment and releases
it only when the final resolved tile result is consumed. The scheduler uses
these permits for both normal jobs and cancellation reports. A stalled engine's
empty results therefore cannot fill its queue and pin the shared result port
ahead of work for another engine. Queues remain bounded by resident capacity;
they do not grow to the full frame's tile count.

Verification builds and closes all 3,600 one-reference chains of a 16x16 720p
grid with eight resident entries, checking every page chain against the input
references. Directory traffic is 460,800 read bytes and 691,200 write bytes,
including initialization and dirty flush, in addition to bin-page traffic.
The two-reader path replays 768 ordered references across 192 pages for 96
tiles with four resident entries while one reader is backpressured. Resolved
replay checks 96 tiles, immutable records, metadata fault recovery and reuse
under new generations. A separate blocked-empty-result workload checks that
the other engine continues retiring work.

The real SRAM model also initializes a 14,400-tile 8x8-grid directory, seals
empty tiles at both bank boundaries and the final tile, then cancels and
checks exactly one result for every tile. This verifies directory ownership
and report SRAM coverage; it does not render a 720p image. Other checks cover
one-entry caching, repeated eviction, page links, invalid ordering, exact
exhaustion/restart, duplicate seals, malformed backing, metadata read/write
and final-flush faults, corrupt refills, and cancellation during validation.

The broad regression passes 238 tests in 25 suites. After the final byte-counter
retiming, all 69 affected directory/scheduler/replay checks pass again alongside
18 accumulator and prefix-adder checks, for 87 follow-up checks in seven suites.
The evidence retains the source snapshots and exact changed files for both
runs. Randomized 64-bit accumulation checks include wrap, idle cycles and
clear priority; the accumulator's original observation path remains the default.

Synthesis screening at ASAP7 TC/SLVT, 1 GHz and 25% input/output timing budgets
identifies and removes three successive critical paths:

| Emission | Core setup slack | I/O setup slack |
|---|---:|---:|
| Initial metadata validation | −96.777 ps | −82.424 ps |
| Registered response and validation | −95.015 ps | +123.686 ps |
| Carry-save byte counters | −115.424 ps | +43.997 ps |
| Prefix addition for registered counter observation | +40.938 ps | +127.332 ps |

The final emission passes complete mapping and every reported synthesis setup
path. It contains 41,579 standard cells and two SRAM macros: 5,965.86 µm² of
standard-cell area plus 830.477 µm² of SRAM, totaling 6,796.34 µm². Its
default-activity power estimate is 0.0387182 W, without representative workload
switching. The small positive margin is pre-layout and applies to this directory
component, excluding replay readers, render engines, CUs and shared L2. It does
not qualify their integrated clock, hold timing, frame energy or frame rate.

At this frozen checkpoint the renderer frontend still admits and retains a bounded table of per-tile
attachment descriptors. Connecting this paged backend requires replacing
that table with bounded grid-descriptor generation and carrying the new
metadata client through rendering and host submission. Full-frame image and
transfer-throughput qualification, automatic spill/restart, producer
publication and the remaining H0–H8 gates remain open. Current PPA work uses
synthesis only.

## Implementation evidence: rendering across a paged full-frame grid

The [paged-rendering record](performance/mobile-paged-rendering-2026-10-18.json)
connects the paged directory and immutable record replay to both render
engines. A positive `TileBinRenderPipeline.pagedCapacity` selects version-2
bin backing. The outer render descriptor and immutable record ABI retain
their existing versions. The resident path remains available with capacity
zero; no production host command or capability-discovery ABI is implied.

Paged admission walks and validates the complete grid before allowing any
memory traffic. It retains no per-tile descriptor array: its result contains
only status. Each render engine has one `TileGridDescriptorLookup`, which
snapshots the pass and divides a 16-bit tile ordinal by the column count over
sixteen cycles. It then regenerates the tile origin and partial-edge extents.
This bounded state replaces the renderer's descriptor table. The per-frame
sealed bitmap is also absent in paged mode; the directory's persistent
closed/reported state makes repeated seals idempotent. Result queues,
retirement queues, caches and descriptor lookups remain bounded independently
of frame tile count.

Admission also checks that complete color, depth and optional resolve
allocations are disjoint. Per-tile touched-span checks alone can miss color
data in one tile aliasing depth data in a different tile. Paged metadata must
be separate from bin pages, immutable records, attachments and programmable
shader windows. Full-precision tile checks still reject a last-tile image
overflow before initialization or partial rendering starts. Physical virtual
aliases and translation/allocator ownership remain caller responsibilities.

Descriptor results remain stable under backpressure. Cancellation drains a
lookup even if its attachment owner has not begun, and the final completion
waits for every lookup to become idle. A later binding cannot inherit an old
descriptor. Tests enumerate all 3,600 16x16 and 14,400 8x8 tile descriptors of
a 1280x720 grid, including non-power-of-two column counts, partial edges,
nonzero origins, wide legal coordinates, malformed ordinals and cancellation.

The rendering checks include an 81-tile clear-start frame with 243 ordered
draws. Its complete blend/depth/stencil image matches a separate unbinned
renderer, with 41,472 useful store bytes. A blocked final empty-tile result
stream on one engine does not stop the other engine from retiring more than
the bounded result capacity. Metadata read faults invalidate the image and
drain before healthy same-backing reuse.

A complete sparse 1280x720 frame runs with real attachment and report SRAM
models, four resident directory entries and all 3,600 tiles. Nine draws touch
three tiles spanning the first, middle and last portions of the frame. The
pass uses load-start attachments initialized to known values. All 1,843,200
color/depth words match the unbinned reference, including untouched pixels;
every tile reports once. Useful attachment loads and stores are 5,896 bytes
each in 666,015 deterministic component cycles. Empty untouched tiles report
zero attachment traffic. The reference shares raster/arithmetic/backend RTL,
so this verifies paged integration and ordering rather than independently
proving those shared blocks or API rasterization semantics.

The programmable integration renders a 70-tile grid through two actual shared
CUs, coherent L2 and Sv32 translation. It compares complete images under
fresh roots and physical backing, injects a metadata-read fault, and reuses
the renderer after drain. The three submissions total 34,386 test-service
cycles; peak graphics CU ownership is two and both engine clients execute
shader tasks. Common shader payloads remain memory-backed, so H5 is open.

The default emitted mobile profile has 16,384 directory tiles and eight
resident entries. Its report bitmap occupies 2 KiB of the existing primitive/
state budget alongside the 1 KiB record caches; the proposed 368 KiB aggregate
data-array budget is unchanged. Directory backing needs up to 1 MiB in memory
and is not an on-chip array. Implemented persistent profiles check the
two-slot credit limit, at most four engines and the reserved L2 store headroom.
The complete line namespace is now 51 IDs: thirteen bin/record/metadata IDs,
two eighteen-ID attachment groups, and two texture IDs. The smaller tests
use 21 IDs. The shared-system harness derives translation and host namespaces
from these counts.

All 270 regression checks in 28 suites pass on the frozen source, including
the complete 720p image, the paged shared-CU/Sv32 case, the resident paths,
MSAA/resolve, prefix restart, faults, backpressure and arithmetic checks.

Both new control components pass mapped 1 GHz synthesis setup screening at
ASAP7 TC/SLVT with 25% input/output timing budgets and complete mapping:

| Component | Core setup slack | I/O setup slack | Standard cells | Cell area |
|---|---:|---:|---:|---:|
| One grid descriptor lookup | +189.467 ps | +485.830 ps | 6,406 | 895.941 µm² |
| Paged fixed-function admission | +256.767 ps | +486.672 ps | 37,772 | 5,100.48 µm² |

These emissions have no SRAM or CUs. The admission result has no descriptor
table and this timing scope does not include programmable shader-window
validation. Neither component result establishes integrated renderer/GPU
timing or hold closure. Power estimates use default synthesis activity and
do not measure frame energy.

The complete fixed-function paged renderer is emitted with 34 SRAM macros
(32 attachment banks and two report banks). Its synthesis attempt reaches
the 900-second limit during logic optimization, before a qualified mapped
netlist or timing/area result. The leftover run container is stopped. The
record preserves that failed attempt; it provides no integrated clock or
power claim and does not include CUs or shared L2.

These are sparse-image and controlled-memory correctness workloads. They do
not establish a realistic 720p frame budget, DRAM throughput, whole-GPU clock
or frame energy. Automatic spill/restart, host submission, producer cache
publication, workload throughput, broader formats/resources and H0–H8 closure
remain required. Current PPA screening uses synthesis only.

## Implementation evidence: automatic spill into preallocated bin segments

The [segmented-spill record](performance/mobile-segmented-bin-spill-2026-10-18.json)
adds automatic bin-page fallback through the writer/reader store, resident and
paged directories, and both render engines. The pool descriptor now accepts
version 2 independently of the directory descriptor version. The outer render,
resolved-record and page-header versions remain unchanged. These are typed RTL
interfaces; this checkpoint does not define a production host binary ABI.

A version-2 pool reserves the exclusive virtual envelope `[base,limit)` and
uses its initial prefix `[base,initialLimit)` followed by up to eight
preallocated fallback segments. The initial prefix may be empty. Each used
fallback is nonempty, page aligned, increasing, nonoverlapping and contained
in the envelope. Inactive entries must be zero; the last used end must equal
`limit`. Version 1 retains its contiguous arena and ignores extension fields.
The caller preallocates and pins all used backing before submission. Hardware
crosses a checked boundary with one allocation bubble, without closing a tile
or resubmitting its primitive. This is bounded automatic spillover, not an OS
allocator or automatic continuation after every segment fills.

Pool, reader and whole-pass admission validate the eight segment entries
sequentially before memory traffic. Admission uses the same elaborated page
size as the renderer's children. The whole reserved envelope, including gaps,
remains exclusive from record, attachment, metadata and shader arenas. Gaps
may be unmapped: allocation, replay heads/links and evicted directory head/tail
checks exclude them before any bin access. Replay capacity counts actual
segment pages rather than reserved gaps. Page-header ordinals retain their
address-offset meaning, `(pageAddress-pool.base)/pageBytes`, across gaps.

Next-segment activation can happen without an allocation request. This lets
an empty initial prefix progress through writer/directory binding, whose
admission waits for allocator readiness. Offered allocation replies remain
stable across boundary activation, backpressure and cancellation. Completion
propagates a four-bit `spillTransitions` count through the writer, store,
directory and replay pass. It counts fallback activations, including an
eagerly activated but unused next segment; `allocatedPages` counts actual
successful allocations. After all segments fill, the exact first rejected
reference and committed attachment prefix remain available for caller-driven
restart. Automatic continuation/restart remains open.

The native checks cross all eight fallback boundaries with both empty and
nonempty initial backing, stalled replies and exact exhausted tokens. They
cover malformed counts, alignment, overlap/order, inactive entries, final
bounds, cancellation during validation and the final exclusive 4 GiB page.
Reader tests reject a head or forward link into a reserved gap before
fetching it. Paged-directory tests corrupt an evicted head/tail into a gap
and verify that no subsequent bin write occurs. A twenty-reference writer/
reader chain crosses two fallbacks, verifies address-offset headers and
ordering, faults a later-segment payload and recovers with a fresh generation.

Resident and paged rendering each execute thirteen ordered draws per tile
across two fallbacks, with 26 committed/resolved references, no resubmission,
zero attachment loads and 1,024 useful store bytes. Complete blend/depth/
stencil images match the separate unbinned RTL reference. Every bin request
is checked against actual segments, including reads. A separate exhaustion
case fills an initial page and one fallback, retains both sequence-seven
restart references, and compares the exported/loaded continuation image to
uninterrupted draw order. That continuation is still caller-driven.

The 70-tile programmable case uses an empty initial pool and two one-page
fallbacks through actual shared CUs, coherent L2 and Sv32. Healthy, metadata-
read-fault and healthy-reuse submissions use fresh roots/physical backing;
healthy complete images match and both shader clients execute. Existing
sparse 720p SRAM-image, MSAA/resolve, attachment-slot, L2, arithmetic and
resident compatibility checks remain in the regression. All 279 checks in
28 suites pass on the recorded source. The image oracle shares raster,
arithmetic and backend RTL and does not independently qualify GLES semantics.

The changed components pass mapped synthesis setup screening at 1 GHz,
ASAP7 TC/SLVT and 25% I/O timing budgets:

| Component | Core setup slack | I/O setup slack | Standard cells | Cell area |
|---|---:|---:|---:|---:|
| Segmented page allocator | +405.006 ps | +270.371 ps | 5,620 | 905.549 µm² |
| Segmented writer/reader store | +97.102 ps | +128.447 ps | 44,648 | 6,214.35 µm² |
| Paged fixed-function admission | +242.091 ps | +489.487 ps | 40,864 | 5,566.03 µm² |

All operators map and all reported setup groups, including I/O, pass. These
4096-byte-page component emissions contain no SRAM, render engines, CUs or
shared L2; admission excludes programmable shader-window validation. The
results do not establish current whole-renderer/GPU timing, hold closure or
frame energy. Default-activity power estimates are recorded separately from
representative workload power. No new place-and-route run is performed.

H2 still requires continuation/restart automation after all backing fills,
host submission/capability integration, producer publication and realistic
transfer/workload throughput. H3–H8 and the original H0/H1 acceptance gates
remain required. Current PPA iteration continues with synthesis only.

## Implementation evidence: bounded automatic bin continuation

The [continuation record](performance/mobile-bin-continuation-2026-10-18.json)
adds `ContinuingTileBinRenderPipeline` and its hardware controller. One
version-1 streaming descriptor supplies the original render pass and a 16-bit
restart budget. The producer submits each append once; hardware retains a
rejected reference, drains the exhausted batch, reloads its exported image and
retries that exact reference before admitting another append. Successful
producer appends are never resubmitted. The original explicitly sealable
renderer remains available. The streaming interface owns its tile seals:
input end or exhaustion establishes a batch boundary.

Positive restart budgets require both color and depth/stencil stores, matching
the existing usable-prefix contract. A zero budget allows one batch with the
ordinary attachment modes. All original admission checks remain active,
including the original resolve allocation even while intermediate resolve is
suppressed. A child batch must finish with success/exhaustion, every tile
prefix must be usable and the batch must commit a reference before an internal
retry. Cancellation, faults, unsafe prefixes, zero progress and exhausted
budgets terminate after drain. The limit permits at most 65,535 restarts and
65,536 batches; it does not hide an unbounded no-progress loop.

The controller keeps one accepted producer append and offers exactly one
producer reply after its commit or terminal rejection. Offered replies and
tile events remain stable under backpressure. A submission stays busy through
all internal drain/rebind gaps and until its public completion is consumed.
VM, immutable records, bin/metadata/image allocations and shader staging must
remain pinned throughout that interval. The child summary and all tile events
must drain before the same bin arena is recycled. On a later fault, earlier
exported tiles do not validate the final image.

Tile events carry `(batch,tile)` and describe individual visits. The logical
submission has one final completion, a 17-bit batch count, a 16-bit actual
restart count and 64-bit aggregate work/traffic counters. Nested cache counters
in `last` explicitly describe the last batch. Append `recordAddress` and
`committedCount` are also scoped to their tagged batch: older bin addresses
are recycled and are not durable handles. This is a typed RTL submission
interface, not a production host command ABI.

Version-3 directory bindings retain strict per-tile sequence history across
internal batches. Resident mode keeps validity/sequence registers; paged mode
reuses the existing 64-byte metadata entry. Its magic is `0x47444402`, flag bit
5 records sequence validity, and word 10 holds the last committed sequence.
Every version-3 end closes/reports all logical tiles, including untouched
ones, and flushes their nonzero owner-tagged entries. Resume requires a safely
completed predecessor and the identical identity, complete pool descriptor,
grid count and metadata allocation. Changed or failed ownership rejects the
binding before traffic.

Paged resume reads and validates the complete previous journal before new
appends. It retains sequence validity/value while resetting current chain,
count, closed/reported and restart fields. Empty, unreported, foreign or
malformed history is a fault, so cleared metadata cannot silently erase an
older sequence. An accepted read is still validated through cancellation;
corruption wins over cancellation and prevents reset writes. Existing pointer
membership and reserved-gap checks remain active. Sequence history adds no
full-frame SRAM array or memory transaction ID. Versions 1 and 2 retain their
existing fresh-binding behavior.

After the first batch, attachment load operations select the exported image.
Empty untouched load-start tiles remain lazy. Resolve executes only after the
final input end; intermediate batches export sample storage without resolving.
The controller therefore preserves blend, depth and stencil ordering and the
original clear/load semantics across arena reuse.

The frozen regression includes these complete native images, with two
128-byte bin pages and two render engines:

| Mode | References | Batches / restarts | Useful loads | Useful stores | Useful resolve bytes |
|---|---:|---:|---:|---:|---:|
| Resident, 1x | 38 | 4 / 3 | 672 B | 1,696 B | 0 B |
| Paged, 1x | 38 | 4 / 3 | 672 B | 1,696 B | 0 B |
| Paged, 4x MSAA | 18 | 2 / 1 | 3,068 B | 5,628 B | 512 B |

Store bytes include resolve stores. Each input receives one reply. Every
RGBA8/D24S8 word matches the separate unbinned reference; the MSAA resolve also
matches the rounded average of the four final samples. Another workload leaves
an older tile absent from an internal batch, accepts its next ordered reference
and rejects a duplicate after that boundary. Tests cover zero/one restart
budgets, zero available pages, reload faults, journal eviction/corruption,
cleared/unpublished history, cancellation and stable held outputs. The
unbinned oracle shares raster/arithmetic/backend RTL and does not independently
qualify GLES raster or numeric semantics.

A 70-tile programmable frame runs this controller through actual shared CUs,
coherent L2 and Sv32, using an empty initial pool and two fallback pages.
Healthy submissions execute 18 references over two batches and one restart;
complete images match. The sequence includes a metadata-read fault, a
post-restart attachment reload fault and healthy reuse with fresh roots and
physical backing. Both engine clients execute shaders and peak CU ownership
is two. VM remains fixed across internal batches. The broader regression also
retains the existing complete sparse 720p SRAM image, explicit-prefix restart,
MSAA/resolve, double buffering, shared L2, shader routing and arithmetic checks.
All 299 checks in 29 suites pass on the recorded source.

The changed components pass mapped 1 GHz synthesis setup screening at ASAP7
TC/SLVT with 25% input/output timing budgets:

| Component | Core setup slack | I/O setup slack | Standard cells | Cell area |
|---|---:|---:|---:|---:|
| Continuation controller | +183.783 ps | +373.772 ps | 29,410 | 4,746.69 µm² |
| Paged directory with sequence journal | +20.999 ps | +180.489 ps | 52,177 | 7,395.31 µm² |
| Paged fixed-function admission | +250.258 ps | +478.082 ps | 40,874 | 5,548.27 µm² |

The directory also contains two report SRAM macros totaling 830.477 µm²;
that macro area is separate from the table's standard-cell area. Mapping and
all reported setup groups pass. Controller/admission have no SRAM. None of
these emissions contains render engines, CUs or shared L2. Admission excludes
programmable shader-window validation. Default-activity power is recorded
without interpreting it as workload power or frame energy.

The full fixed-function continuation renderer is emitted with the same 34
SRAM macros: 32 attachment banks and two report banks. Its default profile
retains 16,384 directory tiles, eight resident entries, 4 KiB bin pages and 51
memory IDs. Sequence history reuses metadata backing, so the proposed 368 KiB
data-array budget is unchanged. This structural emission does not establish
whole-renderer/GPU mapped timing or later hold/physical closure. No new
place-and-route run is performed.

Host submission/capability integration, producer cache publication, full-frame
automatic-continuation SRAM workloads, realistic transfer/frame throughput,
independent API oracles and the remaining H0–H8 requirements stay open. Current
PPA screening continues with synthesis only.

## Implementation evidence: attachment line transfers and SRAM continuation

The [attachment-transfer record](performance/mobile-attachment-line-transfer-2026-10-19.json)
adds `TileAttachmentLinePort` to both attachment SRAM slots per engine. It
collects disjoint 32-bit writes into masked 64-byte requests and scatters each
line acknowledgement back into exactly one acknowledgement per accepted
word. The default eight write-word credits use two gather groups, with a
separate reserved read credit. This preserves the existing nine-ID namespace
per slot and the full renderer's 51 reserved memory IDs. Only IDs 0/1 for
write groups and 8 for the read can issue from a default slot.

Full masks, a full unsealed gathering window, group pressure, a same-line
read or 32 idle cycles seal a partial group. Counting unsealed gathering
members prevents old completed replies from prematurely sealing a new group.
Offered line payloads remain immutable under backpressure; unused data words
are zero. Line responses may reorder and are captured independently of held
word replies. A read can complete while all eight write acknowledgements are
withheld. Fault and cancellation paths drop only unoffered work; offered and
issued transactions retain ownership until their replies drain. Slot
completion and rebinding require the line port to drain completely.

Four pass-local read-cache lines per slot cover color/depth at two quad rows.
Faulted fills never become cache hits, writes invalidate matching lines and
every new owner invalidates the cache after drain. Tag lookup and word return
are registered. The contiguous and quad-working-set tests return 64 exact
words using four line fills and 60 hits, including subsequent write
invalidation and owner-rebinding checks.

Useful loads count returned requested words, including cache hits, rather
than complete line fills. Stores and resolves count actual issued byte-mask
bytes, including faulted issued writes; resolve bytes are a subset of store
bytes. Dropped unoffered work adds no external stores. Events widen to eight
bits for the two-slot sum; per-slot totals remain 16 bits and submission
aggregates remain 64 bits. Logical load bytes therefore cannot be inferred
by multiplying physical read requests by four or by 64.

The default data-array budget increases from 368 to 369 KiB for 1 KiB of
read-cache data over two engines and two slots. The additional 512 bytes of
registered gather data, 144 bytes of registered word-reply data, tags, masks
and control remain queue/state costs outside that major-array sum. Existing
attachment capacity is still 16 KiB per engine, compared with the proposed
64 KiB per engine; coalescing does not enlarge the attachment SRAM.

Two controlled comparisons use identical 80-cycle responses and one external
write credit. The word-port stream offers one word every six cycles when
credits permit. The SRAM case clears a 16x16 RGBA8+D24S8 tile, updates 32
selected color/depth words, then checks every exported word:

| Workload | Baseline writes / cycles | Coalesced writes / cycles | Cycle speedup |
|---|---:|---:|---:|
| 64-word port stream | 64 / 5,231 | 8 / 1,066 | 4.91x |
| 512-word real SRAM export | 512 / 42,185 | 64 / 7,245 | 5.82x |

Both SRAM variants export the exact 512-word image, with 2,048 useful store
bytes and zero old attachment reads. These measurements qualify this latency
and credit model, not DRAM bandwidth or frame rate. Buffering also adds
latency: the small low-latency 4x MSAA continuation case increases from the
previous frozen source's 14,895 cycles to 17,021 cycles. The baseline
remains selectable with `coalesceAttachments=false`; the measured export
speedup is not universal. Serial SRAM export preparation remains open.

A new complete sparse 1280x720 test exercises automatic continuation through
actual SRAM functional models. Three active tiles, 0/1799/3599, receive 27
ordered references once from the producer. Three 128-byte bin pages force one
hardware restart, two batches and 7,200 unique `(batch,tile)` visits over
3,600 logical tiles. All 1,843,200 final RGBA8/D24S8 words match uninterrupted
unbinned RTL. Untouched empty visits issue no attachment traffic. The run
records 8,844 useful load bytes, 8,844 useful store bytes,
162 physical attachment read lines, 1,128 masked write
lines and 1,379,856 simulator cycles. This remains a sparse functional
workload; the oracle shares raster/arithmetic/pixel backend implementation
and does not independently qualify GLES semantics or dense frame throughput.

The current-source regression also repeats the actual shared-CU/coherent-L2/
Sv32 70-tile continuation sequence, including metadata and reload faults and
healthy reuse. Both engine clients execute shaders and peak ownership is two
CUs. All 312 checks in 30 suites pass on the frozen 463-file source,
including credit windows 1/2/8/16, sparse/full masks, reordered replies,
read reservation, held outputs, abort/rebind and prior image/order checks.

A subsequent test-composition correction qualifies common response-ready
by the selected transaction range and asserts exactly one downstream response
handshake per accepted common response. An unrelated ready client can no
longer consume a stalled client's response. The new test checks every pairing
of 128 transaction IDs and 64 client-ready masks, including unowned IDs. All
five affected actual shared-CU image/fault/reuse scenarios also pass again.
These six focused checks use a second frozen 464-file snapshot; only the
validation composition and its new test differ. Main RTL, build configuration
and synthesis emissions are unchanged. The two runs cover 313 unique checks
in 31 suites; they are not one 313-check invocation.

Mapped synthesis screening uses 1 GHz, ASAP7 TC/SLVT and 25% I/O timing budgets:

| Component | Core setup slack | I/O setup slack | Standard cells | Cell area |
|---|---:|---:|---:|---:|
| One attachment line port | +364.228 ps | +234.961 ps | 20,552 | 2,904.58 µm² |
| Two-slot coalesced store | +54.424 ps | +231.975 ps | 205,032 | 27,113.9 µm² |
| Same-source baseline store | +23.924 ps | +126.924 ps | 164,355 | 21,614.2 µm² |

Both store variants additionally contain the same 16 attachment SRAM macros,
with 6,643.81 µm² of macro area, separate from standard cells. The line port
has no SRAM macro. All reported setup groups and complete-mapping checks
pass. Default-activity power estimates are 0.00805052 W for the port,
0.319839 W for the coalesced store and 0.291331 W for the baseline; these are
not representative workload power or energy/frame. The cost of buffering is
therefore reported alongside traffic and cycle improvements.

The full fixed-function continuation emission still contains 34 SRAM macros,
32 attachment banks and two report banks. Structural emission and component
synthesis do not establish current-source whole-renderer/GPU mapped timing,
hold closure or frame rate. Current PPA evaluation uses synthesis only.

Host submission/capability discovery, producer cache publication, realistic
transfer/frame budgets, independent API oracles and the remaining H0–H8
requirements remain open. This checkpoint continues functional hardware
development alongside component synthesis screening.

## Implementation evidence: bounded memory submission

The [memory-submission record](performance/mobile-memory-bin-submission-2026-10-19.json)
adds `TileBinJobReader` and `MemoryTileBinRenderPipeline`. A bounded descriptor
request now feeds the continuing renderer without an external RTL producer
supplying every begin/append/end. The [version-1 memory ABI](MOBILE_TILE_BIN_JOB_ABI.md)
defines the complete 512-byte descriptor and 32-byte append-record layouts,
reserved bits, exclusive limits, completion fields and resource record.
It is separate from the existing opcode-7 single-tile ABI.

Eight independently tagged descriptor reads may return out of order. One
64-byte append-line buffer supplies two records, retaining each accepted
reference through its producer reply and any internal bin restart. A zero
record count executes the requested empty-grid attachment operations without
reading the append arena. Full-precision count/size arithmetic and aligned,
padded append allocations prevent four-GiB wrap or overfetch. Descriptor and
append envelopes cannot overlap enabled writable bin, metadata, image,
resolve or programmable-staging allocations. The bin envelope protects its
reserved spill gaps as well as allocated segments. Ordinary whole-grid,
segment, handle, format and shader-window admission remains in the renderer.

Every offered external read stays immutable through cancellation and retains
its ID until response. An offered begin is also retained; cancellation reaches
the renderer after begin acceptance. Accepted append replies, queued reads
and the renderer summary drain before host completion. Faulted data never
becomes a successful input. Frontend errors merge with the final renderer
status, preserving late read-fault priority. Completion distinguishes frontend
status, accepted producer appends, requested input bytes and the nested
renderer summary. A held final completion remains stable and retains busy.
Per-tile diagnostic pulses are drained internally, so software need not read
individual events to permit completion.

A separate eight-word `0x47424301` resource record reports the descriptor
magic/sizes, engine and tile capacities, sample limit, mode flags, page/credit
sizes, restart limit and line-ID count. These are read-only output signals
for host integration; they do not alter the existing capability word or add
a production MMIO register. The default engine reserves nine frontend IDs
and the existing 51 renderer IDs, totaling 60, with a registered output queue
and response selection qualified by the destination transaction range.

Complete 16x8 images exercise one public memory submission with 18 producer
references and one internal restart:

| Mode | Backing | Batches / restarts | Descriptor / append reads | Compared sample words | Test cycles |
|---|---|---:|---:|---:|---:|
| 1x | Empty initial prefix, two fallback segments | 2 / 1 | 512 B / 576 B | 256 | 6,741 |
| 4x MSAA | Two contiguous pages, actual SRAM models | 2 / 1 | 512 B / 576 B | 1,024 | 21,917 |

The segmented case counts four spill transitions and checks that no bin
request enters a reserved gap. All final color/depth/stencil words match a
separate unbinned RTL renderer; the MSAA resolve matches the rounded average
of the four final samples. The oracle shares raster/arithmetic/backend RTL
and does not independently qualify GLES semantics. Additional cases cover
stream-read faults followed by healthy reuse, malformed records, queued-read
cancellation with late faults, empty 8x8-grid clears and a partial 16x16 tile.

Actual shared CUs, coherent L2 and two-level Sv32 run the memory-submitted
path through healthy, descriptor-page-fault, append-page-fault, shader-fault
and healthy-reuse scenarios. Descriptor, append and immutable-record pages
are read-only. Public submissions use fresh roots/physical backing, with VM
fixed throughout each internal continuation. Healthy complete images match;
both engine clients execute shaders and peak ownership is two CUs. The five
scenarios take 49,324 cumulative test cycles on two four-lane/two-warp CUs.
All 56 checks in eight suites pass, including 18 reader cases, six integrated
pipeline cases and retained legacy descriptor, continuation, ABI, routing and
mobile configuration/budget suites. Earlier render-core RTL is unchanged;
the preceding 312-check regression and routing follow-up remain separately
frozen evidence.

Both standalone paged reader variants pass complete-mapping synthesis setup
screening at 1 GHz, ASAP7 TC/SLVT and 25% I/O budgets:

| Reader | Core setup slack | I/O setup slack | Standard cells | Cell area |
|---|---:|---:|---:|---:|
| Fixed-function | +218.187 ps | +334.106 ps | 43,651 | 6,410.16 µm² |
| Programmable staging checks | +167.470 ps | +350.820 ps | 44,204 | 6,464.17 µm² |

Neither reader emission contains SRAM, render engines, CUs or L2. Default-
activity power estimates are 0.0314472 W and 0.0314 W respectively, separate
from representative workload power or frame energy. The functional snapshot
extends two test fixtures after the synthesis snapshot; all main RTL/build
sources match exactly, and emitted RTL/report hashes are checked.

The full fixed-function memory-submission hierarchy is emitted with the same
34 SRAM macros, 32 attachment banks and two report banks. Descriptor words
and the append cache add 576 bytes of fetch registers, plus the decoded
snapshot, queues and control, outside the unchanged 369 KiB major-array
budget. Structural emission and reader synthesis do not establish current
whole-renderer/GPU timing, hold closure or frame throughput. PPA remains
synthesis-only at this development stage.

The next host integration must connect this engine/resource record to unified
commands, protected VM changes and safe command reset. Producer-cache
publication, physical-alias/allocation ownership, CPU-visible coherence,
representative frame budgets and the remaining H0–H8 requirements stay open.

## Implementation evidence: binned-render unified AXI host

The [host-integration record](performance/mobile-binned-host-integration-2026-10-19.json)
connects the memory-submitted renderer to the production `GpuHostSystemAxi`
under the explicit `binnedRendering=Some(MobileGpuConfig(...))` profile. The
normal emitter selects it with `--binned-tile-jobs`; adding `--frag-core
--shared-graphics --parallel-graphics` uses the real shared shader CU pool.
The default is four 16-lane/eight-warp CUs, two tile engines, 1280x720,
128 KiB L2 and a 256-bit AXI data bus. Existing profiles retain their command
contracts and pass regression. See the [host ABI](MOBILE_TILE_BIN_JOB_ABI.md)
for submission fields, statuses and software obligations.

The reserved discovery word at `0x068` reads zero on legacy profiles and
`0x160` on the new profile. Eight read-only GBC1 words occupy `0x160..0x17c`;
writes fail with SLVERR. No old capability bit is repurposed, and the old
single-tile descriptor format is not advertised by this profile. Opcode 7
now selects GBJ1 only when this resource record is present. A command supplies
the descriptor virtual base, exclusive 33-bit limit and exact 512-byte size;
unsupported flags and obsolete descriptor sizes fail before input traffic.
Unified completion retains command ID, opcode, status and success, with
`bytesProcessed` counting requested padded append-line bytes, including
faulted reads, excluding descriptor bytes.

Binned work waits for earlier nonkernel system commands and legacy DMA
acknowledgements. Unified copy/fill/resolve/invalidate commands wait for the
renderer to drain; compute may overlap in the same pinned VM. Legacy register
fill/blit/strided kicks reject while a bin command is pending or active.
Completion arbitration has a registered queue and preserves stalled results.
Legacy DMA/scanout/IRQ control remains available, without adding another
shader core or attachment store. Bin software uses unified STATUS/COMPLETION;
the legacy render STATUS and fragment/OM/texture counters do not measure GBJ1.

The new line fabric has independent translators for legacy DMA, the root
memory renderer and each engine's shader staging. Its default namespace
contains 192 data IDs and four walker IDs in a 256-ID interface. Including
the rest of `GpuSystem` uses 308 IDs and a nine-bit external AXI ID. Requests
are queued; response readiness is qualified by the selected transaction
range. Outstanding ownership includes local translation faults and stalled
upper replies. SATP/flush exclusion extends through command queues, renderer
and translator ownership, queued adapter results and retained software
completions. Translation counters separately cover legacy DMA, root traffic,
stage zero and the remaining stages.

Safe command reset cancels rendering and drains every offered or accepted
memory operation before ACK, including late descriptor and attachment faults.
Old-epoch completions are discarded, queued commands abort, and healthy reuse
remains possible. Software still publishes immutable inputs before submission
and pins the mappings and backing allocations; physical alias ownership and
CPU-visible coherence are not established by virtual envelope checks.

The main regression passes 104 checks in 13 suites. Three end-to-end host
profiles run two submissions each, first bare and then remapped with Sv32:
fixed-function rendering with one system CU, two small shared shader CUs, and
four shared 16-lane/eight-warp CUs. The complete 16x8 or 32x16 sample images match an unbinned RTL reference,
with 18 references, two batches and one internal restart per submission.
Queued copies start after final attachment export, held completions retain
VM ownership, and peak shader ownership reaches two/four CUs. These host tests
use eight L2 sets/two ways; the emitted default has 512 sets/four ways. The
reference shares raster/arithmetic/backend RTL and is not an independent API
oracle.

Seven fresh-VM fault/recovery scenarios check obsolete sizes, unsupported
flags, old unified render opcodes, descriptor/append bus faults, shader traps
and healthy reuse. A real unified compute command also completes. Reset tests
hold a descriptor read or attachment write through late fault replies, abort
queued DMA, reject VM mutation until ACK and verify healthy reuse. A separate
end-to-end check verifies Sv32 legacy fill, blit and strided copy with exact
physical outputs; a queued bin cannot read its descriptor before the earlier
fill's B acknowledgement. Together these are 105 passing checks in 14 suites.
The additional DMA fixture is qualified after the frozen primary run; all
476 primary source/ABI files remain byte-identical, with one test file added.

Both new standalone components pass complete-mapping synthesis setup at
1 GHz, ASAP7 TC/SLVT and 25% I/O budgets:

| Component | Core setup slack | I/O setup slack | Standard cells | Cell area |
|---|---:|---:|---:|---:|
| Command adapter | +603.993 ps | +415.567 ps | 896 | 122.093 µm² |
| Translated memory fabric | +296.897 ps | +304.988 ps | 219,781 | 29,569.5 µm² |

Neither component contains renderer/CU/L2/SRAM arrays. Default-activity power
estimates are 0.000607577 W and 0.146202 W respectively; representative
workload power and energy/frame remain unmeasured. Main RTL/build sources,
emitted RTL and synthesis report hashes are checked against the frozen run.
No new place/route is performed.

The complete default host structurally emits four shared CUs and one memory
bin engine, with 114 32-bit and 1,184 64-bit SRAM macros across the hierarchy.
The bin subtree retains 34 32-bit macros: 32 attachment and two report banks.
Legacy host control adds no private CU or SRAM macro. Whole-host macro counts
include CU register/cache/shared-memory and L2 banks; the proposed 369 KiB
major-array capacity budget is not total implemented chip storage or area.
This structural emission and the component results do not qualify whole-host
mapped timing, hold closure or frame throughput.

Unified host/reset/VM integration is implemented for this opt-in profile.
Producer-cache publication, physical allocation ownership, CPU coherence,
dense/clear-heavy frame budgets, independent numeric/API validation and all
remaining H0–H8 requirements stay open. Functional development continues with
synthesis as the current PPA gate.

## Implementation evidence: GPU input publication and physical invalidate ranges

The [input-publication record](performance/mobile-bin-input-publication-2026-10-19.json)
qualifies an actual GPU producer feeding the binned renderer. The publication
audit found write-through CU data caches and L2, so successful store replies
follow lower-memory acknowledgements. There are no dirty cache lines to
write back. It also found that private instruction caches are not snooped by
data stores. The [ABI publication sequence](MOBILE_TILE_BIN_JOB_ABI.md#publishing-gpu-produced-inputs)
therefore requires successful producer completion before submission, result
retirement, and an idle-only instruction/TLB flush if executable bytes change.
Queue order alone is not a compute-to-bin dependency; GBJ1 still rejects
DMA/event dependency flags and permits unrelated compute to overlap rendering.

The new test warms a rendered image and shader code, then runs a real RVV
producer on the shared CU pool. It copies 1,728 bytes in 108 masked writes:
512-byte descriptor, 640-byte append stream, 384 bytes of primitives, a
64-byte state record and 128 bytes of shader instructions. Two Sv32 virtual
superpages alias the same physical backing. The producer writes through the
upper alias while the renderer reads the lower alias. Original target bytes
are restored before the GPU starts; only actual GPU stores update live inputs.

A 97-cycle B-response model delays the producer stores. While the first B is
held, VM changes reject and 41 additional paused-memory clocks cannot produce
a completion. All 108 acknowledgements precede the successful producer result,
and every generated packet word matches. The dependent 20-reference render
observes changed primitive geometry/depth, the changed shader entry point and
new instructions in a previously fetched code line. All 256 color/depth/stencil
sample words match a separate unbinned renderer. This oracle shares raster,
arithmetic and pixel RTL and does not independently qualify API semantics.
The test uses two four-lane/two-warp CUs, two engines and a reduced eight-set,
two-way L2 with a 256-bit AXI data bus.

The same audit found a concrete publication-range bug. `LineInvalidateEngine`
rounded byte counts with a truncating addition and did not include an unaligned
first-line offset or reject a wrapped physical end. New focused checks
reproduce two failures on the original engine. The corrected engine captures
the request, checks its original exclusive end at full precision, aligns the
first line, then walks the rounded extent. An end of exactly four GiB is legal;
a greater end returns `ADDRESS_OVERFLOW` (1), zero processed bytes and no cache
requests. Empty ranges succeed without traffic. Each issued line retains
ownership through its matching acknowledgement and held completions stay stable.

Boundary checks include a two-byte range spanning adjacent lines, single-line
unaligned ranges, the last physical byte, an end exactly at four GiB, overflow
rejection and healthy reuse. Three near-full-address-space cases inspect the
decoded four-GiB extent and the first three line handshakes; they do not simulate
a full 67,108,864-line sweep. The actual AXI host also rejects a wrapped physical
range and completes an unaligned two-line invalidate with 128 processed bytes,
under an active Sv32 context and without DRAM reads or writes. Opcode 5 remains
a physical L2/private-data-cache operation; it does not clean CPU caches or
invalidate private GPU instruction caches.

The primary regression passes 61 checks in seven suites: invalidate range
validation, the six-case GBJ1 host suite, ABI, command routing, existing host
resolve/VM behavior, translated legacy DMA and fixed/two/four-CU
render-to-texture visibility. A final fixture-only follow-up moves the valid
physical invalidate outside all input/code packets and reruns the producer
case, preventing that operation from masking stale L2 instruction data.
All 476 other source/build/ABI files remain byte-identical to the primary
run and synthesis snapshot; the evidence distinguishes these two runs.

The standalone corrected invalidate engine passes complete-mapping synthesis
setup at 1 GHz, ASAP7 TC/SLVT and 25% I/O budgets: +176.174 ps core and
+177.871 ps I/O slack, 1,038 standard cells and 140.333 µm² cell area. It has
no SRAM. Default-activity power is 0.00119236 W, separate from representative
workload power or frame energy. This is pre-layout setup screening; no new
place/route is performed.

The default 1280x720/four-CU/two-engine/128-KiB-L2 host is regenerated with real
SRAM black boxes and both corrected invalidate engines. Structural SRAM counts
remain 114 32-bit and 1,184 64-bit macros; this is not a whole-host mapped timing
result. All current source/build/ABI hashes and emitted/report hashes are
recorded. Existing renderer/host/cache RTL is unchanged; only the invalidate
engine, its status contract, emitter and validation fixtures change in this
checkpoint. Earlier component synthesis remains separately scoped evidence.

This establishes a sequential GPU-producer publication path. Automatic producer
dependencies, full physical ownership/alias exclusion between independent
writable allocations, CPU/SoC coherence, representative full-frame budgets and
the remaining H0–H8 gates stay open. Legal aliases of one resource in this test
do not establish protection against conflicting allocations.

## Streamed attachment export and complete 720p clear/store budget

The [frozen export-stream checkpoint](performance/mobile-attachment-export-stream-2026-10-19.json)
qualifies `TileAttachmentExport`: one synchronous SRAM-result stage, two
registered export entries and exact private acknowledgement ownership. The
scanner overlaps the next SRAM read or clear preparation with queued export
words and acknowledgement retirement. A stalled SRAM result is captured once;
inactive `SyncReadMem` output need not retain its value. All credit profiles
use the bounded transfer owner. One credit retains one total external owner;
wider windows retain an independent read credit. Fault/cancel stops new
preparation, drops only unoffered work and drains offered/issued ownership
before completion or slot reuse.

Identical vendor-SRAM benchmark sources and exact color/depth/stencil images
measure these export-only cycles; preparation, useful bytes and line counts
are checked separately and remain equal before/after:

| Tile | Before | Current |
|---|---:|---:|
| 1x clear | 2,149 | 1,696 |
| 1x fully dirty | 2,576 | 1,696 |
| 4x clear | 8,549 | 6,736 |
| 4x fully dirty | 10,256 | 6,736 |

The isolated pipeline prepares 160 consecutive words in 163 total cycles.
Its stalled test deliberately poisons inactive SRAM output and takes 1,367
cycles without losing or duplicating words. The 80-cycle-response benchmark
with one external write credit takes 42,185 cycles through the generic adapter
and 6,808 through coalescing, compared with the earlier 7,245 coalesced cycles;
all transfer the same 2,048 useful bytes. A measured one-credit compatibility
cost remains in the fixed-32-cycle word benchmark: 39,942 to 41,005 cycles;
the eight-credit case moves from 11,548 to 11,535. These measurements do not
claim every profile improves.

The primary regression passes 120 checks in 16 suites. A separate
eight-credit run passes 19 checks in two suites, including independent read
progress and reordered late faults. Partial clears, 1x/4x resolve, cancellation,
fault priority, slot overlap, legacy and binned host paths, shared CUs/Sv32 and
render-to-texture visibility remain covered. All 479 source/build/ABI hashes
match the final runs and emitted synthesis inputs. The initial parallel 8 GiB
JVM exhausted heap; its unchanged-source retry serializes suites with a 16 GiB
heap. The failed run is retained separately and is not passing evidence.

The complete 1280x720 clear/store workload visits every one of 3,600 tiles
through two tile engines, two SRAM slots per engine, a paged 16,384-entry
directory, 4 KiB pages and eight word credits. It checks all 1,843,200 final
RGBA8/D24S8 words, zero attachment loads and exactly 7,372,800 useful store
bytes. The bounded line-service model has 80-cycle response latency plus
transaction-ID skew, at most four external writes, reordered eligible replies
and request backpressure every fifth cycle. It completes in
**10,938,945 cycles**, issuing 230,400 masked 64-byte writes
with 14,745,600 bytes of line payload. Useful mask efficiency
is 50%: the eight-word credit window currently
seals half-lines. Useful bytes and line payload are distinct accounting units.

At an assumed 1 GHz, these cycles consume
32.82% of a 30 FPS frame budget and
65.63% of a 60 FPS budget. This
is cycle-budget arithmetic for a clear/store service workload. The bench uses
actual fixed-function tile engines and vendor SRAM models served directly by
a bounded line model; it contains no CUs, shared L2, MMU/AXI controller or
display. It does not establish physical FPS, dense shader performance, DRAM
bandwidth or frame energy.

Both components pass complete-mapping synthesis setup at 1 GHz, ASAP7 TC/SLVT
and 25% I/O budgets. The export pipeline has +680.204 ps core/+469.163 ps I/O
slack, 1,114 standard cells and 150.582 µm² cell area. The two-slot store has
+90.177/+214.451 ps, 205,292 standard cells and 27,265.3 µm² standard-cell area,
plus the unchanged 16 SRAM macros occupying 6,643.81 µm². Its historical
mapped standard-cell area rises about 0.56%. Default-activity synthesis power
is separately recorded and does not measure workload power. No new place/route
is performed; hold and whole-GPU mapped timing remain unqualified.

The default host is regenerated with four shared CUs and four export pipelines.
Transaction namespaces and SRAM macro counts remain unchanged: 114 32-bit and
1,184 64-bit macros across the complete host, 34 32-bit macros in the bin subtree
and 16 per two-slot attachment store. Structural emission does not establish
whole-host mapping or timing. The proposed 369 KiB major-array capacity budget
is unchanged; pipeline registers and FIFO bookkeeping are additional storage.

Dense/texture/shader/overdraw/MSAA full-frame service budgets, representative
activity power, dependency/alias/coherence closure and the original H0–H8 gates
remain open. One prepared export word/cycle does not establish H3's four
complete RGBA8+D24S8 sample updates/cycle. Functional hardware development
continues with synthesis as the current PPA screen.

## Buffered attachment capture, ordered issue and complete-frame transfer

The [buffered-write checkpoint](performance/mobile-buffered-attachment-writes-2026-10-19.json)
qualifies private capture acknowledgements while the existing two 64-byte gather
buffers per slot retain actual external write ownership. The enclosing owner
waits for both private and external drain before completion or reuse. Late
write faults retain their original slot and override cancellation; an older
response cannot modify a recycled private reply. A fully drained held result
remains stable.

A FIFO of existing write-slot handles preserves enqueue order. Recycled low
slots cannot repeatedly overtake older words and split a contiguous line.
Acknowledgements may reorder. Abort drops unoffered data, drains discarded
handles and retains every offered/issued owner. Repeated unoffered captures
keep the final word value and its final resolve classification, preserving
neighboring words' classifications. The configured eight-word credit window
and transaction namespace remain unchanged; they do not describe simultaneous
DRAM request count. The added FIFO holds 24 payload bits per owner plus control.
No new major data array or characterized SRAM macro is introduced.

The final source passes 55 focused checks, 136 checks in the 16-suite integration
regression, and 21 checks in the separate eight-credit variant. These are
executions under their stated configurations. Coverage includes held replies,
read-after-write visibility, continuous credit reuse, reordered late faults,
partial clears, resolve, overlapping slots, shared CUs/Sv32, GPU publication,
legacy and binned host paths, safe reset and render-to-texture visibility.

Identical 512-word vendor-SRAM images transfer 2,048 useful bytes. The generic,
strict-coalesced and buffered paths issue 512, 64 and 32 lines respectively.
At 80-cycle latency and one external write credit they take 42,185, 6,808 and
2,735 cycles. At the lower-latency setting, export-only cycles improve as follows;
preparation and every final color/depth/stencil word remain independently checked:

| Tile | Previous | Buffered |
|---|---:|---:|
| 1x clear | 1,696 | 1,042 |
| 1x fully dirty | 1,696 | 1,042 |
| 4x clear | 6,736 | 4,114 |
| 4x fully dirty | 6,736 | 4,115 |

The complete 1280x720 clear/store frame checks all 3,600 tiles and 1,843,200 final
RGBA8/D24S8 words with zero attachment loads and 7,372,800 useful store bytes.
It takes **5,937,039 cycles**, compared with 10,938,945 in the preceding frozen
checkpoint: a 45.73% cycle reduction. The 115,200 full-line attachment writes
carry 7,372,800 bytes of payload, giving 100% useful mask efficiency for this
full-tile workload; partial rectangles retain masked writes. The service model
still has 80-cycle latency plus ID skew, four global write credits, reordered
eligible responses and request backpressure every fifth cycle.

The test-only native idle driver first reproduced the old hardware's complete
frame, all words and exact 10,938,945-cycle/230,400-line result. A smaller test
also matched all 1,072 timestamped request/response payloads, complete memory,
tile records and peak activity. Thus the driver change is separately qualified
before comparing hardware. It reduces verification wall time and is not itself
a GPU performance result.

At an assumed 1 GHz, the new frame consumes 17.81% of a 30 FPS budget or 35.62%
of a 60 FPS budget. This bench uses actual fixed-function tile engines and vendor
SRAM models directly served by the bounded line model. It contains no CUs, shared
L2, MMU/AXI controller or display. It does not establish physical FPS, dense-shader
performance, DRAM bandwidth or frame energy.

The changed two-slot store passes complete-mapping synthesis setup at 1 GHz,
ASAP7 TC/SLVT and 25% I/O budgets: +109.271 ps core and +227.648 ps I/O slack,
206,230 standard cells and 27,253.7 µm² standard-cell area, plus the unchanged
16 SRAM macros occupying 6,643.81 µm². Default-activity power is 0.332160 W;
representative workload power remains unqualified. No new place/route is run.
The regenerated default host retains four CUs, one bin-job root, four export
pipelines, 114 32-bit and 1,184 64-bit SRAM macros. The bin subtree has 34 32-bit
macros. Structural emission and component synthesis do not establish whole-host
mapping or timing. The proposed 369 KiB major-array capacity budget is unchanged.

The evidence retains the initial 33-line gather failure, the enqueue-order fix,
and the final-resolve-bit reproducer (four bytes counted instead of zero). The
intermediate broad run was intentionally stopped for that correctness fix;
only the final source's complete results qualify this checkpoint. All 479
source/build/ABI hashes, the recoverable source archive, emissions and reports
are recorded. Dense/shared-memory full-frame budgets, H3's complete pixel
backend, H4–H8, dependency/alias/coherence closure and whole-GPU timing remain open.

## H3 banked sample storage service

The [banked-storage checkpoint](performance/mobile-banked-sample-sram-2026-10-19.json)
adds `BankedTileSampleSram`, with four tagged read ports and four write ports over
eight paired RGBA8/D24S8 single-port banks. Each pair arbitrates one operation
per cycle. Independent full-word enables preserve either attachment plane.
Sample mode is captured at begin; local replies and the producer must be
quiescent before rebinding. The logical maximum is 1,024 samples, or 8 KiB.
The sixteen characterized 256x32 macros occupy 16 KiB because each bank uses
128 logical rows. This fits within the proposed 32 KiB single-slot budget.

A read port owns one synchronous SRAM-result stage and two registered response
entries. Stalled SRAM data is captured exactly once, before another port can
reuse the bank or an inactive behavioral read output changes. Replies retain
index/ticket ownership and per-port order under backpressure. The row-major
quad mapping is `bank={x1,y0 XOR s1,x0 XOR s0}` and
`row={y3:y1,x3:x2,s1:s0}`; paired adjacent quads use disjoint bank sets.

Six tests pass in the main build, covering all logical words at 1x/2x/4x,
independent plane masks, mixed reads/writes, changing input sample mode,
blocked responses during other-port bank reuse, tagged ownership and conflict
fairness. Behavioral and vendor SRAM paths both pass. A 64-cycle disjoint-bank
stream accepts four raw tuple reads and four raw tuple writes per cycle and
returns four replies per cycle after warm-up. It reads and writes different
sample addresses. This does not demonstrate complete pixel read/modify/write.

The component passes complete-mapping synthesis setup at 1 GHz, ASAP7 TC/SLVT
and 25% I/O budgets: +357.898 ps core/+91.454 ps I/O slack, 11,194 standard
cells and 1,397.99 µm² cell area, plus 16 SRAM macros occupying 6,643.81 µm².
Default-activity power is 0.0735603 W. No new place/route is performed; this is
component setup screening, without workload power or integrated timing proof.

The default renderer does not instantiate this new service yet. Its scalar
sample-expander/output-merger ingress still limits that path to one sample
per cycle. H3 still requires wide ingress, a complete merge pipeline, sample
reservations and ordering, valid/dirty and refill ownership, clear/resolve/export
integration, cancellation and legal early rejection before shader dispatch.
The four-complete-updates gate and H4–H8 remain open.

## H3 pipelined sample merge arithmetic

The [sample-merge checkpoint](performance/mobile-sample-merge-pipeline-2026-10-19.json)
adds a three-stage elastic RGBA8/D24S8 arithmetic pipeline. The stages perform
depth/stencil comparison and factor selection, rounded blend products, then
equations and channel masks. Every accepted sample captures its state, index
and ticket. Output payload remains stable under backpressure. Rounded division
by 255 uses an exact bounded correction instead of a hardware divider.

Four tests pass: 5,432 independent-model semantic cases with changing state and
stalls, a 512-sample continuous stream, and 131,072 exhaustive product/convex-sum
rounding cases. With continuous readiness, one instance accepts one sample per
cycle and returns one arithmetic result per cycle after three-cycle warm-up.
The component retains the existing raw OutputMerger contract, including its
zero-stencil depth-write path and raw blend-factor behavior; this is not GLES
API or numerical closure.

Complete-mapping synthesis passes at 1 GHz, ASAP7 TC/SLVT and 25% I/O budgets:
+246.173 ps core/+422.913 ps I/O setup slack, 7,925 standard cells and
1,024.11 µm² cell area, without SRAM macros. Default-activity power is 0.017656 W.
No new place/route is performed. All 483 source/build/ABI hashes, the source
archive, regression log, emitted RTL and synthesis reports are recorded.

This arithmetic block is not yet instantiated in the default renderer and
does not own SRAM requests, reservations, refill/dirty state, cancellation or
export. Its one-result-per-cycle rate is not complete sample-update throughput.
The next H3 integration must combine four lanes with banked storage and retain
each sample reservation through actual writeback, before wide renderer ingress
and legal early rejection can close the complete pixel-backend gate.

## H3 resident quad read/modify/write backend

The [resident-backend checkpoint](performance/mobile-resident-sample-backend-2026-10-19.json)
now composes the banked SRAM with four arithmetic merge lanes in
`ResidentTileSampleBackend`. `TileSampleQuadMerge` atomically admits each native
quad's active lanes, reserves their samples and retains each reservation through
the actual SRAM write commit. Active lanes must use distinct banks; a 2x2 quad
at one sample satisfies this contract. Eight packet contexts retire in acceptance
order while lanes and banks may progress independently. Sparse and empty masks
are supported, and every sample carries its accepted state and ownership tag.

Cancellation stops admission and drains each accepted owner. Unoffered work can
be discarded, while an already offered read or write retains valid and payload
until acceptance. Per-packet completion masks distinguish committed color/depth
writes from canceled samples. Concurrent lane terminal events are combined.
The exclusive maintenance interface provides initialized old values and later
inspection; attachment valid/dirty, refill and export policy remain parent duties.

Fifteen tests across four suites pass on the final frozen source. With both
behavioral and vendor SRAM, 128 consecutive quads perform 512 complete RGBA8
source-over/D24 test-and-write/S8 test-and-increment updates. Actual SRAM write
commits are four samples on each of 128 consecutive cycles. Admission through
ordered completion takes 135 cycles, excluding initialization and inspection.
This measures resident old-value reads, merge arithmetic and writeback together.
It is not a full renderer, shared-CU, DRAM or frame-rate measurement.

The regression also checks 768 changing-state sparse/empty packets at 1x/2x/4x
with repeated sample overlaps and stalled completion, comparing final SRAM to
an independent ordered integer model. Cancellation checks reconstruct final
SRAM from committed/canceled masks, rebind and reuse the same sample addresses.
An explicit test stalls all four already offered reads or writes across cancel,
then verifies stable payload, exact drain, held completion and reservation reuse.

Complete-mapping synthesis passes at 1 GHz, ASAP7 TC/SLVT and 25% I/O budgets:
+133.670 ps core/+47.425 ps I/O setup slack, 84,545 standard cells and
11,884.5 µm² cell area, plus 16 SRAM macros occupying 6,643.81 µm².
Default-activity power is 0.195952 W. These are resident-component setup results;
no new place/route, workload-power or whole-GPU timing claim is made. All 487
source/build/ABI hashes, the recoverable source archive, regression and emitted
RTL/report hashes are recorded. A test-only refinement adds explicit write-cycle
assertions; its production RTL is byte-identical to the first combined run.

The existing production `RenderPipeline` and persistent attachment store still
use their scalar composition. The resident component demonstrates the local
four-complete-updates rate, but H3 remains open until attachment clear/load/refill,
valid/dirty ownership, store/resolve and fault/cancel handling are integrated,
renderer and shader-export ingress are widened, and legal early rejection is
verified before shader dispatch. The original workload and remaining H0–H8 gates
remain required. Current PPA screening continues with synthesis only.

## H3 persistent banked attachment integration

The [persistent banked checkpoint](performance/mobile-persistent-banked-attachments-2026-10-19.json)
connects `TileSampleQuadMerge` to the existing persistent attachment lifetime
through the opt-in `bankedSamples=true` profile. Lazy clear/discard values feed
the merge arithmetic without preinitializing SRAM or reading external old data.
Missing load-start planes use a fifth, fairly arbitrated SRAM write port while
their sample reservations remain live. Valid and dirty updates combine all
simultaneous quad commits and refill events; loads do not mark samples dirty.

The ordered scalar/partial-clear/export/resolve path uses an exclusive one-cycle
compatibility port after quad completion drain. A waiting scalar request cannot
block an older quad's refill. An offered stalled refill retains its external
response and payload until SRAM commit, including across cancellation. SRAM and
merge ownership begin only after descriptor validation, so invalid modes fail
admission without initializing unsupported internal state. Native, RGBA, BGRA,
RGBX and BGRX formats decode before native channel masks and blending, then
encode at writeback using the admitted descriptor snapshot.

The final source passes 38 banked/resident checks across six suites, 57 default
compatibility checks across five suites, and 19 banked eight-credit checks.
These counts describe configuration runs, not distinct API requirements.
Behavioral and vendor SRAM both commit four samples on each of 128 consecutive
cycles after a lazy-clear start, with blending, depth and stencil enabled and
no external attachment traffic before pass end. The same tests then perform
masked partial clears, overlapping quads and an MSAA resolve with color-store
discard. Final stores and byte-wise rounded resolves match an independent
ordered model. Load-start repeats fetch exactly four color and four depth
words once; a newer scalar request waits while those loads progress. Clear
boundaries, held completion, canceled/faulted refills and identity reuse pass.
All five memory orders and native channel masks retain the bound format through
resolve despite mutation of the incoming descriptor. The default buffered
512-word export reproduces 32 full lines and 2,735 cycles.

The mapped synthesis screen completes with no omitted operators, but **fails
1 GHz setup** at ASAP7 TC/SLVT and 25% I/O budgets: −594.959 ps core and
−291.176 ps I/O slack. It reports 213,018 standard cells, 28,310.7 µm² cell
area, and 16 SRAM macros occupying 6,643.81 µm². Default-activity power is
0.265079 W. The cycle benchmark does not demonstrate operation at 1 GHz;
timing remains open. No new place/route is performed. All 489 source/build/ABI
hashes, the recoverable archive, passing regression logs, emitted RTL and the
failing synthesis reports are frozen in the measurement record.

The production renderer and double-buffered host composition still select the
scalar profile. Next work must cut the reported integrated timing paths and
route wide ingress through both attachment slots and shader exports, then add
legal early rejection before expensive dispatch. H3 and the remaining H0–H8
workload, resource, visibility and API gates remain open.

## H3 staged refill addresses and scalar request ownership

The [banked staging checkpoint](performance/mobile-banked-attachment-staging-2026-10-19.json)
splits quad refill addressing into registered steps. It captures the selected
index and missing plane first, computes paired 4x16 row-pitch products and
column bytes, joins the row product, adds the admitted tile base, then adds the
column. Four preparation cycles are added to a missing-plane load; the warm
resident quad path retains four actual sample commits per cycle. Cancellation
in preparation drains the accepted quad without creating an unnecessary new
memory request. Already offered external and SRAM refill owners still drain.

The opt-in banked scalar interface now owns one registered request entry. It
captures the memory request, rebased sample index and plane at acceptance.
Changing external sidebands afterward cannot redirect the request. One newer
request can wait behind a stalled older response without changing that response.
Queue occupancy participates in clear/quad admission and pass close, preserving
ordering across the extra stage. The default scalar profile keeps its direct
path. The clear-read diagnostic observes actual internal word-service admission
after banked scalar capture.

The frozen source passes 41 banked/resident checks across six suites, 57 default
compatibility checks across five suites, and 19 banked eight-credit checks.
New checks cover scalar sideband mutation and response replacement, rows 14/15
with large pitches and nonzero low-half product carries at 1x/2x/4x, high-address
depth bases, and cancellation at eleven delays across preparation/offered-read
phases followed by identity reuse. Behavioral and vendor SRAM retain the same
128 consecutive four-sample commit cycles after lazy clear. Default buffered
transfer and 1x/4x export cycle measurements are unchanged.

At unchanged 1 GHz ASAP7 TC/SLVT constraints with 25% I/O budgets, complete
mapping passes but setup still fails: −245.621 ps core and −113.085 ps I/O.
Margins improve by 349.338 ps and 178.091 ps over the preceding checkpoint.
The all-I/O qualification report supersedes the intermediate −55 ps I/O result.
The mapped design has 210,488 standard cells and 27,874 µm² cell area, plus
the same 16 SRAM macros occupying 6,643.81 µm². Default-activity power is
0.268293 W. No new place/route or workload-power claim is made.

All 489 source/build/ABI hashes, the recoverable archive, regression logs,
emissions and failing synthesis reports are frozen. A test-only refinement adds
the low-half carry cases; its emitted production RTL is byte-identical to the
version used for synthesis. The original constraints and throughput target
remain in force. Remaining work includes the integrated SRAM arbitration paths,
wide ingress through both attachment slots and production shader exports,
legal early rejection, and all remaining H0–H8 integration and workload gates.

## H3 exclusive attachment SRAM ownership

The [exclusive-bank checkpoint](performance/mobile-exclusive-attachment-banks-2026-10-19.json)
gives the optional scalar/clear/export/resolve SRAM service an explicit
exclusive owner. Tagged bank arbitration now depends on that owner rather than
the compatibility address, index, sample-mode decode or read/write enables.
Compatibility operations require quiescent tagged producers and drained replies;
assertions enforce both directions of this ownership contract.

The parent retains tagged ownership while any quad refill preparation, external
request or response is live. Canceled arithmetic can finish before an offered
fifth-port refill write commits, so arithmetic drain alone cannot grant exclusive
access. This guard preserves refill progress and its exact response/data owner.
The change adds one internal compatibility-service bit, with no persistent-store
or host pins, command ABI changes, extra banks, data capacity or transfer credits.

The frozen source passes 43 banked/resident checks across six suites, 57 default
compatibility checks across five suites, and 19 banked eight-credit checks.
New behavioral/vendor SRAM tests change both tagged and compatibility addresses
during exclusive accesses, verify that tagged readiness stays blocked, then
release ownership and verify tagged replies and all sample contents. Existing
partial-clear/load/store/resolve/format, scalar staging, address-carry and cancel/
fault tests pass. Both SRAM models retain 128 consecutive four-sample commit
cycles after lazy clear without external old-data reads before pass end.

Complete-mapping synthesis at unchanged 1 GHz ASAP7 TC/SLVT and 25% I/O budgets
reports −175.595 ps core and −7.087 ps I/O setup slack. These improve by
70.026 ps and 105.998 ps over the preceding checkpoint, but setup still fails.
The final all-I/O result supersedes the intermediate positive I/O result.
The mapped design has 212,260 standard cells and 28,281.5 µm² cell area, plus
the same 16 SRAM macros occupying 6,643.81 µm². Default-activity power is
0.246604 W. No new place/route or workload-power claim is made.

All 489 source/build/ABI hashes, the recoverable source archive, regression logs,
emitted RTL and failing synthesis reports are frozen. The remaining worst path
runs from a quad input queue into depth-valid state; timing is still open.
Next functional work routes quads through both attachment slots with completion
drain before end/cancel handoff, then connects production renderer/shader export
ingress. Legal early rejection and all remaining H0–H8 gates remain required.

## H3 two-slot banked attachment integration

The [two-slot banked checkpoint](performance/mobile-banked-double-functional-2026-10-19.json)
routes native four-sample quads and ordered results through the active producer
slot of `DoubleBufferedTileAttachmentStore(bankedSamples=true)`. Both combined
memory and externally arbitrated per-slot ports are supported. Accepted and
committed sample events reflect actual handshakes and SRAM writes.

Producer handoff now waits for accepted local work, refill responses, held quad
results and accepted clear completions, as well as upstream drain. Pulsed
cancellation remains pending until this boundary drains. A held end still permits
older upstream quads while that producer is not drained. This also fixes a default
scalar-profile bug reproduced on the preceding commit: cancellation could release
the producer while an accepted clear result remained backpressured. The next tile
must now wait for that result. Response fault metadata is valid-qualified without
using ready, avoiding a refill ready/fault combinational cycle.

The frozen source passes 64 banked checks across eight suites and 58 default
compatibility checks across five suites. New tests exercise behavioral and vendor
SRAM, both memory port configurations, old-slot write-response stalls, a late
old-slot fault while the next slot holds its quad result, held end, pulsed cancel,
cold-read cancellation and slot reuse. Exact per-pass identities, useful byte
counts and stored color/depth words are checked independently.

In the synthetic two-slot workload, the first 4x tile commits 512 samples in
128 consecutive cycles with four actual writes per cycle. While its external
write responses remain stalled, the next 2x BGRA tile loads eight useful words
through four physical 64-byte reads, updates them twice without reloading, and
stores eight dirty words. Each next-slot useful transfer count is 32 bytes.
This is a component benchmark; production renderer and shader wide ingress,
legal early rejection, full H3 workload gates and remaining H0-H8 requirements
are still open. The default renderer profile remains scalar.

This functional checkpoint makes no new PPA claim. The synthesis-only run for
the two-slot Mobile profile uses the original 1 GHz and 25% I/O constraints;
its final mapping and all-I/O setup results will be recorded separately.

The completed [two-slot synthesis checkpoint](performance/mobile-banked-double-attachments-2026-10-19.json)
qualifies source commit `3f458d2`, with all 491 frozen source/build/ABI hashes
matching that committed tree. Complete mapping passes with no omitted operators
or undriven logic, but setup fails at the unchanged 1 GHz ASAP7 TC/SLVT and
25% I/O constraints: -164.141 ps core and -85.471 ps I/O slack. The design maps
to 477,562 standard cells occupying 63,263.2 µm² and 32 SRAM macros occupying
13,287.6 µm². Default-activity power is 0.801501 W. These are two-slot component
results, not an optimization delta against the earlier single-slot profile.
Timing remains open; no new place/route or whole-GPU performance claim is made.

## H3 native quad ingress into the fixed-function renderer

The [banked fixed-function renderer checkpoint](performance/mobile-banked-fixed-renderer-2026-10-19.json)
connects `RenderPipeline(bankedAttachments=true)` to either one persistent store
or the two externally arbitrated attachment slots. Quad rasterization retains
four pixel lanes through a registered sample-major expander. Each output visits
one sample index across the quad, skips uncovered lanes and empty sample indices,
and snapshots local coordinates, sample depths, color and merge state. At 1x,
complete quads can replace one another every cycle. The optional renderer events
count actual backend admissions and SRAM commits.

Fixed-function texture sampling retains its existing scalar service. A quad
serializer filters helper/zero-coverage lanes and carries the matching edge and
sample-depth sidebands through texture stalls; textured pixels then enter the
same banked backend. This preserves functionality without widening texture
throughput. The default renderer profile remains scalar, and this opt-in profile
currently rejects programmable/vertex-core paths until shader quad exports are
connected. No host command or descriptor ABI changes are introduced.

Queued clear/end commands now let older upstream quads finish before closing
admission. Selected-slot abort metadata drains raster work after cancellation or
an attachment fault. A focused integration test exposed an abort-reset ordering
error in the new path: a fresh one-slot pass could report success with zero
commits. The final implementation gives an accepted begin priority over the old
abort latch. Termination tests require the reused pass to render another draw
and make actual SRAM commits, in addition to checking status and completion drain.

The frozen 496-file source passes 52 banked checks across six suites, six renderer
termination/reuse checks, and 58 default compatibility checks. Exact-image
comparisons cover clear/load starts at 1x/2x/4x, nonzero-origin 8x8 tiles, textured
1x/4x draws, varying per-vertex depth, blending, stencil, queued partial clear and
scissor intersections. Both one-slot and two-slot renderers pass early end,
pulsed cancellation during a cold read, actual read faults, held completion and
subsequent draw reuse. Existing behavioral/vendor SRAM and old-slot fault tests
also pass.

In the 32x32 synthetic four-draw workload, clear-start banked draw work takes
441/827/1,472 cycles at 1x/2x/4x, versus 1,719/3,317/6,531 for the scalar reference
with identical inputs. The 4x case accepts and commits 1,407 samples, with
106 four-sample commit cycles. Load-start draw work remains slower through the
current word-refill service: 3,121/6,218/12,356 cycles versus the same scalar
reference figures. These are draw-stage/component measurements, not complete
frame or whole-GPU throughput. Export credits and memory stalls are measured
separately; cold refill throughput remains open.

The 1280x720 Mobile emission includes one renderer, two banked stores, one quad
expander, one texture serializer and 32 reachable SRAM macro instances. Sample
arrays provide 16 KiB logical capacity in 32 KiB physical macros across the two
slots. The source archive, regression logs, emitted RTL and hierarchy hashes are
frozen. Integrated renderer synthesis is recorded separately at the original
1 GHz/25% I/O constraints; this functional checkpoint makes no new PPA claim.
Programmable shader exports, production host/two-engine integration, legal early
rejection, integrated timing closure, full H3 workload gates and remaining H0-H8
requirements are still open.

The [integrated fixed-function synthesis attempt](performance/mobile-banked-fixed-renderer-synthesis-2026-10-19.json)
uses the exact `d0ebf88` source and emitted Mobile RTL. All 496 source/build/ABI
hashes match that committed tree and the recoverable archive. The synthesis-only
run retained 1 GHz ASAP7 TC/SLVT and 25% I/O constraints, but timed out after
900 seconds in `1_synth` following ABC logic optimization. No final complete-
mapping or setup qualification report was produced. Intermediate AIG statistics
are not mapped cell area, power or timing; no integrated PPA result is claimed.
The immutable manifest, emitted/macro hashes, run log and failure result are
preserved. Complete integrated synthesis and timing closure remain open.

## H3 programmable shader quad exports

The [shader quad-export checkpoint](performance/mobile-shader-quad-exports-2026-10-19.json)
adds an optional registered four-pixel result port to `KernelFragStage`, its
parallel scheduler and shared-worker wrapper. Each lane keeps its effective
coverage and independently selected sample depths. ABI 0 retains its nonzero
emit/depth convention; ABI 1 retains emit, discard, reserved-bit and depth-override
behavior. Helpers and discarded lanes carry zero coverage. Empty quad groups
produce no packet. This groups the existing shader outputs without changing the
SoA memory ABI or host command/descriptor encodings.

The emit cache advances four lanes per packet, can replace a consumed packet
every cycle and retains the entire packet under stalls. Batch and draw retirement
wait for the packet that owns the final output, including a late raster flush
after a full batch. The parallel scheduler routes both packet data and readiness
through its ordered worker token, so faster younger workers retain their results
until older batches retire. `RenderPipeline` snapshots each packet's attachment
state in the common sample-major expander before advancing the draw context.
`RenderCore(bankedAttachments=true)` exposes this path for persistent jobs.
The fixed-function and programmable paths share the attachment ownership boundary.

Integration exposed response-ready/cancellation cycles in the command owner and
its translated word client. Word bridges now provide valid-qualified fault
metadata that stays with each offered reply, including buffered commands,
texture fills and every member of combined reads/writes. The existing consumed-
line fault pulse remains unchanged. `RenderCore` supplies immediate fault metadata
to the attachment store and registers the accepted word-fault event before feeding
cancellation back through the command controller. The coherent host uses the new
word metadata for attachment faults. Queued replies preserve their own error bit
through stalls, invalidation and slot reuse.

The frozen 500-file source passes 143 checks: 79 native/bridge checks across
11 suites, 42 scalar shader compatibility checks across seven suites, 16 fixed-
function image/termination checks, four actual shared-CU configurations and two
overlapping quad output-contract cases. The full-size case instantiates four CUs
with 16 lanes and eight warps; its small system uses an eight-set/two-way L2
and a 16-fragment dispatch limit. All source/build/ABI hashes are frozen, and
the added overlapping-contract test leaves the prior 499 hashes unchanged.

The raw worker test emits 32 consecutive quad packets from a 128-lane batch under
continuous readiness after shader results are available. This proves the result
port's packet rate; shader execution and global staging are still separate costs.
The real-CU tests check exact attachment images, graphics/compute overlap, task
ownership, shader traps, command faults, context/TLB changes, completion stalls and
subsequent reuse. MSAA protocol tests independently check exact stored words at
1x/2x/4x for both serial and parallel fragment workers. These component and small
system workloads do not establish whole-frame mobile throughput.

The emitted 1280x720 Mobile programmable renderer has two banked attachment slots
and four shared shader workers. Its CUs are external pool interfaces; the real-CU
harness verifies actual CU instances separately. Source snapshots, recoverable
archives, logs and emitted hierarchy hashes are frozen. The default production
binned/two-engine host still requires native-path integration. Shader staging is
still the existing global-memory protocol; on-chip payload/export storage, legal
early rejection, cold refill throughput, integrated synthesis/timing closure,
full H3 workload gates and remaining H0-H8 requirements remain open. No new PPA
result is claimed by this functional checkpoint.

## H3 native Mobile binned host integration checkpoint

The [integration checkpoint](performance/mobile-native-binned-host-integration-2026-10-19.json)
enables banked attachments by default in `MobileGpuConfig` and forwards the
selection through the tile-bin, continuing and memory-submitted pipelines into
`GpuHostSystemAxi`. Generic pipeline constructors retain their explicit scalar
fallback. Mobile PPA emitters use the same selection. GBC1 capability word 4 bit
20 reports the selected path through `GPU_BIN_CAP_BANKED_SAMPLES`; command and
descriptor layouts are unchanged.

Twenty initial checks pass: 17 configuration/C ABI checks and three AXI host
cases covering the scalar fixed fallback, native fixed rendering and native
programmable rendering on two four-lane CUs. Each host case checks exact images,
two submissions, two queued DMA copies and VM ownership. Both native engines
perform sample updates: engine 0 accepts/commits 720 samples with 80 four-sample
commit cycles, and engine 1 accepts/commits 288 with 16 such cycles. Scalar
fallback observations remain zero. Optional performance counters expose these
actual events; this is an integration observation, not a frame throughput claim.

The final 500-file source snapshot and recoverable archive are frozen. The
initial runs preceded later PPA emitter forwarding and deep-test fixture edits.
The full native memory/tile-bin regression, including SRAM-backed 720p frames,
and the complete AXI host/legacy DMA regression are still running at this
checkpoint. The four-CU, sixteen-lane/eight-warp host case and production Mobile
RTL hierarchy audit remain pending; they are not counted among the 20 checks.
Subsequent qualification results will be recorded separately.

On-chip shader payload/export storage, legal early rejection, cold refill
throughput, integrated synthesis/timing qualification and the remaining H0-H8
requirements remain open. Current PPA evaluation continues with synthesis only.

## H3 depth-first cold color refills

The [deferred color-refill checkpoint](performance/mobile-deferred-color-refills-2026-10-19.json)
repairs a failure found by the full native binned regression. That frozen run
passed 65 of 66 checks. Restarting an exported prefix produced the exact image
and 224 stored bytes, but loaded 448 bytes for 56 samples rejected by depth and
stencil. The scalar reference loaded only the required 224 depth/stencil bytes.
The original strict byte assertions remain in place; failure and diagnostic
logs are preserved.

The banked attachment owner now refills depth/stencil first. When color is cold
and tests are enabled, a tagged provisional SRAM read evaluates the existing
comparison contract under the sample's reservation. A rejected sample advances
with captured depth/stencil and leaves color invalid and clean. A passing
sample fills color before its normal merge read. The provisional test commits
no depth or stencil state; final arithmetic and actual SRAM writes retain that
responsibility. This avoids unneeded color loads after shading, and does not
close the separate legal early-rejection requirement before expensive dispatch.

Offered normal and provisional SRAM reads retain their tag and identity through
cancellation. A flow/pipe reply queue captures effective load/clear values and
keeps private provisional replies behind older normal replies. Probes, held
reads, refills and merge work must drain before scalar/export ownership or pass
reuse. Resident traffic retains its flow path.

The frozen 500-file source passes 32 focused checks across five suites plus the
strict restart case. Independent comparison tests cover all eight depth and
stencil functions at 1x/2x/4x in both behavioral and vendor SRAM models, followed
by passing blends that must fetch previously rejected colors. Both models also
cover 21 cancellation/reuse phases and retain 128 consecutive four-sample
commit cycles. Two-slot overlap, fault/end/cancel ownership and renderer reuse
checks pass. The repaired restart loads and stores 224 bytes and matches the
reference image; its small workload completes in 2,879 cycles versus 3,892 for
the scalar baseline. These are focused workload measurements, not frame-rate
or whole-GPU performance claims.

The complete 66-check native regression is running on this source. Eight
AXI/legacy DMA host checks and the production Mobile RTL audit are queued after
it. Their final results remain pending. Integrated synthesis/timing, pre-shader visibility
rejection, payload integration, the remaining H3 gates and H0-H8 requirements
remain open. Current PPA evaluation continues with synthesis only.


### Native Mobile banked host qualification, 2026-10-19

The repaired native path passes 66 configuration, ABI, memory-bin and tile-bin
checks plus eight AXI host/legacy-DMA checks. The strict arena-exhaustion restart
case retains the exact output and its original 224-byte load/store assertions.
The AXI matrix includes scalar fallback, native fixed-function rendering,
two shared four-lane CUs, and four shared sixteen-lane/eight-warp CUs. It checks
queued DMA, VM ownership/rebinding, GPU input publication, translation faults,
and reset drain. The full-size CU host case uses a small 32x16 image and reduced
caches; it does not establish representative frame throughput.

The complete 1280x720 clear/store image covers 3,600 tiles and 1,843,200 color
plus depth words in 5,937,039 cycles. The sparse rendered image checks the same
word count with three active tiles in 663,762 cycles. Automatic continuation
covers 7,200 visits, 27 references and one restart in 1,370,465 cycles, with
8,844 load bytes and 8,844 store bytes. These are simulated workload cycles;
a qualified rendering clock remains open.

Production RTL emission uses the Mobile 1280x720, four-CU/sixteen-lane/eight-warp
profile, two tile engines, 32-byte AXI beats and black-box SRAMs. Its reachable
hierarchy contains two native render pipelines, four attachment stores, eight
fragment workers, four shared CUs and 64 attachment SRAM macros. A third scalar
render pipeline remains inside the compatibility host; it contains no banked
stores or fragment workers and is not one of the two native tile engines.

The [frozen qualification packet](performance/mobile-native-binned-host-2026-10-19.json)
records all 74 checks, frame markers, 500 source hashes, source archive and 714
emitted-file hashes. This completes the bounded native integration regression
and hierarchy audit previously pending at the default-selection checkpoint.
H3 remains open for legal pre-shader rejection, representative throughput,
integrated synthesis/timing and its remaining gates. Shader staging still uses
the existing global-memory transport in this native host checkpoint. No new
place-and-route work was run.


### Optional shared shader payload transport, 2026-10-19

The optional 64 KiB payload store uses sixteen word banks and 64 characterized
SRAM macros. It reserves fourteen complete 4,608-byte SoA input/output slots
(64,512 reserved bytes). Allocation zeroes a fresh generation; masked writes use
serialized synchronous read/modify/write service. This implementation makes no
one-line-per-cycle claim. Full/oversized reservations can return a spill decision,
but the producer still owns the separate memory-backed transport.

`GpuSystem(shaderPayload=Some(...))` connects one shared store, independent
physical management owner ports and one bound data port per CU. Local accesses
bypass the CU private cache and translation; uniforms outside the bound prefix
retain the existing VM path, including mixed tail lines. Compute and explicit
memory-backed graphics tasks remain unbound. Unsupported local AMOs fault
instead of modifying the unrelated global alias; normal global AMOs retain their
existing path. CU retirement waits for both data and atomic transport drain.

Accepted leases remain pinned through producer completion consumption. The
optional shader-client router snapshots each binding with its reserved task
slot, restores local tags independently of physical CU selection, and exports
pins for accepted queued tasks and prospective client offers. These producer
pins join the system's dispatch pins, preventing lease recycling before a
queued launch reaches a CU. Faults retain ownership through drain; reset blocks
new allocation/staging while accepted leases can cancel or release.

The [shared-pool qualification](performance/mobile-shared-payload-pool-2026-10-19.json)
records 76 checks and emitted four-CU/sixteen-lane/eight-warp RTL with one pool,
four line routers, four atomic guards and 64 payload macros. The
[client-router checkpoint](performance/mobile-shader-client-payload-routing-2026-10-19.json)
adds 13 checks and default-size producer-pin emission. Actual two-CU tests
retain three distinct payloads at the same VA, with shared local task tags,
blocked pre-admission releases, held completion and zero external payload
transactions. Unit routing covers one, two and four CUs. A
[combined-source checkpoint](performance/mobile-native-payload-integration-2026-10-19.json)
checks two routed payload scenarios, strict native restart and native shared-CU
AXI rendering after merging the attachment and payload changes.

At this checkpoint the optional transport was not yet wired through the renderer
hierarchy. The fragment-worker checkpoint below adds allocation, local
staging/readback, full-pool memory fallback and export/retirement ownership in
`KernelFragStage`. The later renderer checkpoint below connects the parallel
workers, tile engines and host. The fragment producer validates aligned, non-wrapping bounds
outside CU shared memory; other future payload producers need the same contract.
The default Mobile renderer still uses global staging. GPU index/attribute
fetching and reuse remain part of H5; no H0-H8 milestone or synthesis gate is
closed by these checkpoints.


### Fragment-worker payload staging and export lifetime, 2026-10-19

`KernelFragStage(shaderPayload=Some(...))` now reserves one complete SoA slot
before staging a batch and supplies its trusted binding to the shared CU.
Local staging/readback uses the producer's masked pool port. A full/oversized
spill decision leaves the shader unbound and retains the existing memory-backed
staging port. Fresh local output colors start at zero; the fallback preserves
existing global output contents. Depth/control defaults and helper coverage
retain their raster semantics. Local admission rejects unaligned, wrapping or
CU-shared-memory-overlapping extents before allocation or shader launch.

A reservation remains owned until accepted staging work, actual exports and
any bound draw-retirement event drain. Pending batches acquire a fresh
generation; a late flush cannot retire a held last quad or recycle its lease.
Cancellation retains already offered allocation/data requests and export
payloads, poisons unfinished work, suppresses younger exports and drains the
lease before reuse. Line service is blocking; throughput remains unqualified.

The [frozen fragment-worker packet](performance/mobile-fragment-payload-staging-2026-10-19.json)
records 25 passing checks, 514 source hashes and full-size worker RTL emission.
Actual one-CU tests use four lanes/two warps and sixteen lanes/eight warps.
Three full 128-thread draws emit 384 fragments in 3,781 simulated cycles with
zero global staging requests. A forced-full-pool fallback emits 24 fragments
across three draws using 21 global staging requests. Tests also cover a pending
partial batch, fresh generations, helper masks, held exports/retirement, late
flush, three invalid address classes, cancellation at allocation/staging/export,
shader faults and healthy reuse. Eleven compatibility checks retain the
existing quad export, overlap, kernarg-bank and draw-retirement behavior.

This qualifies a fragment-worker harness with reduced caches, behavioral
payload SRAM and integer/RVV copy kernels plus an ordinary uniform load. It is
not a two-engine renderer or general shader qualification. The emitted worker
contains one payload controller and external pool/CU ports; it instantiates no
private CU or payload store. The following renderer checkpoint extends this
worker qualification through parallel-worker management, queued task bindings,
producer pins, tile cancellation/context and AXI-host wiring. Mobile default
selection and broader workload qualification remain open. No new synthesis or
place-and-route result is claimed.


### Optional local payloads through the AXI renderer, 2026-10-19

`MobileGpuConfig(localShaderPayloads=true)` now connects fixed producer owners,
allocation/data ports, task bindings and pins through the parallel fragment
workers, both tile engines, nested shared-task routers and AXI host to one shared
payload pool. `--local-shader-payloads` selects this configuration in the host
emitter; capability bit 21 reports it. At this checkpoint the default remained
the existing global staging path. The unused compatibility renderer receives no
local payload port.

The [renderer qualification packet](performance/mobile-local-payload-renderer-2026-10-19.json)
records eight local-path AXI checks on 515 frozen sources. Two-CU tests exercise
4 KiB resident storage, a 512-byte mixed resident/spill pool and 64-byte oversized
fallback. The four-CU, sixteen-lane, eight-warp profile uses 64 KiB, reaches all
four CUs, compares 1,024 final words across two submissions and fresh VMs, and
releases all 360 allocations with zero global staging bytes. Both native tile
engines perform sample work. These tests retain queued DMA ordering, exact
images and bounded completion checks. Another 47 compatibility checks cover
configuration/ABI, actual-CU fragment payload behavior, quad exports/overlap,
generic reset admission and all seven original global-path AXI cases. All 55
checks pass on the same frozen source, which matches code commit `5f94b40`.

GPU-produced input/code publication, seven fault scenarios and reset/reuse
checks run with local payloads enabled. Reset during live shader fetch observes
1,280 reserved bytes and six producer pins in each of two fresh VMs, aborts
queued DMA, drains every reservation and then renders the exact healthy image.
For this internal renderer, accepted jobs may finish their staging, launch and
invalidation sub-operations while command reset drains them. The generic
external graphics interface keeps its existing admission-blocking policy.
A release-edge handoff repair also consumes a batch committed simultaneously
with the previous release and retains a simultaneous queued flush.

Host staging-byte and fragment-phase counters now observe the active binned
renderer. Local bytes count accepted masked producer requests; global bytes
count accepted fallback word requests. These are traffic observations for the
covered scenes, not a throughput or general shader claim. Default promotion,
production hierarchy qualification, GPU geometry fetching/reuse, representative
workloads and remaining H0-H8 requirements stay open. No new PPA result is
claimed; current PPA qualification remains synthesis only.


### Production local-payload AXI hierarchy, 2026-10-19

The [production host packet](performance/mobile-local-payload-production-host-2026-10-19.json)
adds full 1280x720, four-CU, sixteen-lane, eight-warp RTL emission using SRAM
black boxes on the same 515 sources as the preceding 55-check packet. Emission
completed in 59 seconds and produced 799 files. Reachable-instance inspection
finds one shared payload store with 64 payload SRAM macros, eight fragment
payload controllers, four CU payload line routers and four atomic guards.
Both native tile renderers have four fragment workers and two attachment
stores; the compatibility renderer has no fragment worker or payload controller.
The complete host has 162 SRAM wrappers, including 64 attachment wrappers.

The shared store exposes eight physical producer owners and twelve data ports.
The source defines 32 renderer pins and five dispatcher pins. RTL optimization
removes four unused vertex-client entries, leaving 28 renderer pins and five
dispatcher pins. Inspection checks all 165 retained valid/owner/handle/generation/
context field connections into the shared store. This closes production
hierarchy inspection for the optional path; it adds no new runtime or PPA
claim. Default promotion, representative shader/workload qualification, GPU
geometry fetching/reuse, synthesis and the remaining H0-H8 requirements remain
open. Current PPA qualification remains synthesis only.

### Default local payloads for eligible Mobile rendering, 2026-10-19

The [default-selection packet](performance/mobile-default-local-payload-2026-10-19.json)
changes `MobileGpuConfig` to prefer local shader payloads. A binned host selects
the shared pool when fragments are programmable, graphics use the shared CUs,
parallel fragment workers are enabled and attachments use the native banked
path. Fixed-function, scalar shared graphics, unbanked attachments and non-binned
hosts retain their existing transport. `localShaderPayloads=false` or the emitter's
`--global-shader-payloads` selects global staging explicitly. The existing
`--local-shader-payloads` remains accepted and rejects unsupported or conflicting
CLI selections.

Default construction is exercised by exact-image AXI tests for fixed-function,
two-CU and four-CU/sixteen-lane/eight-warp rendering, with queued DMA, fresh VM
ownership and capability checks. Resident programmable defaults require zero
global staging bytes and release every payload allocation. Eighteen config/ABI
checks cover sizing, overrides, invalid CLI combinations and the unchanged ABI
bit assignments. This adds 21 checks on a new frozen 515-source snapshot.

Production emission covers the implicit local default (799 files), an explicit
global override (724 files) and scalar shared graphics (690 files). The latter
two contain no payload store or controller. All 798 emitted SystemVerilog files
for the default match the preceding explicit-local production host after
removing comments and whitespace. The prior opt-in renderer and production
hierarchy packets remain the evidence for spill, publication, fault/reset drain
and the unchanged payload datapath. This default change closes the mandatory
global fragment-staging gap for the covered native profiles. General shader
coverage, representative workload throughput, GPU geometry fetching/reuse,
synthesis and the other H0-H8 requirements remain open.


### FP32 texture shaders on the native payload path, 2026-10-19

The [FP32/texture checkpoint](performance/mobile-local-payload-fp32-texture-2026-10-19.json)
adds five exact-image AXI cases on the default local renderer and its spill/
global alternatives. The shader moves scalar FP32 operands, multiplies 0.75 by
65536, converts the result to Q16.16 coordinates, broadcasts them to vector
lanes, samples a 2x2 texture and exports color plus the original fragment depth.
Distinct alternate texels make an incorrect coordinate affect the image.

Each case uses two fresh Sv32 VMs with different physical mappings and changed
texture contents. A texture-read callback verifies that VM-control writes remain
blocked while the shader owns the job. Queued DMA starts only after the exact
color/depth image is exported. Native depth/stencil/blend and coverage remain
compared with the ordered reference renderer.

Two-CU/four-lane/two-warp cases cover resident storage, mixed resident/spill,
oversized fallback and the explicit global override. The resident case also
runs on four CUs with sixteen lanes/eight warps. Both tile engines perform
sample work; all CUs become active. Resident runs require zero global staging
bytes. Every allocated reservation is released, including across VM reuse.
Fragment batch limits are eight threads on the four-lane profile and sixteen
on the sixteen-lane profile; full eight-warp FP32/texture workgroups still need
qualification. These are selected instruction/resource interaction checks with behavioral
SRAM and reduced caches; general FP32 numeric behavior, derivatives/divergence,
resource families, representative throughput, geometry and remaining H0-H8
requirements remain open. No synthesis or place-and-route claim is added.


### Full eight-warp FP32/texture workgroups, 2026-10-19

The [full-batch checkpoint](performance/mobile-local-payload-full-batch-2026-10-19.json)
extends the preceding shader checks with complete 128-thread workgroups on the
four-CU, sixteen-lane, eight-warp host. Oversized clip-space triangles fill both
native tiles, and `fragmentBatchLimit=0` selects the configured 128-thread
capacity. Exact color/depth/stencil images, changed textures in two
fresh VMs, queued DMA ordering, all-CU activity, zero global staging and complete
lease release remain required.

`maxGraphicsLocalX` reports the largest X dimension actually accepted on the
shared graphics launch port since hardware reset. It is a sixteen-bit thread
count on the optional performance interface, not a new MMIO register. The full
case observes 128; compatibility cases observe eight for the small programmable
host and zero for fixed-function rendering. Shared graphics workgroups here
have Y=Z=1, so 128 work-items at sixteen lanes require all eight logical warps.

Three AXI checks qualify the counter and full-batch workload on a new frozen
515-source snapshot. Production RTL is also emitted with performance exposure
disabled. All 798 SystemVerilog files match the prior production default after
comment/whitespace removal; the counter is absent from that product profile. This closes the selected
FP32/texture full-workgroup gap above. General shader/numeric/resource coverage,
representative throughput, geometry, synthesis and remaining H0-H8 requirements
stay open.


### Bounded GPU vertex input fetching, 2026-10-19

The [vertex-input component checkpoint](performance/mobile-vertex-input-fetch-2026-10-19.json)
adds `VertexInputFetcher`, an internal descriptor/record stream with sixteen
attribute slots, sixteen buffer bindings and four canonical words per
attribute. It reads arrays or unsigned 8/16/32-bit GPU indices, applies checked
signed base-vertex bias, emits fixed restart tokens before that bias, and
retains draw-relative vertex ordinals, effective vertex IDs, instance IDs and
base instance. Instanced bindings use `baseInstance + floor(instanceId/divisor)`;
divisor zero selects per-vertex data. Per-binding recurrence counters implement
positive divisors without a combinational divide.

Float32 bits and signed/unsigned 32/16/8-bit integer elements are supported.
Narrow integers expand to 32 bits. Enabled arrays supply typed zero/one defaults
for absent components; disabled arrays supply four explicit constants. Multiple
attributes may share arbitrary bindings and interleaved bytes. Normalized,
packed and half formats are explicitly rejected in this component version.

Logical byte bounds are checked separately from padded allocation bounds. Each
64-byte read stays inside its allocation, while every scalar component and index
stays inside its logical view, including retained-line hits and the final byte
of a 4 GiB allocation. Split 16x16 address products and registered sums preserve
high bits through bounds checks. One private output record and one outstanding
read bound ownership. Cancellation retains offered reads/records and drains
accepted replies before completion; faults never publish a partial vertex.
Two 64-byte response registers reuse index/attribute bytes and are invalidated
on every draw. They provide no post-transform reuse.

Twenty-one checks pass on 517 frozen sources: seven component cases plus fourteen
existing ABI checks. Coverage includes all sixteen slots, minimum stream width,
all supported formats/index widths, signed bias and restart, independent
divisors including 3 and 4294967295, descriptor snapshots, eighteen invalid
layouts, eleven bounds scenarios, three memory-fault paths, 4 GiB endpoints and
held-read/record/completion cancellation. A shared interleaved line serves 64
logical component reads across two instances with one line fetch. Default-size
RTL emission exposes all 64 canonical output words and five prefix adders, with
no division/modulo operator or private CU/SRAM instance. Read-line data occupies
128 bytes of registers; descriptor/output/arithmetic state is additional. No
area or timing result is claimed.

This is a component boundary, not native indexed-draw support or a new memory
ABI/capability bit. Vertex shader input/ID propagation, primitive assembly and
restart consumption, post-transform reuse, vertex payload/export ownership and
native GPU geometry submission remain next. The enclosing owner must publish
and pin immutable inputs, translation and allocation ownership through drain.
The legacy draw/job ABI is unchanged; H5 and remaining H0-H8 requirements stay
open. Current PPA qualification remains synthesis only.

### Generic GPU vertex execution on shared CUs, 2026-10-19

The [vertex-shader checkpoint](performance/mobile-vertex-shader-stage-2026-10-19.json)
connects `VertexInputFetcher` to a new `VertexShaderStage` shared-CU client.
Its internal version-1 descriptor declares up to sixteen attribute slots and
sixteen four-component varyings. SoA inputs contain the attributes followed by
vertex ID, draw-relative instance ID and base instance. Outputs contain four
FP32 clip-position words followed by the declared varying arrays; a component
mask controls varying readback, with disabled components exported as zero.
Array stride is four times physical CU thread capacity. Uniforms start beyond
the complete prefix, preserving existing x1/x8/x9 kernarg/local-base/capacity
preloads. This adds no native draw memory ABI or compiler implementation.

Batches retain per-record identity and ordinals and may span instances. A
restart token waits for all older shaded vertices and reservation release,
then appears in stream order without launching a shader. Each nonempty batch
owns its scratch/payload reservation through scatter acknowledgements, shared
CU and sampler completion, output readback and accepted exports. Resident
allocation initialization or masked global output clearing supplies zero
defaults without overwriting input arrays or the uniform tail. Empty and
restart-only draws allocate no payload.

Descriptor preflight checks declared counts/masks, PC alignment, logical and
padded scratch bounds, the 4 GiB endpoint and virtual overlaps with used input,
index and shared-memory views before input access. Offered reads, writes,
invalidations, launches and exports remain irrevocable through cancellation.
Accepted input/memory/texture/CU operations drain before reservation release
and held completion. Faults suppress unpublished results; a shader watchdog
and interruption contract remain open.

Twenty-eight checks pass on 519 frozen sources: seven stage cases, seven input
cases and fourteen existing ABI checks. Actual two-CU system harnesses verify
indexed/instanced FP32 transforms, all IDs, restart order, cross-instance batch
boundaries, descriptor/context snapshots, local/global staging and forced
full-pool spill with exact values. Coverage also includes twenty invalid
layouts, empty/restart-only draws, vertex textures and texture faults, held
launch/export cancellation, offered global writes, input/scatter/clear/readback
faults, shader traps and healthy reuse. At sixteen lanes/eight warps, one
resident batch accepts 128 threads across eight instances. A separate draw
enables all sixteen attributes and copies all 64 varying components through a
69,120-byte full-interface prefix, explicitly spilling beyond a 64 KiB pool
while preserving its uniform tail. The test shader remains within 512 words.

Default-size standalone RTL emission audits 46 files, one shared shader
adapter, one fetcher, one payload transport and no private CU or embedded pool.
Metadata and a transposed sixteen-vertex readback window use ten characterized
256x64 SRAM macro references: **20 KiB additional physical SRAM**, separate from
the shared payload pool. Production admission and SRAM accounting must include
that cost when this component is integrated. No new synthesis result is claimed.

Native geometry submission, VM/reset/physical allocation ownership, shared
vertex/fragment producer wiring, primitive assembly, general clipping and
interpolation, post-transform reuse and native bin production remain open.
Normalized/packed/half input conversion, general resources, transform feedback,
representative throughput, synthesis and the remaining H0-H8 gates also remain
open. Current PPA evaluation continues with synthesis only.

### Ordered primitive assembly after generic vertex shaders, 2026-10-19

The [primitive-assembly checkpoint](performance/mobile-primitive-assembly-2026-10-19.json)
adds `PrimitiveAssemblyStage` and the composed `VertexGeometryStage` input,
shader and assembly client. The internal stream supports points, independent
lines, line loops, line strips, independent triangles, triangle strips and
triangle fans. Two retained vertex records preserve connectivity across shader
batches. Restart and instance boundaries reset connectivity and strip parity;
incomplete tails are discarded. Loops close once per segment, including both
directed edges of a two-vertex loop. Degenerate positions still advance strip
parity and primitive numbering.

Each output carries identity, source ordinals/IDs, instance/base instance, a
draw-wide sequence and an instance-local primitive ID that continues through
restart. Triangle strips reorder their first two vertices on alternate
triangles while preserving the last submitted provoking vertex. A component
mask replicates raw flat values before clipping; varying-slot and flat-mask
metadata also travel with every primitive so later stages can bypass arithmetic
on those components. The loop-closing edge uses its first loop vertex as the
provoking endpoint, following [GLES 3.0.6 section 2.17/table 2.12](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf).
Unused vertex slots are zero; positions and unmasked varyings retain their bits.

Preflight checks versions, topology and declared varying/mask bounds. Ordered
record identity/ordinal/instance/base-instance checks and source completion
identity/count/length checks detect malformed streams. Cancellation and faults
retain any offered primitive, suppress younger assembly, consume draining
source records and require source completion before retirement. The composed
client snapshots both child admissions and gates cancellation until an offered
child draw is accepted. Invalid topology cancels before input access. The final
geometry completion remains held and keeps system/context ownership active.

Thirty-seven checks pass on 523 frozen sources: six assembly cases, three
actual-CU geometry cases and the preceding 28 vertex/input/ABI checks. An
independent reference covers 210 topology/length/restart/flat-mask draws, two
instances and 686 exact primitives under stalls. Additional checks cover
degenerate positions, long input gaps, 48 reference primitives, loop closure,
incomplete tails, full 64-component flat masks, the minimum varying interface,
malformed streams, invalid layouts, cancellation and healthy reuse. Actual
two-CU local/global paths each run four topologies with 44 shaded vertices and
six restarts per draw, producing 120 exact primitives per path. Shader batches
cross primitive and instance boundaries. A held final primitive remains valid
after its source payload releases, while geometry/context ownership stays busy.

Standalone default-size emissions audit six assembler files and 51 composed
client files. Assembly adds no private CU or SRAM macro: it retains two complete
history records plus up to three private output records, independent of draw
length. Their maximum record data is 12,005 register bits, with primitive
headers, descriptors, counters and other control additional. The composed
client retains the preceding ten vertex-stage SRAM references (20 KiB) outside
the shared payload pool. Production admission, register/SRAM budgeting and
throughput still need integrated qualification; no synthesis result is added.

This connects GPU input, vertex execution and assembly as an internal client.
Native memory descriptors, host/VM/reset/physical allocation ownership,
vertex/fragment producer wiring and bin generation remain open. General FP32
clipping/viewport/interpolation, point/line rasterization, post-transform reuse,
remaining formats/resources, transform feedback/query integration, watchdog
termination, representative throughput, synthesis and the remaining H0-H8
requirements remain open. PPA evaluation continues with synthesis only.

### Range-preserving FP32 clip arithmetic prerequisite, 2026-10-19

The [clip-arithmetic checkpoint](performance/mobile-clip-float-arithmetic-2026-10-19.json)
adds `ClipFloatArithmetic` for the general clipping work following primitive
assembly. This is an arithmetic prerequisite; it does not clip a primitive or
generate a vertex. Finite FP32 coordinates can overflow a `w +/- coordinate`
intermediate, and a crossing weight can underflow even when its product with
an endpoint is representable. A plain FP32 intermediate would lose that value.

The internal representation retains a normalized 24-bit significand and a
legal exponent range of -512 through 511 in twelve-bit signed storage. One
existing `Fp32FmaLane` implementation and one `Fp32DivLane` instance operate on
normalized significands while exponent arithmetic remains separate. Each
add/subtract/multiply/divide rounds its significand to nearest/even. A separate
convenience FP32 result reports gradual underflow, overflow and packing flags
without discarding the extended result. This is a fixed-function internal
representation, with no shader-visible scalar format or numeric-contract change.

One accepted operation owns its captured operands/tag through response drain.
Cancellation never flushes accepted arithmetic; an already offered response
remains immutable. Malformed encodings and out-of-envelope results return
explicit errors. Codecs preserve all FP32 classes, signed zero and raw NaN
payloads; arithmetic special-value behavior follows the reused scalar units.
The initial codec run exposed an eight-bit signed subtraction wrapping the
minimum subnormal exponent; the qualified implementation widens it explicitly.

Sixty-three checks pass on 525 frozen sources: five new cases, the preceding
37 geometry/vertex/input/ABI checks and 21 direct FMA/divider cases. New coverage
includes 2,065 exact codec roundtrips and 904 exact BigInt rational-reference
arithmetic comparisons, controlled range errors, snapshots and held responses.
A coefficient below FP32 range retains a contribution that packs to a nonzero
subnormal after multiplication; independent endpoint weights recover the
expected sum. Two maximum finite FP32 inputs also form an extended distance
that can be divided back without losing its value to intermediate overflow.
The future clipper must compute both weights independently, avoiding loss from
subtracting a rounded near-one coefficient from one. Halfway packing, gradual
underflow, signed zero, special operands, malformed encodings, accepted-divider
cancellation and healthy reuse are also checked.

Standalone emission audits 22 files with one FMA lane, one divider, a small
response queue and no private CU or SRAM macro. This additional fixed-function
arithmetic area/storage has not been integrated into the Mobile budget, and no
synthesis result is claimed. Six-plane point/line/polygon clipping, bounded
generated-vertex storage, flat-mask bypass and deterministic shared-edge
intersections remain next. Viewport/interpolation, native geometry/bin/host/VM
wiring, post-transform reuse, point/line rasterization and all remaining H0-H8
requirements remain open. PPA evaluation continues with synthesis only.

### Bounded FP32 clipping on the shared-CU geometry stream, 2026-10-19

The [FP32 primitive-clipping checkpoint](performance/mobile-fp32-primitive-clip-2026-10-19.json)
adds `PrimitiveClipStage` and the composed `VertexClipPipeline`. GPU input,
indexed/instanced shared-CU vertex shading, primitive assembly and clipping now
connect through internal streams. Points and lines clip against near/far while
preserving X/Y for their raster backends; triangles clip against all six
canonical half-spaces. This follows [GLES 3.0.6 sections 2.18–2.18.1](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf).
Native host submission and API conformance are not established by this client.

FP32 comparisons classify half-spaces without overflowing distances. Strict
crossings always interpolate inside-to-outside and independently divide both
endpoint weights using `ClipFloatArithmetic`, retaining tiny contributions and
large distances. Finite interpolants are bounded by endpoint values; generated
positions snap to the current plane and retain processed half-space bounds.
Boundary endpoints copy exactly, avoiding duplicate polygon intersections.
There is no arbitrary positive-W epsilon. Zero-W output is reported for later
viewport handling. Nonfinite positions return internal numeric status 7; its
native API policy remains unqualified. Smooth varying special values follow
the arithmetic units, while flat components bypass arithmetic and retain bits.

Two nine-vertex SRAM banks bound polygon storage and export an ordered fan of
at most seven triangles. Every part carries source sequence/primitive/instance,
part index/count, varying-slot/flat-mask metadata, original provoking IDs and
generated-vertex flags. Copied vertices retain IDs; generated vertices have
explicit flags and zero source ordinal/ID. Offered parts survive cancellation,
accepted math drains before completion, and capacity failure is explicit.
The composed owner retains context through final parts and held completion,
including after the source payload pool drains. A clipping fault reports its
sequence/primitive/instance and preserves earlier accepted outputs while
cancelling/draining younger work. Amplified output, intersection and arithmetic
totals use 67, 72 and 80 bits; source/culled primitive counts remain 64 bits.

Seventy-five checks pass on 529 frozen sources: nine clipping cases, three
actual-CU pipeline cases and the preceding 63 arithmetic/geometry/ABI checks.
Directed and exact rational-reference coverage includes every triangle plane,
point/line X/Y preservation, signed zero, tiny/large finite crossings, reversed
and adjacent shared edges, 36 random triangles, negative W, boundary copies,
smooth special values, raw flat NaNs and the minimum interface. A 64-component
case reaches nine polygon vertices and all seven fan parts after clipping away
the original provoking vertex. Tests also cover five cancellation offsets,
held parts, ten invalid layouts, three nonfinite positions and healthy reuse.
Actual two-CU local/global paths each exercise all three primitive kinds over
two instances and produce 42 exact clipped outputs across three draws. The
actual `GpuSystem.vmContextIdle` stays false through held output/completion.
Numeric failure is checked both before any output and after a valid earlier
primitive, including exact fault sequence attribution.

Default standalone emissions audit 27 clipper files and 79 pipeline files.
Clipping uses four characterized 256x64 SRAM macros (**8 KiB physical**) for
684 logical 64-bit rows padded to 1,024 rows. The full internal client contains
14 SRAM macros (**28 KiB**) including the preceding vertex-stage storage,
outside the shared payload pool. One scalar FMA/divider engine services clip
arithmetic; descriptors, private records, queues and other control are additional.
No private CU or payload pool is embedded, and no synthesis result is claimed.

General FP32 viewport/perspective division, smooth fragment interpolation,
broader invariance/numeric qualification and zero-W/nonfinite API policy remain
open. Native descriptors, host/VM/reset/physical ownership, producer/bin wiring,
point/line rasterization, post-transform reuse, remaining formats/resources,
transform feedback/query integration, watchdog termination, production budgets,
throughput, synthesis and the remaining H0-H8 requirements also remain open.
PPA evaluation continues with synthesis only.


### FP32 perspective and viewport preparation, 2026-10-19

The [viewport checkpoint](performance/mobile-fp32-viewport-2026-10-19.json)
adds `Fp32ViewportStage` and `VertexViewportPipeline`. The internal shared-CU
vertex stream now connects input fetching, shading, assembly, clipping and
window-coordinate preparation. Viewport/depth state is captured once per draw.
Native host binding and bin/raster consumption remain open.

The viewport computes local X/Y and reciprocal W with extended exponents and
24-bit RNE significands, then rounds local coordinates to subpixels before
adding the exact signed integer origin. Default output uses signed 64-bit
coordinates with eight fractional bits. Point/line coordinates beyond that
range retain their extended local values and explicitly clear `fixedValid` and
set `guardBandRequired`; a raster consumer must honor those flags. Reciprocal W
remains extended, including exponent 149 for minimum-subnormal W. Depth endpoints
copy exactly; interior depth is bounded by the normalized near/far range.
Dimensions clamp to implementation limits, depth endpoints clamp to [0,1], and
negative dimension encodings or NaN depth state return internal status 1.
Nonempty views require finite positions and positive W (otherwise status 7).
Empty views still execute source vertex work, then cull without viewport math.
These internal policies do not establish native API conformance.

Raw varyings, flat values, clipping provenance and part identity pass through.
Cancellation drains accepted arithmetic and retains offered window primitives.
The composed client retains actual VM context ownership through its last output
and held completion, including after source payload release. Numeric faults
retain earlier accepted outputs and report sequence, primitive, instance, part
and vertex. Part counters use 67 bits and arithmetic totals use 80 bits.

Ten new checks cover exact rational directed/random preparation, all 64 varying
components, large origins, reversed/constant depth, extreme reciprocal W,
signed-64 boundaries, guard-band output, invalid state, cancellation and reuse.
Actual two-CU local/global tests each prepare 42 outputs across point, line and
triangle draws; additional draws verify invalid state before memory access,
first/later zero-W fault attribution, empty-view source execution and actual
`GpuSystem.vmContextIdle` retention. All 85 regression checks pass against an immutable 533-source snapshot;
standalone and composed RTL emissions audit 26 and 87 files respectively.

The standalone stage adds one scalar FMA/divider pair and no SRAM. The composed
client includes two such fixed-function pairs (clipping plus viewport) and the
preceding 14 SRAM macros (28 KiB physical), with private records/control in
addition. It embeds no private CU or payload pool. RTL emission is not a
synthesis PPA result. PPA evaluation remains synthesis only.

Smooth fragment interpolation, guard-band/point/line raster consumption,
native geometry descriptors and producer/bin wiring, host/VM/reset/physical
ownership, broader invariance and API numeric policy, formats/resources,
post-transform reuse, production budgets, throughput and remaining H0-H8 gates
remain open.


### FP32 quad interpolation and pixel-center raster profile, 2026-10-19

The [fragment-interpolation checkpoint](performance/mobile-fp32-fragment-interpolation-2026-10-19.json)
adds `Fp32FragmentInterpolator`, accepting one triangle's `WindowPrimitive`
and a complete `RasterQuad`. All four lanes retain pixel positions, coverage
masks and helper identities. Each lane exports up to 64 raw/FP32 components,
window depth, packed fragment reciprocal W and its extended value.

Smooth components evaluate `sum(edge * reciprocalW * value) / sum(edge * reciprocalW)`;
depth evaluates `sum(edge * windowDepth) / area` and clamps the packed result
to [0,1]. Fragment reciprocal W divides the perspective denominator by area.
Signed 64-bit edges, including the minimum value, convert to 24-bit RNE
significands without exponent loss; every scalar operation rounds separately,
and values pack only for shader export. Flat components bypass arithmetic and
copy the clipping stream's canonical values, retaining integer/NaN bit patterns.
This follows the interpolation structure in [GLES 3.0.6 section 3.6.1](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf);
precision/invariance and complete API numeric behavior remain unqualified.

Preflight checks triangle metadata, active slot/mask bounds, vertex identity,
positive legal reciprocal W, bounded finite vertex depth, common nonzero signed
area, exact edge sums and covered/mask consistency. Invalid inputs return
internal status 1 before arithmetic/output. A covered center with a nonpositive
perspective denominator returns status 7; native API policy for such extrapolated
MSAA centers remains open. Helpers with nonpositive denominators keep their
quad position, depth and flat values, flag invalid perspective, and use zero
smooth values. Smooth NaNs follow the arithmetic lanes. Cancellation drains
accepted/offered math and retains an offered complete quad; completion remains
immutable until accepted. No partial quad is published after a fault.

`TriangleRasterizer(pixelCenter=true)` evaluates coverage and exported edges
at half-integer pixel centers, including center-relative MSAA offsets. Origin
products retain a leading zero for large unsigned screen coordinates. Existing
packed-color clients retain the integer-anchor profile pending native migration;
merely instantiating the new interpolator does not change their image semantics.
Actual-raster integration tests compare center edges, complete coverage masks,
all required covered pixels and interpolated values against independent integer
and exact rational references in both windings at 1x and 4x. Source state is
snapshotted and output/completion stalls are exercised.

All 108 tests pass on 536 frozen sources: 85 preceding geometry/ABI checks,
11 existing raster checks, eight interpolation checks and four actual-raster
integration cases. Default emissions audit 26 interpolator files and 15
center-raster files, with no SRAM in either component. Directed/random checks
cover signed-64 conversion ties/bounds, negative area, inactive slots, raw flat
NaNs, smooth NaNs, reciprocal exponent 149, invalid helper denominators, malformed
records, cancellation/reuse, minimum one-slot/one-sample operation and the full
64-component four-lane interface. The full all-smooth case uses 1,584 scalar
operations, counted in 16 bits. This serialized functional stage has no sustained
quad-rate claim. It adds one scalar FMA/divider pair, private record/control
registers and no SRAM/private CU/payload pool; production register/arithmetic
budgets and synthesis PPA remain open. PPA screening remains synthesis only.

Native geometry/bin/raster/fragment producer ownership, guard-band handling,
point/line rasterization and interpolation, centroid/sample qualifiers,
per-sample depth integration, complete fragment built-ins, invariance/numeric
qualification, formats/resources, queries/watchdog behavior and remaining
H0-H8 requirements remain open.


### Owned FP32 triangle raster and shared-CU fragment-input stream, 2026-10-19

The [triangle-raster checkpoint](performance/mobile-fp32-triangle-raster-2026-10-19.json)
adds `Fp32TriangleRasterStage` and `VertexRasterPipeline`. Generic GPU indexed/
instanced input, shared-CU vertex execution, assembly, clipping, FP32 viewport,
pixel-center triangle raster and general quad interpolation now connect under
one draw owner. Outputs are fragment inputs; native bin allocation, actual
fragment-shader input delivery and launch remain open.

The triangle owner snapshots versioned raster state: screen extent, sample mode,
front-face convention, cull mode and scissor. It carries facing and sample mode
with each complete quad. Both windings, both front-face conventions, all three
cull modes and 1x/2x/4x sampling are checked. Invalid state/records reject before
raster launch. Signed64 bounds rejection handles guaranteed off-screen triangles
before narrowing, including extreme integer viewport origins. Overlapping
coordinates must fit the signed32 raster interface and safe signed32 coordinate
spans; otherwise internal numeric status 7 prevents wrapping. Guard-band
reconstruction and wider/general raster range remain open. This client accepts
triangles; point/line backends remain separate required work.

Cancellation drains queued/offered raster candidates and accepted interpolation
instead of resetting child pipelines. Offered fragment-input quads remain stable,
even after cancellation. Earlier accepted quads survive a later interpolation
fault, which records quad X/Y and lane. Per-primitive completion is held until
accepted; draw completion waits for the full vertex, viewport, raster and
interpolation hierarchy. Actual VM-context idle remains false through final
held output and held draw completion after source payload release. Assertions
check child drain at both completion boundaries.

Per-primitive raster/output/discard counts use 32 bits, covered/helper lanes
34, covered samples 36 and interpolation operations 48. Draw sums use 67-bit
part totals, 99-bit quads, 101-bit lane totals, 103-bit sample totals and 115-bit
operation totals. These conservatively sum fewer than 2^67 amplified parts,
with fewer than 2^32 quads/part, four lanes and at most four samples/lane, and
fewer than 2^16 arithmetic operations/quad. Exported widths are audited.

All 43 focused checks pass against a frozen 540-source snapshot: seven new
owner tests, four actual-CU pipeline tests and 32 upstream checks. Default RTL
emissions audit 42 owner files and 109 composed-pipeline files. The unchanged
earlier blocks retain the prior 108-check interpolation checkpoint. Owner tests compare exact quad order, sample coverage, facing,
helper lanes, FP32 values/depth and counters against independent integer/rational
references. They cover directed and random draws, runtime extents/scissor,
far-origin culling, unsafe overlap rejection, malformed state, full 64-component
output, cancellation/reuse and a later numeric fault after one accepted quad.
Actual two-CU local/global tests use real GPU indices and two instances, varying
W, smooth FP32 values, raw flat data, clipping amplification/culling and 1x/4x
sample modes. Additional actual-CU draws cover pre-admission cancellation,
invalid raster state before memory access, zero-size viewport source execution,
upstream viewport faults, held final fragment input and healthy reuse.

The standalone owner contains one scalar FMA/divider pair and no SRAM. The
composed client contains three such fixed-function pairs (clipping, viewport,
interpolation) and the preceding 14 SRAM macros (28 KiB physical). Neither
embeds a private CU or payload pool; private records, queues, descriptors and
control are additional. Production storage/arithmetic budgets, throughput and
synthesis remain open. PPA screening remains synthesis only.

Native geometry/bin/fragment ownership and shader delivery, host/VM/reset/
physical allocation, point/line and guard-band rasterization, centroid/sample
qualifiers, per-sample depth and polygon offset, complete built-ins, numeric/
invariance qualification, formats/resources, queries/watchdog behavior and the
remaining H0-H8 requirements remain open.


### Helper invocation side-effect policy on the shared CUs, 2026-10-19

The [helper-invocation checkpoint](performance/mobile-helper-invocations-2026-10-19.json)
adds trusted launch metadata for a workgroup helper bitmap and a bounded private
scratch/payload range. Dispatch splits the bitmap by logical warp and masks tail
bits; physical warp initialization snapshots the resulting policy in `GpuCore`.
Helpers remain active for normal instructions, loads and existing quad exchange.
Execution masks and register/derivative participation are not replaced with
coverage masks. Memory instructions snapshot policy before offering a request.

Vector stores filter helper lanes after effective-address formation and before
local/global routing, coalescing and translation. An all-suppressed store retires
without an external request. Returned fault masks include only lanes sent to
memory. Scalar integer/FP stores issue when the instruction has an active real
invocation; helper-only paths suppress external stores before alignment or
translation faults. Whole accesses inside the trusted private range can still
write spills and per-invocation outputs. Overflowed ranges and ranges overlapping
the CU shared-memory window grant no private exception. Helper-only local/global
scalar AMOs retire without a request and return an internal zero value. Real
invocations retain the existing AMO behavior; this is not a new per-lane atomic
ISA or a complete graphics atomic-resource implementation.

The private range must come from validated owner storage and must not alias
externally visible buffer/image/framebuffer resources. It is an internal spill/
payload exception, not a general writable-resource binding. Invocation-private
addressing remains a compiler/owner contract. Native fragment
workers derive helper masks from their covered-lane records and snapshot the
existing nine-array kernarg prefix as private state. Serial and parallel launch
paths carry that policy without changing kernarg offsets. Host compute queues
clear graphics invocation metadata, including raw compute entries to the shared
CU dispatcher. Host MMIO registers, instruction encodings, shader register
preloads and the sibling compiler's ISA contract remain unchanged.

This implements the hardware plan's separation of helper execution and memory
side effects. The later [GLSL ES 3.20 helper-invocation rules](https://registry.khronos.org/OpenGL/specs/es/3.2/GLSL_ES_Specification_3.20.html)
also describe suppressed image/buffer stores and atomics; no ES 3.20 capability
or conformance claim follows from this checkpoint. Dynamic discard/demotion,
shader-visible helper built-ins, resource tables and broader divergent-control/
fault semantics remain open.

Policy tests cover scalar/FP private boundaries, suppressed misaligned external
stores, normal helper loads, active-path masks, indexed/strided/byte/halfword
vector stores, filtered faults, private/shared overlap, wrapped ranges, snapshots
and held replies. They exercise all nine scalar AMO operations on local/global
paths, tail warps, a non-power-of-two warp count and the default 128-bit helper
bitmap. Actual four-lane/two-warp CUs execute mixed, all-helper, whole-helper-warp
and helper-free kernels in both SRAM and memory-backed private modes. Every
helper contributes to existing quad exchange and writes its private outputs,
while external vector stores, scalar/FP stores and AMOs follow the real masks.
Kernel reuse, held completion and absence of local-payload VM leakage are checked.
A fragment producer check overlaps two batches with masks `0x2a`/`0xa5`, verifies
288-byte private prefixes and bank snapshots, and retires nine covered outputs.
Native shared-CU payload tests retain cancellation/fault/release ownership; host
compute/graphics dispatcher tests check trusted policy routing and compute
sanitization.

All 42 frozen core/dispatch/memory checks and 28 native fragment/routing checks
pass, with a 544-source archive. Default CU/renderer emissions audit 271 and
205 files and verify all four worker policy connections. The native checks used
unchanged hardware/test sources; the later archive adds vector policy cases
covered by the 42-check run. The
default CU has eight per-warp policy records of 16 helper bits plus two 32-bit
bounds (**640 register bits**), with dispatch/queue/instruction snapshots and
combinational checks additional. No SRAM array, arithmetic lane or private CU
is added for the policy. The programmable renderer emission retains four
fragment workers using the shared pool. These are RTL audits; synthesis PPA,
production register/timing/power budgets and sustained rates remain open.
PPA screening remains synthesis only.

General FP32 fragment-input shader delivery, native bin/geometry/fragment
ownership, physical allocation/alias validation, general image/buffer resources,
point/line/guard-band rasterization, qualifier and built-in closure, dynamic
invocation termination, precision/invariance, queries/transform feedback and
remaining H0-H8 requirements remain open.

### General FP32 fragment-input shader client, 2026-10-19

The [fragment shader stage checkpoint](performance/mobile-fragment-shader-stage-2026-10-19.json)
adds a generic shared-CU consumer for the complete FP32 quad stream. Its internal
version-1 SoA ABI carries `fragCoord.xyzw`, front/helper/perspective/sample flags,
original sample coverage and up to 64 raw varying components. Pixel-center X/Y
convert signed raster coordinates to FP32 with RNE. Varying words retain their
bits, including integer encodings and NaN payloads. Four-lane quads remain intact
through batches, physical warp allocation, readback and ordered exports. This
interface preserves the existing shader ISA/register preloads and the native
nine-array fragment ABI.

A draw selects zero to four raw RGBA32 output locations, with float/uint/sint
location metadata, per-lane location masks, emit/discard and optional depth
override. Color words start at zero; depth and emit/mask defaults come from the
source quad. Disabled locations remain zero. Covered lanes with reserved control
bits, an enabled mask outside the draw mask, a forbidden depth override or a live
NaN override fault the whole unpublished quad. Valid override depth clamps to
`[0,1]`; source depth otherwise survives. Helpers retain shader participation and
private outputs while exporting no coverage or color mask. These typed raw
locations are an internal producer interface; attachment format conversion,
blending and native MRT ownership remain separate requirements.

The producer validates the descriptor, scratch allocation/view bounds, alignment,
shared-memory overlap, input identity/layout, TL/TR/BL/BR coordinates, coverage,
covered-lane perspective state and depth. A trusted non-aliasing private scratch
allocation remains an enclosing owner contract. Each batch snapshots the helper
bitmap and private prefix bounds into the existing shared-CU launch metadata.
Local allocation uses the existing payload service; full and oversize decisions
spill explicitly into bounded memory-backed scratch. Every batch invalidates the
shared L1 state before launch. Uniforms follow the full prefix and survive output
initialization. No private CU or payload pool is embedded in this stage.

The stream has explicit source EOF, carrying identity, status and the number of
accepted quads. EOF may coincide with the final input and is required for empty,
faulted and canceled streams. `cancelSource` asks the enclosing source to drain.
Accepted launch and memory offers remain irrevocable; held output quads retain
their values and lease. Completion waits for source EOF, accepted shader/texture/
memory work, held exports and payload release. Its counters and attributed fault
record remain immutable while held, and retain VM-context ownership through the
completion handshake.

Storage uses narrow synchronous SRAM rows: default 128-lane metadata occupies two
256x64 macros, and the sixteen-lane transpose window occupies one, totaling
**three macros / 6 KiB** outside the existing pool. Full 64-component/four-color
payloads need **45,056 bytes**. The default 64 KiB pool's existing 4,608-byte maximum
slot is unchanged, so this case explicitly oversize-spills; a configured
45,056-byte slot also executes locally. Smaller and partial batches retain the
physical CU-capacity array stride. A non-power-of-two twelve-lane check exercises
split-line readback.

Qualification uses two actual shared CUs and covers local/global multi-batch
streams, raw integer/NaN varyings, front flags, negative centers, whole-helper
quads/batches, masks, discard, depth clamps, uniform tails and held exports. It
also checks descriptor/input/EOF rejection, staging/shader/export faults, pool
pressure, canceled launch/export/staging offers, explicit EOF drain, empty and
depth-only draws, texture sampling/fault/reuse, and all 64 varying components
across 128 physical lanes in local and oversize-spilled modes. Upstream
raster/interpolation and helper policy/CU checks run on the same frozen source.
All **31 checks** pass on a frozen **546-source** archive, including **11 new
fragment checks**. The default RTL audit covers **39 emitted files**. RTL emission and SRAM counts are not synthesis PPA; PPA screening
remains synthesis only.

Composition with the generic vertex/raster owner, native bin/host allocation and
typed attachment writes remains open. This client does not close general image/
buffer/texture binding, dynamic helper/discard behavior, complete built-ins and
qualifiers, point/line/guard-band rasterization, precision/invariance, queries/
transform feedback or the remaining H0-H8 requirements. Hardware development is
still in progress.

### Shared-CU vertex-to-fragment draw ownership, 2026-10-19

The [composed draw checkpoint](performance/mobile-vertex-fragment-pipeline-2026-10-19.json)
connects generic GPU indexed/instanced vertex execution, primitive assembly,
clipping, viewport, owned triangle raster and interpolation directly to the
FP32 fragment shader client. Both shader stages use one enclosing physical CU
pool. Their internal task tags, texture traffic and payload bindings pass through
one two-client router; neither stage embeds a private CU or payload pool. Ordered
exports retain the raw typed location interface, identity, primitive order,
clipped part, facing, coverage, helpers and depth.

A version-1 draw snapshots both descriptors and both payload-context inputs at
outer acceptance. Before offering either child draw, admission checks fragment
layout/bounds, identity, varying count, sample mode and payload-context agreement.
The declared scratch views, including uniform tails, must not overlap each
other. Fragment scratch must also avoid enabled vertex binding and index views.
Both children retain their own descriptor/resource checks. This checks declared
virtual views; translation pinning, physical alias detection and non-aliasing
with general resources remain enclosing allocation-owner requirements.

Each child owns an independent staging/payload port and payload owner. The router
forwards four prospective/queued task pins to the enclosing pool. Eight public
memory transaction IDs retain the source client and its four local IDs; a queued
arbiter holds public offers and an outstanding counter gates completion. Vertex
and fragment prefixes and uniform tails remain separate. The fragment descriptor
checks are shared with the standalone stage so admission and execution use one
layout/bounds definition. ISA encodings, register preloads, host ABI and both
legacy shader kernarg layouts remain unchanged.

Source completion is the fragment client's explicit EOF, with identity, status
and the exact number of accepted quads. A fragment failure cancels upstream
geometry/raster work; the source still drains offered quads and produces EOF.
A source failure reaches the fragment stage through EOF. Fragment completion
asserts agreement with source input and downstream export counts. The draw
summary retains both nested results, separates preflight/source/fragment faults
and preserves source memory fault addresses and nested numeric attribution.
Completion waits for both children, task/invalidation routing, queued memory and
accepted replies. Held final exports retain payload and actual VM context, and
held draw completion remains immutable until accepted.

Cancellation is remembered across child admission, including a one-cycle pulse
coincident with child draw acceptance. Accepted queued launches, prospective
payload pins and memory offers drain before completion. A local reservation
cannot recycle while its task remains queued. A full pool does not create an
allocation dependency cycle: fragment work can use bounded global spill while a
vertex release remains backpressured. A one-slot actual-CU case exercises that
progress path explicitly, then releases the owner and completes the draw.

Actual two-CU checks compare indexed/instanced vertex-to-fragment results with the
existing rational clipping/viewport/interpolation/raster reference. Local and
memory-backed modes each exercise 68 quads across 1x and 4x sampling, varying W,
clipped parts, flat raw values, three raw typed locations, helper/coverage masks,
uniform tails and held final exports. Additional cases cover cross-stage
preflight/alias errors before access, empty draws, empty viewports with vertex
execution, source/fragment memory and shader faults, numeric source attribution,
pulsed/queued-launch/final-export cancellation, healthy reuse and distinct
vertex/fragment samplers with descriptor snapshots. Standalone fragment and
shared task/payload-router regressions qualify the same frozen source. All
**27 checks** pass on a frozen **548-source** archive, including **seven new
composed checks**. The default RTL audit covers **136 emitted files**.

The default emitted owner composes the prior 28 KiB vertex/raster storage and
6 KiB fragment metadata/transpose storage: **17 characterized SRAM macros /
34 KiB**, outside the existing shared payload pool. It retains the three existing
clip/viewport/interpolation arithmetic pairs. Default interfaces carry 64 raw
varying components and four typed raw output locations; actual composed shader
checks use a bounded representative profile. These are RTL/storage audits, not
synthesis PPA. PPA screening remains synthesis only.

Native bin/host draw admission, persistent geometry/state publication and typed
attachment consumption remain open. The composed internal stream does not close
API MRT/format semantics, physical allocation/alias validation, general resource
binding, point/line/guard-band rasterization, complete built-ins/qualifiers,
precision/invariance, queries/transform feedback or remaining H0-H8 gates.

### Resident typed MRT color storage, 2026-10-19

The [typed color checkpoint](performance/mobile-typed-tile-colors-2026-10-19.json)
adds an explicit format encoder and a resident four-location color service.
The format inventory follows the 28 color-renderbuffer formats in
[GLES 3.0.6 section 3.8.3.1/table 3.13](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf).
Eight additional R/RG/RGB/RGBA binary16/binary32 encodings provide internal
floating storage; they do not advertise API renderability or conformance. The
machine evidence records all 36 internal format IDs. Legacy packed-color formats,
shader ABI, native pixel backend and CU ISA remain unchanged.

Inputs retain four raw RGBA32 locations and float/uint/sint location types. The
late visibility owner supplies per-lane sample masks that must be subsets of
shader coverage. The service validates quad identity/layout, coordinates, types,
helper/discard restrictions and selected sample bounds before updating storage.
Attachment location masks, RGBA component masks and sample coverage have separate
units. Component bit zero means R here; the legacy packed-color mask starts at
alpha. Helpers, discarded lanes, rejected samples and disabled locations cannot
change color data.

Byte/half/word formats store R at the lowest address. Packed 565/4444/5551 use
conventional high-R positions; 10:10:10:2 uses low R. Narrow integers saturate.
UNORM conversion multiplies the exact FP32 significand by the component maximum
and rounds once to nearest/even. Binary16 uses nearest/even and gradual underflow;
binary32 preserves raw bits. UNORM/sRGB NaNs map to zero, while binary16 NaNs
canonicalize. These are explicit hardware numeric policies. A shared sRGB encoder
performs eight threshold-search steps per selected RGB component and leaves alpha
as UNORM8. Its 255 fixed FP32 thresholds have a reproducible 100-digit decimal
generator; RTL elaboration does not depend on host `libm`.

A version-1 descriptor snapshots tile identity/origin, 8x8 or 16x16 storage class,
active extent, 1x/2x/4x sampling and four disjoint format/view spans. Layout checks
include actual component bytes and sample count. A reserved prefix protects
other owner data such as depth/stencil. The default slot has **32 KiB in sixteen
256x64 SRAM macros**, plus **8,192 valid/dirty bitmap register bits** and additional
control/descriptor registers. Logical slot capacities from 1 KiB through 64 KiB
retain macro minimums. Four RGBA16F colors plus depth at 16x16/4x exceed the 32 KiB
slot; the checked smaller tile fits. Wider formats are not budgeted as one packed
32-bit color per sample.

Clear/discard tags supply nonresident typed defaults. Three-, six- and twelve-byte
pixels repeat correctly across word boundaries. Load-start misses issue one
bounded 64-bit refill and retain it through backpressure, faults and cancellation.
Once materialized, valid words preserve resident updates and avoid repeat refills.
Maintenance reads report availability/dirty state; maintenance writes are explicit
dirty mutations. Reserved words remain available to their enclosing owner.
External backing views, permission/translation checks, transfers, whole-clear
export and publication remain that owner's responsibilities.

One quad serializes its lane/sample/location read/modify/write operations through
completion, preserving packed neighbors and unselected component bits. Completion
reports touched samples, completed sample/location records, physical word writes
and written mask bits. A failed or canceled pass can retain a counted committed prefix
and must not publish a successful image. Offered codec/refill work and already
offered quad/maintenance replies drain before rebinding. A final input offered
with end is accepted first; end waits for its completion. Local pass completion
is not an external-memory visibility fence.

All **16 checks** pass on a frozen **554-source** archive: eight new numeric/storage
checks and eight legacy pixel regressions. Numeric checks cover 2,637 format/mask
vectors, 765 sRGB transition-neighbor cases and 76,744 binary16 cases including
all 65,536 encodings. Storage checks cover all 36 formats in nine four-MRT groups,
MSAA visibility, packed neighbors/masks, protected prefix, virtual clear/discard,
refill/reuse/fault/cancel with a counted one-word partial prefix, invalid
layouts/quads, end/input simultaneity, held
results and 1 KiB/64 KiB capacity boundaries. Boundary writes reach the highest
logical word and exercise 128 writes, 8,192 written mask bits, 64 location records and
16 touched samples in one quad. The default encoder/store RTL audits
cover six/nine files. Existing behavioral/macro RGBA8/D24S8 regression checks retain
four complete sample updates/cycle; this new serialized service makes no such
throughput claim. These are RTL/storage audits; PPA remains synthesis only.

Connecting general shader outputs, late depth/stencil qualification, format-aware
blending and this storage service under a native tile/pass owner remains open.
Dithering, resolve/transfer/publication, backing resource/alias validation, native
bin/host geometry, remaining format semantics and H0-H8 qualification are still
required. This component is not native typed framebuffer or API closure.

### Shared-slot late depth/stencil and typed MRT, 2026-10-19

The [late pixel checkpoint](performance/mobile-typed-late-pixels-2026-10-19.json)
composes per-sample depth/stencil qualification with the resident typed MRT color
service. It accepts final shader depth and pre-test sample masks, then submits only
passing samples to color storage. Quad and attachment validation precede depth or
stencil effects. Helper/discard restrictions and color type mismatches cannot leave
an accidental depth update.

The internal layouts cover D16, D24, D32F, D24S8, D32FS8, stencil-only S8 and a
separate S8 plane. Missing attachments pass without writes. Front/back stencil
functions, reference/read/write masks and all eight operations are captured per
quad. Stencil rejection skips a separate depth read/refill. Disabled depth testing
also disables depth writes, following
[GLES 3.0.6 sections 4.1.4–4.1.5](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf).
Fixed-depth encoding rounds the exact FP32 value once to nearest/even at 16 or 24
bits; floating depth preserves signed zero and handles stored NaNs as unordered.
Selected source NaNs are rejected before effects when depth testing is active.
D24 and D32FS8 padding survives updates and load materialization.

Depth/stencil shares the existing **32 KiB slot**, sixteen characterized 256x64
SRAM macros and **8,192 valid/dirty bitmap bits** with color. Aligned disjoint
planes must fit the reserved prefix. Cold load fills initialize a full absent word
without making it dirty; resident or partial clean fills are rejected. Clear and
discard materialization and actual mutations remain dirty. External maintenance
and internal sample operations have exclusive ownership. Depth/stencil counters
separate initialization fills from update writes; read counts describe successful
maintenance read commands, not every physical SRAM access.

All **24 checks** pass against the frozen **558-source** archive: two depth/stencil
arithmetic checks, five combined backend checks, seven color storage checks, two
color encoding checks and eight legacy pixel regressions. Coverage includes 20,475
depth conversion cases, 8,192 stencil cases, 1,296 comparisons, seven attachment
layouts, 64 changing-state overlap quads, stencil-first cold-read avoidance,
read-only clean loads, padding, invalid preflight, faulted refills and cancellation
of held depth/color refills. Offered replies remain stable and all child memory,
refill and completion work drains before reuse. A failed pass can retain a counted
committed prefix and cannot publish success.

One quad serializes its sample tests and color writes. Local completion is not
external image publication. Format-aware blending, dithering, resolve, backing
transfers, native host/bin/pass ownership and connection to the general shader
pipeline remain open. This checkpoint supplies component correctness and an RTL
storage audit; it supplies no new throughput or PPA result. PPA acceptance remains
**synthesis only**, and the wider H0–H8 development goal remains open.

### Shared-CU shaders in a resident typed tile pass, 2026-10-19

The [typed tile render checkpoint](performance/mobile-typed-tile-render-2026-10-19.json)
connects GPU vertex fetching, shared-CU vertex execution, clipping/viewport,
triangle raster/interpolation and shared-CU fragment execution to late typed
sample tests and four resident color views. A pass binds one tile; multiple
ordered draws share its attachment contents until explicit end or failure.
Draw completion waits for every accepted pixel result, so a younger draw cannot
overtake a held depth/stencil/color update. Empty shader sources and empty scissors
retire without resetting attachments.

Pass identity, attachment layout and payload contexts are captured at begin.
Each draw snapshots its shader and front/back test state. Admission checks pass
identity, sample count, selected color types and the effective screen/scissor
rectangle before shader access. Raster zero/oversize screen dimensions use the
same elaboration-limit fallback as the existing rasterizer when checking bounds.
A nonempty effective rectangle must fit inside the bound tile; a scissor can
restrict a larger screen to that tile. This owner handles one tile and does not
replace the native bin scheduler or expose a framebuffer address ABI.

Final shader coverage, discard and optional depth override feed the pixel owner.
The pixel owner applies late stencil/depth tests before typed color storage.
A retained quad packet carries the accepted draw state through downstream
backpressure. Captured packets remain irrevocable across cancellation; further
source exports drain with an explicit dropped count. Pixel failure cancels the
shader source, shader failure cancels the pixel owner, and first fault details
survive the resulting cancellation. Held pixel, draw and pass completions are
immutable. Child memory/refill traffic, payload pins and queued task launches
must drain before pass completion. The enclosing VM context remains busy through
held completions and between draws, even after the payload pool becomes empty.

All **38 checks** pass on a frozen **560-source** archive, including seven new
integration checks. The record covers real two-CU execution with local and global payloads,
indexed/instanced vertices and four mixed normalized/integer/floating color targets
at 1x and 4x. Repeated draws exercise retained depth, rejection, shader depth
override and discard, and compare final attachment words with independent raster,
interpolation, quantization and byte-level models. Clear-start draws require no
attachment refills. Other checks cover load refill, reverse pixel fault
propagation, source faults, empty work, tile/scissor admission, simultaneous last
draw/end, pulsed cancellation and a one-slot payload pool where fragment spilling
progresses while a vertex release is held.

The default owner retains one existing vertex/fragment pipeline and one typed
pixel backend, with a shared external CU/payload interface. Attachment data remains
32 KiB, with 34 KiB of existing geometry/fragment private SRAM: **33 characterized
256x64 macros, 66 KiB total**. The 25 logical SRAM wrappers can span multiple
physical macros. The **149-file** RTL audit retains the existing three fixed-function
FMA/divider pairs for clipping, viewport and interpolation. It adds no shader
arithmetic lane or duplicate CU/payload pool. Packet, descriptor, bitmap and control
registers are additional. These structural counts are not synthesis PPA
measurements; PPA screening remains synthesis only.

Native host/bin/pass descriptors, backing attachment transfers and publication,
format-aware blending/dithering/resolve, resource/alias validation, broader
primitive/texture support and the original H0–H8 gates remain open. This internal
composition supplies neither API closure nor a new throughput or frame-rate claim.

### Resident format-aware blending, 2026-10-19

The [typed blend checkpoint](performance/mobile-typed-blending-2026-10-19.json)
adds a destination decoder and serialized blend service to the existing resident
color owner. General shared-CU draws capture blend state with their shader and
late-test descriptors. Passing samples gather their own destination record,
blend and repack before the next sample or draw can overtake them. Depth/stencil
rejection, helper/discard masks and disabled locations avoid this work.

Version-1 blend state supplies separate RGB/alpha source and destination factors,
separate equations and four constant components. All 15 factors and five equations
are represented. Integer targets bypass blending and retain typed saturation.
Normalized targets clamp source/constant/factors to [0,1], decode destination
components at their stored precision, apply the equations and clamp on storage.
MIN/MAX ignore factors. Missing destination alpha is one; absent RGB is zero.
An sRGB destination is decoded to linear RGB before blending, with alpha left
linear; the existing encoder converts the result back to sRGB. These contracts
follow [GLES 3.0.6 sections 4.1.7–4.1.8](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf).
The additional binary16/binary32 formats support an explicit internal floating
blend policy; they do not advertise base-API renderability or extension support.

UNORM decoding uses the repeating binary expansion of n/(2^b-1). Forty repeated
fraction bits suffice for exact FP32 nearest/even rounding at every supported
component width, with no divide unit or normalized lookup table. Binary16 expands
exactly, including gradual values and signed zero; signed/unsigned integers retain
their types. A fixed 256-entry FP32 inverse-sRGB table is generated with 100-digit
decimal arithmetic and nearest/even neighbor selection. Its checked-in bytes are
reproducible and RTL elaboration uses no host transfer-function calculation.

One existing FP32 FMA implementation supplies the blend arithmetic. Products and
sum round separately to nearest/even; zero/one factor products retain their exact
identities. Floating targets retain range, signed zeros and gradual values. The
internal MIN/MAX policy selects a numeric operand over a NaN and orders signed
zeros; arithmetic NaNs canonicalize. Blend arithmetic flags remain diagnostic.
This is serialized fixed-function work, not an extra shader CU or a throughput
claim. Destination gathering and masked word writes preserve packed neighbors,
per-sample values and untouched component bits without adding attachment SRAM.

Shared preflight rejects malformed enabled blend state before depth/stencil
updates; the composed draw owner rejects it before shader admission. State stays
snapshotted through codec, arithmetic and refill backpressure. Cancellation drains
an already offered blend request, accepted arithmetic and cold gathers; it cannot
flush an irrevocable request or publish successful pass completion after failure.
The backing-memory publication boundary remains external.

All **44 checks** pass on a frozen **565-source** archive. Regression evidence
includes exhaustive binary16/UNORM decoding, all sRGB codes,
all factor pairs across normalized/sRGB/floating targets, separate alpha state,
nonfinite/signed-zero/gradual values, all 36 resident formats with packed masks,
changing constants and integer bypass, canceled arithmetic and cold refills,
invalid preflight and exact shared-CU draw results at 1x/4x. The source archive,
qualified logs, ROM check and emitted decoder/blender/render hierarchy are frozen
in the evidence record. The decoder/blender/composed render emissions contain
**6/19/154 files**. The composed owner retains **33 SRAM macros, 66 KiB**, including
the 32 KiB attachment slot and 8,192 attachment bitmap register bits. It adds
**one fixed-function FMA instance**, with no additional divider, CU or payload
pool; the three prior fixed-function FMA/divider pairs remain. Packet/control
registers and inverse-sRGB constants are additional. These counts are structural
audits; PPA evaluation remains synthesis only.

Native host/bin/pass integration, backing resource/alias validation, attachment
loads/stores/publication, dither/resolve, complete primitive/texture/resource
semantics and the original H0–H8 qualification gates remain open. This checkpoint
closes an internal blending gap without claiming native framebuffer/API closure.

### Typed attachment backing and publication, 2026-10-19

The [typed backing checkpoint](performance/mobile-typed-image-publication-2026-10-19.json)
adds bounded physical image views and a gather/scatter owner around the resident
shared-CU renderer. Four color views, primary depth/stencil and optional separate
stencil share the existing slot. Each backing view describes a selected single
image layer with physical base, exclusive view/allocation limits, image extent,
row pitch, trusted read/write permissions and store intent. Allocation endpoints
are 64-byte aligned; view bases and row pitches may be unaligned. Validation
checks the complete pitched image footprint, tile rectangle, arithmetic overflow,
permissions and overlapping stored-view envelopes before shader or memory work.
Immutable load sources can alias; a single stored view may overlap a read-only
source because all draw loads precede export. Unused clear/discard views with no
store require no backing allocation. These are internal physical views; VM
translation, permission provenance and native resource-table admission remain
separate requirements.

Cold 64-bit slot words gather only active tile bytes from bounded 64-byte physical
reads, with a one-line register cache. Bytes outside a partial tile are zero-filled
without touching the backing image. Cache identity resets on every binding.
Store/export walks active rows and selected views, reads resident or virtual
color defaults, and supplies untouched depth/stencil clear/discard defaults.
Clear/discard-start views export their deterministic active contents; load-start
views export dirty words and leave clean/unavailable words unchanged. Masked line
stores preserve image pixels outside the tile, row-pitch gaps and allocation guard
bytes. Packed 3/6/12-byte formats, separate stencil and combined depth/stencil keep
their established storage layouts and padding policies. Resolve and dithering
remain separate work.

The outer pass owner accepts several ordered draws with no intermediate attachment
stores. It intercepts successful end, exports the selected views, waits for every
physical acknowledgement, then forwards local renderer end and retires the pass.
A tagged arbiter shares the existing physical line interface with shader staging;
attachment requests use the existing uncached coherent-L2 store contract. A
cached L2 consumer can observe new data after completion even when a formerly
cached line must refill. Store acknowledgement is distinct from CPU cache
coherence and does not imply a CPU visibility fence.

Failed or canceled passes stop new work, drain accepted/refill/private/end offers
and retain counted successful store prefixes. Invalid slot refills issue no
physical reads; failed stores cannot advertise successful publication. Direct
and payload-pool cancellation retain already offered local end/maintenance
commands before reaching the renderer. The VM/context and immutable pass result
remain owned until all shader/payload/memory traffic and completion handshakes
drain. Useful load bytes count successful refill replies; issued and acknowledged
store bytes and physical read/write request counts have separate counters.

All **40 checks** pass on a frozen **569-source** archive: ten new transfer/image
checks and thirty numeric/pixel/storage regressions. Transfer checks cover all
36 colors, seven depth/stencil layouts, clear/load/discard,
5x3 edges, odd pitches, unaligned bases, physical aliases, permissions, stale-cache
rebinding, the last byte of a 4 GiB allocation, invalid slot refills and cancellation
or read/store faults. Real two-CU local/global tests render blended indexed/instanced
geometry into four mixed typed views at 1x/4x and compare exact backing bytes,
including untouched image and allocation bytes. They verify no intermediate
stores, warm/cold cached-consumer visibility, held final store acknowledgements,
pre-admission cancellation, physical faults and cancellation timing around the
forwarded local end.

The source snapshots, qualification logs and emitted transfer/composed hierarchy
are frozen in the evidence record. The transfer/composed render audits cover
**7/164 emitted files**. SRAM remains **33 characterized macros, 66 KiB**, including
32 KiB of attachment data and 8,192 attachment bitmap register bits. A 512-bit
load-cache register and other line/request/descriptor/control registers are
additional. The existing four fixed-function FMA and three divider instances
remain; the transfer adds no SRAM, shader CU, payload pool or floating arithmetic
lane. These
are functional and structural checks; PPA evaluation remains synthesis only.
Native bin/host integration, virtual image/resource descriptors and general
permission/alias ownership, texture dependency closure, dither/resolve, workload
and rate qualification and the remaining H0–H8 gates remain open.

### Typed multisample resolve and physical publication, 2026-10-19

The [typed resolve checkpoint](performance/mobile-typed-multisample-resolve-2026-10-19.json)
extends the same shared-CU image pass with optional single-sample destinations
for four colors, primary depth/stencil and separate stencil. Format and pixel
coordinates come from the source; the destination is implicitly the same format
at one sample per pixel. Its pitched image, active rectangle, allocation bounds
and trusted write permission are validated before shader or memory admission.
Each enabled destination requires store intent and must be disjoint from every
active source backing and other resolve output envelope. Source store intent is
independent: a multisample source can be discarded while its resolve is published.
Clear/discard sources with no source store need no source backing metadata.

The policy follows the choices allowed by
[GLES 3.0.6 section 4.3.3](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf).
One-sample copies preserve raw records. Integer colors and depth/stencil select
sample zero; combined depth/stencil retains both components and record padding.
Two/four-sample normalized and floating colors average decoded FP32 components.
sRGB RGB is decoded to linear light before averaging and encoded on output;
alpha follows UNORM rules. The internal eight floating color encodings remain
hardware capabilities rather than an API renderability claim.

A serialized resolve service accumulates finite components exactly in signed
281-bit units of 2^-149, divides by the sample count and rounds once to FP32
before the existing format encoder. This prevents intermediate floating addition
overflow, premature subnormal truncation and loss from cancellation. All-negative
zero resolves to negative zero; mixed exact zero resolves to positive zero.
Nonzero negative values rounded to zero retain their sign. Arithmetic NaNs and
mixed infinities canonicalize to NaN; otherwise infinity retains its sign. Raw
one-sample copies and selected depth/stencil records preserve their stored bits.
General depth-NaN/API rules remain unqualified.

After selected source stores, resolve walks the active rectangle and reads
resident or virtual slot words through the existing maintenance port. Untouched
depth/stencil clear/discard words use the established defaults. Cold load words
gather bounded active image bytes through the physical line cache without
installing or dirtying slot storage. A tagged 64-bit register retains a source
word across packed record boundaries. Admission rejects an earlier source store
aliasing a different cold load-start resolve source, preserving its backing
snapshot; existing aliases without late resolve retain their preceding contract.

Masked resolved stores split 8-byte staging chunks and 64-byte line crossings,
preserving pixels outside the tile, row-pitch gaps and allocation guards. The
outer owner waits for resolve arithmetic, private offers and all physical
acknowledgements before local renderer end, pass completion or VM release.
Fault/cancel drains irrevocable traffic and retains a counted committed prefix.
Issued/acknowledged source and resolve bytes have separate counters; completed
resolve pixels count on their final successful acknowledgement, including after
cancellation. Useful load bytes include completed cold resolve gathers; aborted
assemblies do not count and repeated gathers may count again.

All **47 checks** pass on the frozen **571-source** archive: two numeric resolve
checks, ten transfer checks, five image-pipeline checks and thirty numeric/pixel/
storage regressions. They cover numeric extremes, all formats/sample modes and packed transfer bytes,
invalid bounds/aliases, cancellation/fault drain, the highest physical byte and
actual local/global shared-CU draws with cached consumer visibility. The design
emits **7/9/165 files** for the standalone resolve/transfer/composed owner. SRAM
remains **33 characterized macros, 66 KiB**, including the 32 KiB attachment slot;
the existing four fixed-function FMA and three divider instances remain. It
adds one serialized integer resolve service and a format encoder, including
accumulator, sample/word/result, descriptor and control registers and conversion
constants. It adds no SRAM macro, floating arithmetic lane, shader CU or payload
pool. These structural checks do not constitute PPA; evaluation remains
**synthesis only**.

Native bin/host scheduling, translated image/resource admission and general
shader-resource alias ownership, texture/CPU dependency closure, dithering,
remaining primitive/texture/API semantics, workload/rate qualification,
synthesis PPA and the original H0–H8 gates remain open. This closes the typed
pass's internal resolve/publication gap without claiming full hardware completion.

### Point and line rasterization through shared-CU image passes, 2026-10-19

The [point/line checkpoint](performance/mobile-point-line-raster-2026-10-19.json)
replaces the triangle-only draw consumer with one primitive raster owner. Points,
line lists/loops/strips and triangles use the existing assembled/clipped/window
records. The owner selects triangle or point/line coverage and shares one scalar
interpolation engine; it does not instantiate a second shader CU or interpolation
FMA/divider pair. The triangle-only wrapper retains its constructor and triangle-only admission
policy. Native geometry/bin/host admission remains separate work.

Point coverage uses a square centered on the subpixel window position, with
independent coverage bits at the existing 1x/2x/4x sample locations. Draw point
size and line width are FP32, clamped to the internal supported range [1,64]; zero
in the internal draw state selects the compatible default of one. A shader-written
point size takes precedence. Nonpositive/NaN shader sizes use a deterministic
minimum-size policy for their undefined case; positive infinity clamps to the
maximum. Point and line fragments are front-facing and ignore polygon culling.
The coverage engine applies runtime screen/scissor bounds and preserves every
helper lane in each nonempty 2x2 quad.

Basic lines use the diamond-exit rule with symbolic epsilon and epsilon-squared
endpoint perturbations. Rational interval comparisons retain the boundary terms
rather than choosing a finite perturbation that could alter a difficult endpoint.
The final endpoint is excluded, so connected segments do not shade it twice.
Aliased widths round to nearest/even integer; wide lines offset by (w-1)/2 in the
minor direction and replicate the resulting coverage. Multisample lines use the
finite butt rectangle, tested by center projection and squared cross distance,
with per-sample masks. Integer coverage arithmetic is serialized, with a retained
quad offer and no private SRAM. It is a correctness profile, not the proposed
one-quad-per-cycle throughput qualification.

Line interpolation uses the pixel-center projection onto the original segment,
with two endpoint weights and a zero third weight. Reciprocal W and smooth data
remain perspective-correct; depth is linear in screen space and flat components
retain the provoking value. Unused vertex records cannot inject NaNs into the
weighted sum. Points copy their raw vertex values, including flat/NaN payloads,
and evaluate gl_PointCoord from the unrounded viewport position. A small
fractional residual is combined with the exact integer screen-center difference,
keeping a large integer viewport origin out of FP32 cancellation. Point-coordinate
arithmetic reuses the same scalar service and retains accepted work on cancel.

Two optional internal shader ABI versions make these values executable on the
shared CUs. Version 1 keeps all existing array offsets and uniform locations.
Vertex ABI version 2 appends one FP32 point-size array after the position and
varying outputs; its prefix includes that array. Fragment ABI version 2 appends
pointCoord.xy after the varying input arrays and puts primitive kind in flag
bits 6:5. Fragment outputs and uniforms follow the extended input prefix. The
existing flag bits, typed color/depth/control outputs and legacy native vertex/
fragment ABIs are unchanged. No CU instruction encoding changes. A version switch
changes the uniform prefix, so resource bindings must describe the corresponding
layout; the tests use independent immutable uniform views for the two versions.

Signed64 bounds rejection precedes coordinate narrowing. Unsafe overlapping
coordinates or unrepresentable interpolation weights produce a numeric fault
instead of wrapping. Invalid state/layouts produce no partial raster output.
The enclosing vertex/fragment/image owners retain primitive order, payload and VM
ownership, irrevocable quad offers and final completion until coverage, scalar
math, shader exports and physical image acknowledgements drain. The complete
path supports late depth/stencil, typed blending and independently selected
source stores/resolves with cached GPU visibility.

Qualification covers **93 distinct checks across 14 suites** on the frozen
**575-source** archive. The assembly oracle now includes the 33 added point-size
and flag bits and nonzero metadata values. Its six checks pass after a test-only
correction; 87 passing checks are reused with identical production and test
source hashes. Both immutable source archives/logs and the exact reuse proof are
recorded in the checkpoint. The checks include independent arbitrary-precision diamond/rectangle coverage,
fractional point sizes, connected endpoints, helper interpolation, unrounded point
coordinates, exact large origins, shader size override, cancellation and numeric
faults. Real two-CU local/global tests execute ABI v2 shaders and publish guarded
pitched typed images at 1x/4x, including resolve-only publication and cached reads.
Broader triangle, assembly/clipping/viewport, shader/payload and image ownership
regressions qualify the extended internal records and version-1 behavior.

The coverage/primitive/composed image emissions contain **6/43/166 files**. The
composed owner retains **33 characterized SRAM macros, 66 KiB**, including the
32 KiB attachment slot, with four fixed-function FMA and three divider instances.
The additional integer coverage service and expanded records/state add logic and
registers; no SRAM macro, floating arithmetic lane, shader CU or payload pool is
added. These are structural measurements, not synthesis PPA.

This checkpoint does not establish unrepresentable guard-band reconstruction,
complete invariance/precision, per-sample depth and centroid behavior, native
resource/host/bin binding,
post-transform reuse, queries/transform feedback, broader texture semantics or
workload/rate closure. PPA remains **synthesis only**; emitted hierarchy/storage
counts are not PPA results. The original H0–H8 development goal remains open.

## Covered-sample FP32 default depth through typed image publication

The [sample-depth checkpoint](performance/mobile-fp32-sample-depth-2026-10-19.json)
extends the general point/line/triangle raster and shared-CU fragment path with
independent default depths for every covered sample. At 1x the depth remains the
existing center result. At 2x/4x triangle planes and line projections are evaluated
at the established sample positions; point depth remains constant. The existing
scalar arithmetic service performs the additional work serially. Sample weights
must fit signed 64-bit values before conversion, or the quad fails without
partial output.

Shader-visible FragCoord.z, reciprocalW, varyings and pointCoord retain their
center behavior. Private fragment metadata carries the sample-depth vector and
validity through batching, local/global payload execution, discard and held
exports. Covered values must be finite and in [0, 1] before shader access;
unavailable and uncovered values are zeroed. Shader ABI array layouts (versions
1 and 2) and the CU ISA remain unchanged. Standalone interpolation can retain
its center-only contract with
`sampleDepthEnable` disabled; the general primitive owner always enables it.

Late depth/stencil tests select each raster sample depth unless the shader
explicitly overrides depth, in which case the shader value applies to every
selected sample. Preflight checks only effective selected depths when testing
is enabled, so an unused center or overridden/uncovered sample cannot spuriously
fault a quad. Existing late discard, stencil, blend and acknowledged image
publication ordering remain. Raw multisample source stores preserve every
sample; depth resolve retains the existing raw sample 0 rule.

All **90 checks across 14 suites** pass on one frozen archive containing
**576 source files**.
Independent direct sample-position references check sloped triangle/line depth,
points, reversed signed areas, 1x/2x/4x, helpers, backpressure and cancellation.
Thirty typed pixel quads cover five depth layouts and all sample modes with mixed
visibility, stencil outcomes, typed MRT bytes and shader overrides. Real two-CU
local/global tests retain metadata through shaders with all 64 varying components,
128-lane and 12-lane batches, and publish 12 draws per profile across all primitive
kinds at 1x/4x.
The image oracle checks exact multisample backing, resolve destinations, packed
guards and cached consumer visibility, including override color updates with
depth writes disabled. Existing raster, composed draw, transfer and resolve
ownership regressions also pass.

Primitive and render RTL emissions contain **43/166 files**, respectively.
Default private fragment metadata grows from 630 to 1,146 bits per quad and from
10 to 18 rows. Its allocation grows from 512 to 1,024 64-bit words for 128 physical
lanes. This adds **two 256x64 SRAM macros, 4 KiB total**. The composed owner now
has **35 characterized macros, 70 KiB**, including the 32 KiB attachment slot,
across 27 logical SRAM wrappers.
Four fixed-function FMA and three divider instances remain; no floating lane,
shader CU or payload pool is added. These counts are structural RTL accounting,
not synthesis PPA.

This checkpoint covers the bounded general raster path. Native host/bin/resource
ownership, full precision/invariance, centroid/sample varying qualifiers,
polygon offset, unrepresentable guard bands, post-transform reuse, queries and
feedback, broader textures and representative throughput remain open. PPA stays
**synthesis only**. The original H0–H8 development goal remains open.

## Native GPU-memory typed graphics submission

The [native typed submission checkpoint](performance/mobile-native-typed-submission-2026-10-20.json)
connects the general vertex/fragment image path to `GpuHostSystemAxi`. Its
optional `typedGraphics` profile accepts bounded GPU-memory jobs through the
existing opcode-7 queue, shares the system CUs and payload pool, and retains
the existing compute/DMA ordering, protected VM controls, reset and completion
handling. The older GBJ1 binned profile remains a separate selectable profile.

The [GTJ1/GTI1/GTD1 ABI](NATIVE_TYPED_GRAPHICS_ABI.md) defines explicit
512/1,280/2,560-byte job, image-pass and draw records. Its generated
[C header](../driver/opengpu_native_typed_abi.h) and
[JSON map](abi/native-typed-graphics-v1.json) describe all 548 scalar fields.
Offsets are independent of Bundle packing and lane count. Four tagged line
reads may return out of order into private snapshots. Bounds, stride, padding,
reserved bits and active capacity are checked before child work. All three
record kinds can end exactly at the 4 GiB exclusive limit. Shader scratch
prefixes cannot overwrite the immutable command arenas.

One native job owns a declared tile image pass and its ordered GPU-memory
draws through acknowledged source stores and resolves. Descriptors, geometry,
shader staging and sampler traffic use `VECTOR_SATP`; CU instruction fetch
retains `INSTRUCTION_SATP` execute checks. Physical image views bypass translation
using a disjoint transaction range. Legacy DMA and both page walkers retain
independent IDs, including local translation faults and stalled replies.
Physical views and their permission flags remain trusted pinned allocator
metadata; general virtual resource-table binding is separate work.

Cancellation and reset retain offered descriptor requests and child controls,
then drain tasks, payload leases, private queues and physical acknowledgements.
Software-held completions continue to block VM mutation. Reset discards queued
DMA and stale completions only after drain. The software result preserves its
command ID, status and accepted draw-record read bytes; detailed internal
native fault stage/address/draw records are not yet exposed in MMIO.

All **18 checks across seven suites** pass on one frozen archive of **586 source files**.
The production two-CU AXI path executes triangle, line and point shaders at
1x/4x using both local and global payloads. Six draws per profile verify four
simultaneous RGBA8/RGB8/RGBA16F/sRGB attachments, sloped sample depth, exact
multisample source/resolve/depth bytes and pitched guards. Prewarmed ordinary
GPU kernels observe published data. Eight fault scenarios cover envelopes,
descriptor/later-draw reads, aliases, write/execute permissions and physical
stores. Reset tests interrupt a live 1,024-byte shader lease and delayed stores,
reject VM mutation, release all leases/pins, discard queued DMA, and recover
with exact images. Legacy opcode-7, translated fabric, DMA and ordinary shader
discovery regressions also pass. ABI regeneration and independent C size/offset
assertions pass. The preceding 90-check component checkpoint is retained as
separate evidence and is not counted in these 18 checks.

Reader/fabric/native-image/full-host RTL emissions contain **7/31/177/666 files**.
The reader and host fabric add control/register logic without SRAM or floating
arithmetic instances. The native image owner retains **35 SRAM macros, 70 KiB**,
four fixed FMA units and three dividers. The full Mobile emission contains
**four shared CUs and one shared payload pool**, with no private graphics CU.
Its complete hierarchy includes 144 characterized 256x32 and 1,219 characterized
256x64 SRAM macros, including CU storage, caches, shared memory, L2, the payload
pool and graphics storage. These are structural counts, not synthesis PPA.

Runtime checks use four lanes, two warps and two CUs with reduced descriptor
capacities; the default 16-lane/eight-warp/four-CU hierarchy is structurally
emitted here. Full Mobile native runtime, occupancy and rate qualification
remain open. This milestone does not connect general geometry to paged bins or
multi-engine scheduling, close physical allocation/resource-table provenance,
or finish the remaining texture, attribute, guard-band, qualifier, numeric,
query/feedback and H7–H8 requirements. PPA remains **synthesis only**, and the
original H0–H8 acceptance gates remain open.

## General post-transform primitive bins and full-record replay

The [general bin checkpoint](performance/mobile-window-primitive-bins-2026-10-20.json)
adds `WindowPrimitiveBinReplayPass`, connecting a complete post-viewport stream
to the existing paged tile directory and multi-engine bin scheduler. Each
nonempty primitive is stored once, then referenced by every affected tile.
Separate readers resolve the complete primitive for each replay engine. This
establishes the geometry storage/replay boundary needed to avoid repeating
vertex/clip/viewport work per tile; actual vertex-draw and native rendering
ownership are not connected to this new path yet.

The [internal record contract](WINDOW_PRIMITIVE_BIN_RECORDS.md) retains all
16 varying slots, shaded vertex and clipping metadata, draw-local 64-bit
sequence, exact viewport origins, signed 64-bit subpixel coordinates, extended
local coordinates/reciprocal W, window depth, point-size output and raster
state. Each record also names immutable fragment/test/blend state and its
pass-wide bin sequence/token. The configuration-specific GWP1 word format
contains **329 payload scalars in 1,472 bytes / 23 lines** at full capacity.
It is independent of Bundle bit packing and is not a new native host ABI.

Widened address checks permit a complete record ending exactly at 4 GiB without
wrapping. Header, identity, signed/unsigned padding and reserved words are
checked before a successful read is published. Every record store acknowledgement
precedes its first bin-reference offer. Bounds use widened signed arithmetic,
screen/scissor intersection and conservative point/line size expansion.
Unavailable guard-band fixed coordinates fan out across the effective scissor
while retaining the original data; downstream guard clipping/numeric policy
remains required. Empty bounds consume no record space.

The pass owner checks disjoint record/page/directory arenas and matching
identity/grid. Pass-wide sequences strictly increase across draws and clipped
parts. Each engine owns a tile through the final full-record output and tile
result. A stalled engine leaves independent dispatch and memory capacity for
the others. Cancellation drains held producer appends, record transfers,
queued requests, tile outputs/results and all acknowledgements before the pass
summary. Record exhaustion retains the exact unpublished input; a rejected bin
append may have a partial fanout and invalidates the pass. Resume bindings are
rejected until continuation retains the corresponding record allocations.

All **36 checks across five suites** pass on **592 frozen source files**.
Thirteen new checks qualify complete full-capacity scalar round trips, bounds,
corrupt encoding, signed extremes/scissors, point-size and guard fallback,
ordering, exhaustion, held requests/results and fault/cancel reuse. The actual
paged directory uses two resident entries for nine tiles and feeds two bin
readers plus two complete-record readers. Four stored records replay as
**26 exact, ordered references**; engine 1 completes at least three tiles while
engine 0 holds its output. Six integrated fault cases each recover with exact
healthy data. Twenty-three unchanged directory/replay regressions also pass.
Window fixtures here are not actual shader outputs and are not rasterized.

Transfer/producer/Mobile replay emissions contain **7/11/57 RTL files**.
The 1280x720 two-engine Mobile replay uses a 16,384-entry paged directory,
three record-transfer units and two characterized 256x32 report SRAM macros
(2 KiB). Full-record private snapshots, metadata, queues and control add
register storage. No CU, payload pool, FMA or divider is added by these blocks.
These structural counts do not prove the complete Mobile storage budget or PPA.

Actual vertex-to-bin draw ownership, immutable fragment-state resolution,
multi-engine raster/FS/typed images, tile scissor selection and native host/VM/
reset/publication integration remain open. Indexed-vertex post-transform reuse,
continuation lifetimes, guard-band reconstruction, resource-table provenance,
production storage/rate qualification and the original H0–H8 gates remain open.
PPA evaluation remains **synthesis only**.

## Shared-CU vertex draw ownership for general bins

The [vertex/bin checkpoint](performance/mobile-vertex-bin-pipeline-2026-10-20.json)
adds `VertexBinPipeline`, connecting actual GPU vertex input and shared-CU
execution through assembly, clipping and viewport preparation to the general
record/bin/replay path. A pass accepts multiple ordered draw snapshots; every
window part receives a pass-wide sequence while retaining its original
draw-local sequence, clipped part, instance and provoking metadata. Raster
state, immutable fragment-state handle and token belong to the producing draw.
Replay does not re-execute the vertex shader for another tile.

The [draw/ownership contract](VERTEX_BIN_PIPELINE.md) checks pass identity,
raster/viewport/topology encoding and known vertex input/index/scratch aliases
against writable record/page/directory arenas before source start. The scratch
check includes its uniform tail. General code, texture and immutable fragment
state allocation provenance remains external. A successful draw result waits
for source retirement and its final record store/bin append acknowledgements.
Pass completion also waits for all replay records, tile results and queued
memory acknowledgements; it does not retire downstream pixels or publish an
image. Empty untouched geometry bins produce no tile result.

The owner preserves primary source attribution and the failing draw ordinal.
Source cancellation caused by a bin failure cannot replace that bin status.
A replay fault after draw retirement leaves the draw attribution explicitly
unavailable. Cancellation retains accepted child controls and offered source
parts, tasks, payload leases, record outputs and tile results through drain.
The payload context is captured at pass begin. Connecting `graphicsDrained`
to the owner keeps the actual shared system's VM boundary held after source
payload release and through held draw/pass results.

All **27 checks across five suites** pass on **594 frozen source files**.
Ten new owner checks execute real shared-CU indexed/instanced kernels with
local and global payloads. Each three-draw profile shades **54 source vertices
in nine tasks**, prepares **24 window parts** and replays **54 exact ordered
tile references**. Replay launches no additional vertex task. The independent
clip/viewport oracle checks generated vertices, smooth/flat values, metadata,
local coordinates, reciprocal W, exact fixed coordinates and depth. One replay
engine continues while another holds its record, with payload storage already
released and the system VM boundary still owned.

Seven ordered draws cover all seven topologies, two instances and indexed
restart: **82 prepared parts, 240 exact references and 22 vertex tasks**.
Restart flushes partial batches of 3/8/1/6 vertices in one draw; the other six
draws use three batches each. Other checks cover empty pass/input/viewport,
draw/end priority, eight admission failures before source access, three nested
source faults and four bin/record failures with healthy reuse. A delayed final
record acknowledgement prevents early draw completion after payload release.
Pool cancellation interrupts both pre-admission work and a live shader lease
with an instruction fill held. Replay cancellation retains held records and
tile results. Seventeen unchanged record/bin/vertex-source regressions pass.

Runtime uses four lanes, two warps, two implemented varying slots (one active)
and two available CUs. The sequential vertex client uses CU0 in these fixtures;
this is not an occupancy/rate result. The default 16-lane/eight-warp Mobile
emission has four external shared-CU ports, 16 varying slots, two replay engines
and a 16,384-entry paged directory. Its **147 RTL files** include fourteen
characterized 256x64 and two 256x32 SRAM macros (**30 KiB**) plus additional
private/control registers. Existing clipping/viewport math supplies two FMA
units and two dividers. No CU or shared payload store is instantiated inside
this owner. These are structural counts, not synthesis PPA or total Mobile
storage-budget closure.

Immutable fragment-state fetching, multi-engine raster/fragment/typed image
execution, native GPU-memory admission, general allocation/resource permissions,
host MMU/reset integration and acknowledged image publication remain open.
Indexed-vertex reuse, continuation lifetimes, guard-band/qualifier/numeric and
texture/resource semantics, production budgets/rates and the original H0–H8
qualification gates also remain open. PPA remains **synthesis only**.


## Immutable typed fragment-state reader checkpoint — 2026-10-20

`TypedFragmentStateReader` now fetches GFS1 immutable GPU-memory fragment state:
512-byte records with 71 explicit scalar fields, fragment/test/blend state and
four independent engine scratch views. Four reserved memory tags permit reordered
line replies into a private snapshot. Canonical encoding, identity, bounds,
shader-prefix capacity and active-view separation are checked before publication.
One exact-key cache entry supports explicit invalidation, including suppression
of an in-flight fill. Cancellation retains offered requests/results and drains
all tags before reuse.

All **six reader checks** pass on **599 frozen standalone source files**, without
the pending prepared-geometry/replay renderer changes. Tests include a final
entry ending at the exclusive 4 GiB limit, malformed padding/headers, scratch
aliases, reordered reads, faults, cancellation, held results and healthy reuse.
The generated GFS1 C/JSON maps match byte for byte; the existing native descriptor
maps also pass their generator check. An independent C11 offset/size probe passes
and confirms unchanged GTJ1/GTI1/GTD1 sizes. See the [state ABI contract](TYPED_FRAGMENT_STATE_ABI.md)
and [frozen evidence](performance/mobile-typed-fragment-state-2026-10-20.json).

This checkpoint qualifies the reader component. Prepared-geometry typed image
execution, multi-engine bin-to-image composition, native admission/MMU/reset,
resource provenance, full Mobile runtime/rates and H0–H8 qualification remain
open. There are no new synthesis results; PPA remains **synthesis only**.


## Typed fragment-state synthesis checkpoint — 2026-10-20

The [standalone state-reader synthesis record](performance/mobile-typed-fragment-state-synthesis-2026-10-20.json)
passes the existing **1 GHz ASAP7 TC/SLVT** setup screen with a 25% I/O delay
budget and no false-path ports. The two reported setup groups have slack
**+299.608 ps** and **+418.452 ps**. Mapping checks find no omitted operators or
undriven logic. The mapped **23,374 cells** occupy **3,036.664080 µm²**, including
1,119.744 µm² of sequential cells; this block contains no SRAM macro. The
critical core path runs from the fragment varying-slot snapshot to status.

All seven emitted files reproduce byte for byte from **599 frozen standalone
sources** with only the reader emission case added to `63055b3`. Pending typed
replay/image changes are excluded from that reproduction. The initial synthesis
wrapper's dirty-worktree flag is retained in the evidence and reconciled with
those identical input hashes. This is pre-layout setup and cell-area evidence;
it does not establish power, hold, routed timing or whole-GPU closure. H0–H8
remain open. PPA continues to use **synthesis only**.


## Typed image execution from post-transform replay — 2026-10-20

`TypedReplayTileImagePipeline` now fetches immutable GFS1 state and renders
complete post-transform `WindowPrimitiveReplay` records through the existing
typed image owner. Its prepared-geometry mode starts at primitive rasterization:
vertex fetch/shading, assembly, clipping and viewport work are not repeated.
The default vertex-to-image mode and GTJ1/GTI1/GTD1 layouts remain intact.
Per-engine/per-state scratch slots protect concurrent prefixes and the uniform
tails needed when a later tile replays another state. The raster scissor is
intersected with the tile and runtime screen before narrowing.

All **33 checks across five suites** pass on **601 frozen source files**.
Two actual fragment engines use two shared CUs and one payload pool. Tests
require simultaneous ownership of both CUs, use distinct first states so task
or payload swaps are observable, and repeat with a one-slot pool that forces
global fallback. A prewarmed cached consumer reads the cache line straddling
the tile boundary. Holding its invalidation acknowledgement delays publication;
after source/resolve acknowledgement, exact shared-line bytes and repeated cache
hits are checked. No private CU or payload pool is added.

Resolved triangle, point and line fixtures cover local/global payloads, four
MRT locations, floating/sRGB and integer formats, per-sample default depth,
shader overrides, blending, original scissor intersection, 1x/4x backing and
resolve bytes, and unaligned pitched guards. Empty tiles clear and publish
without state reads or shader work. Admission/record checks reject scratch
roles/slots, mismatched identities/ordinals/tokens and out-of-range handles
before shader access. State reads, shader traps and physical store faults
retain their stage and attribution through healthy reuse. Cancellation retains
held primitive results, launches, leases and final physical acknowledgements.
Twenty-four state-reader/default-image/native-reader/native-AXI regressions also
pass, including translated native resources and production reset drain.

The default 16-lane/eight-warp/16-varying single-engine emission contains
**133 RTL files**, one raster stage, one fragment stage, two FMA lanes and one
divider. Its 21 characterized 256x64 SRAM macros account for **42 KiB** plus
private/control registers. Vertex shader, clipper, viewport, CU and shared
payload-store instance counts are zero in this owner. These counts are
structural; the separately running engine synthesis is not qualified by this
functional checkpoint. The standalone state-reader synthesis result is already
recorded above. See the [replay image contract](TYPED_REPLAY_TILE_IMAGE.md) and
[frozen evidence](performance/mobile-typed-replay-image-2026-10-20.json).

The new tests inject resolved geometry fixtures. Actual shared-CU vertex-to-bin
execution has separate VBIN evidence; production bin-to-image composition and
full-frame empty-tile scheduling are still being developed. The native AXI
regressions cover the ordinary path, not native admission/MMU/reset for these
new replay engines. Resource provenance, native frame integration, full Mobile
runtime/budgets/rates and the original H0–H8 gates remain open. PPA remains
**synthesis only**.

## Shared-CU vertex/bin/typed-image composition (2026-10-20)

`TypedVertexBinImagePipeline` now composes actual shared-CU vertex draws, general
post-transform bins and two independent typed replay image engines. Vertex work
executes once; replay launches fragment tasks. Whole-grid admission validates
partial edges before work, and the version-3 directory publishes all tiles,
including empty tiles. Per-engine renderer credits extend through physical
image publication and queued tile results, preserving progress when another
engine's result is held.

All **24 checks across four suites** pass on **604 frozen source files**. Two-CU
local/global tests publish exact four-MRT/depth source and resolve bytes across a
17x9 frame, including indexed instances, 4x MSAA and partial tiles. Record-read,
fragment-trap and physical-store failures retain primary attribution; cancellation
retains held primitive results, queued launches/live leases and final physical
acknowledgements. Every fault/cancel case drains and then renders a healthy frame.
Existing vertex/bin regression checks also pass.

The combined default emits 267 RTL files, 56 characterized 256x64 SRAM macros
and two 256x32 macros (**114 KiB**), one vertex stage, two fragment stages, six
FMA lanes and four dividers, with no private CU or payload pool. These are
structural counts. See the [owner contract](TYPED_VERTEX_BIN_IMAGE_PIPELINE.md)
and [source/log/RTL evidence](performance/mobile-typed-vertex-bin-image-2026-10-20.json).

This is an internal physical-address owner. Native admission/MMU/host/reset for
the combined path, resource provenance, full Mobile runtime/rates/budgets and
H0-H8 remain open. Runtime uses four lanes/two warps and small images. PPA remains
**synthesis only**.

## Typed replay image engine synthesis screen (2026-10-20)

The completed single-engine ASAP7 TC/SLVT mapped synthesis screen **fails setup**
at a 1000 ps clock and 25% I/O delays. Worst setup slack is **-5867.270 ps**;
the core Fmax estimate is **145.618 MHz**. The other reported setup group has
+99.894 ps slack. Mapping qualification passes with zero skipped operators and
no unresolved diagnostics. Functional correctness remains separate evidence.

The critical register path starts at
`image.transfers.descriptor_pixels_colors_sampleMode[0]` and ends at
`image.transfers.offset[10]`. The next timing work must address the measured
transfer datapath. Synthesis reports 1,309,402 cells including 21 SRAM macros
and 174,148.273079 square micrometres of total mapped area. This is a single
replay engine, not the combined vertex/bin/image owner or the whole GPU.

All 64 active synthesis RTL inputs match the frozen emission in the committed
replay-engine qualification; the evidence reconciles the wrapper's older dirty
checkout metadata explicitly. See the [negative synthesis result and artifact
hashes](performance/mobile-typed-replay-image-synthesis-2026-10-20.json).
PPA remains **synthesis only**. No place/route or power result is claimed;
full Mobile timing/rates/budgets and H0-H8 remain open.

## Bounded typed-image refill row decoder (2026-10-20)

The measured sample-mode-to-transfer-offset path contained a descriptor-dependent
combinational division/remainder. `TypedTileImageTransfer` now locates a refill
word's row with four bounded compare/subtract steps. Admission already limits
views to 8/16 rows and 1024 bytes per row, so this preserves packed 3/6/12-byte
formats without a general divider. It adds four cycles per explicit refill;
physical byte gathering, cache reuse, publication and ownership remain unchanged.

All **12 transfer checks** pass on 604 frozen sources. New coverage verifies
**7,520 refills across 73 bindings**, both tile sizes, 1x/2x/4x samples, all 36
color formats, six depth formats, cropped padding and the highest 64 KiB slot
word. Six cancellation arrival phases retain a held fault reply, issue no
physical traffic and recover with exact healthy reuse. Existing clear/load/
store/resolve, fault, guard, alias and exclusive-4-GiB tests also pass.

Both replay-engine and standalone-transfer RTL emit successfully; the transfer
module has no division/remainder operator. Structural replay-engine counts
remain 133 files and 21 SRAM macros. The broader image/replay/combined-owner/native
AXI regression and a fresh synthesis run are still in progress at this commit;
no improved Fmax or full-system qualification is claimed here. See the
[component evidence](performance/mobile-typed-image-refill-decoder-2026-10-20.json).
PPA remains synthesis only, and H0-H8 remain open.

## Broader refill-decoder qualification (2026-10-20)

The bounded decoder at `8a9c4fe` now passes **44 checks across six suites** on
the same 604 frozen sources. The two immutable logs contain 39 image/transfer/
replay/combined-owner checks and five separately selected native AXI checks.
The evidence records the initial incorrect native test package selector rather
than treating an unselected suite as tested.

Real shared-CU coverage includes local/global payloads, indexed instances,
4x MSAA, exact guarded MRT/depth source and resolve bytes, cached consumers,
primary faults, held outputs, leases, final physical acknowledgements and
healthy reuse. Existing ordinary GTJ1 native AXI coverage also passes translated
resource permissions, shader instruction permissions, physical store faults and
live shader/publication reset drain. These host checks do not qualify the new
GNJ1 binned owner/fabric under development.

The combined default emission remains 267 files, 114 KiB of characterized SRAM,
one vertex stage, two fragment stages, six FMA lanes and four dividers, with no
private CU or payload pool. Both existing ABI byte checks pass. See the
[two-log qualification](performance/mobile-typed-image-refill-validation-2026-10-20.json).
Repeat synthesis is separate and still running at this checkpoint; functional
success establishes no improved Fmax. Full Mobile rates/budgets, new native
binned host integration, resource provenance and H0-H8 remain open.

## Native memory jobs through general bins and typed images (2026-10-20)

`MemoryNativeBinnedGraphicsPipeline` now joins GPU-memory job/frame/draw snapshots
to actual shared-CU vertex processing, general post-transform bins and independent
typed image engines. Payload contexts are captured at submit before descriptor
fetch. Descriptor and renderer memory ranges are disjoint; physical image ranges
are separate from virtual geometry/bin/state/staging traffic. The new standalone
Sv32 fabric adds independent DMA/walker ranges and immutable buffered replies.

The unpublished prototype job magic collided with legacy GBJ1. The current
**GNJ1/GNF1/GND1** records use distinct magic values while retaining the same
512/2048/2048-byte fixed layouts and 563 scalar fields. A regression checks the
namespace against existing bin, typed-image and fragment-state records. Published
legacy interfaces are unchanged.

All **18 checks across five suites** pass on **614 frozen source/ABI files**.
Actual local/global native jobs publish exact four-MRT/depth source and resolve
bytes across 17x9 partial tiles, including indexed instances and 4x MSAA. Both
CUs are observed busy simultaneously. A held result leaves the other tile engine
progressing; cancellation after shader retirement retains the final physical
acknowledgement and then recovers with an exact healthy frame. Standalone reader/
fabric coverage includes capacity/alias/padding rejection, exclusive 4 GiB ends,
fault/cancel drain, translated state reads, both physical image ranges, independent
walkers, write permissions and held output lifetimes. Both ABI byte checks and
an independent C11 probe pass.

Default reader/renderer/fabric emissions contain **7/278/35 files**. The renderer
retains 114 KiB of characterized SRAM, one vertex stage, two fragment stages, six
FMA lanes and four dividers, with four external CU ports and no private CU or
payload pool. Its native memory namespace is 256 IDs; the fabric uses 512 IDs
and two translators. These counts are structural. See the [native owner/fabric
contract](NATIVE_BINNED_GRAPHICS_PIPELINE.md) and [frozen evidence](performance/mobile-native-binned-image-2026-10-20.json).

Actual shader tests use Bare mode; fabric Sv32 checks are standalone. Production
owner/fabric/host/MMIO/reset composition is the next step. Trusted image physical
provenance and translated cross-resource aliases, full Mobile runtime/rates/
budgets and H0-H8 remain open. PPA remains synthesis only; the separate replay
engine synthesis is still running and establishes no new timing result here.

## Typed image refill decoder synthesis result (2026-10-20)

The bounded row decoder improves the single replay engine's mapped ASAP7
TC/SLVT core Fmax estimate from **145.618 to 224.000 MHz** (+53.83%). Setup
still fails at 1000 ps: **-3464.279 ps**, compared with -5867.270 ps before.
The other reported setup group has +27.511 ps slack. Mapping passes with no
skipped operators or unresolved diagnostics.

Mapped area falls from 174,148.273079 to **173,213.286839 square micrometres**
(-0.537%); the 1,295,620 mapped cells include the same 21 SRAM macros. Exactly
one of the 64 active RTL inputs changes: `TypedTileImageTransfer.sv`. Macro
bytes, SDC and flow parameters match the baseline. The new critical path runs
from `image.renderer.pixels.colors.row[1]` to
`image.renderer.pixels.colors.memoryBanks_0.memory/wd[46]`; the next timing
iteration must pipeline that measured color-storage datapath.

See the [decoder synthesis comparison and artifact hashes](performance/mobile-typed-image-refill-synthesis-2026-10-20.json).
The functional decoder adds four cycles per private refill, so the core Fmax
change alone is not a workload speedup claim. This is synthesis-only evidence
for one replay engine; native/full-host timing, power, full Mobile rates/budgets
and H0-H8 remain open.


## Typed color clear and maintenance regression (2026-10-20)

The bounded clear-byte decoder and registered maintenance write path pass
**66 checks across eight suites** on 619 frozen source/ABI files: eleven storage
checks and 55 pixel/render/transfer/image/replay/shared-CU/native-owner checks.
Every supported 1/2/3/4/6/8/12/16-byte pixel width preserves clear repetition;
6,656 virtual clear words, 227 masked high-slot updates and two accepted-write
cancellation phases are independently checked. Maintenance writes add one cycle.

Actual local/global shared-CU owners preserve typed blending, per-sample depth,
pitched source/resolve guards, cached publication, independent tile engines,
indexed instances, immutable contexts and exact fault/cancel reuse. Default
color/replay emissions remain 25/133 files with 16/21 SRAM macros and no private
CU or payload pool. These are structural counts. See the
[66-check regression and frozen inputs](performance/mobile-typed-color-clear-validation-2026-10-20.json).

The synthesis run remains separate; functional success does not establish a new
Fmax or area. Combined production AXI/Sv32/GNX1 composition, physical provenance,
full Mobile workloads/rates/budgets and H0-H8 remain open. PPA is synthesis only.


## Base native binned AXI host composition qualification (2026-10-20)

The [14-check base host record](performance/mobile-native-binned-host-composition-2026-10-20.json)
qualifies the native owner, Sv32/physical fabric and production command/MMIO host.
Local/global actual shaders, four MRTs plus D32F, guarded pitched source/resolve
images, cached consumers, 1x/4x MSAA, empty six-tile frames and indexed instances
pass. Eight fault cases each allow an exact healthy frame. Both live-shader and
unacknowledged-publication reset phases discard queued DMA/old completions,
protect the VM and release payload ownership before exact healthy reuse.

This is the earlier 24-field GNC1 profile, before the separately tested GNX1
extension and new color-clear pipeline. The five-case rerun passes on 619 frozen
files after correcting its permission-injection and AXI-driver assumptions; nine
compatibility checks use the same production RTL. The default 768-file four-CU
host emission is structural evidence. Combined GNX1/color host qualification,
physical provenance, translated aliases, full Mobile rates/budgets and H0-H8
remain open. PPA remains synthesis only.


## Combined native host, diagnostics and color qualification (2026-10-20)

The [30-check combined checkpoint](performance/mobile-native-binned-combined-host-2026-10-20.json)
passes on 621 frozen source/ABI files. GNC1 native submission, GNX1 completion
diagnostics and the bounded clear/registered maintenance pipeline now pass
actual local/global shader, cached publication, indexed/empty frame, 1x/4x MSAA,
eight fault/reuse and both reset/drain cases together. Nested vertex faults and
image fault tiles remain attributed; stale details cannot survive POP/reset or
be inherited by compute results. Legacy typed rendering and AXI/MMIO controls
pass in the same run.

All three ABI checks, C11/schema evidence and the combined 768-file four-CU host
emission pass. These tests use small runtime configurations and trusted physical
GNC1 image views. GNC2 image-VM protection, physical provenance/alias admission,
full Mobile workloads/rates/budgets and H0-H8 remain open. The separate color
synthesis is still pending; no new Fmax, area or power is established here.


## Bounded color maintenance synthesis result (2026-10-20)

The [completed synthesis screen](performance/mobile-typed-color-clear-synthesis-2026-10-20.json)
raises the single replay image engine's TC/SLVT core estimate from **224.000 to
255.537 MHz** (+14.08%). At the unchanged 1000 ps period and 25% I/O budget,
core setup improves by 550.951 ps but still fails at **-2913.328 ps**. The
reported I/O path has +81.208 ps slack. Mapped area, including the same 21
characterized SRAM macros, falls from 173213.286839 to **172654.027199 um²**
(-0.323%); the mapping has 3796 fewer cells and no omitted operators.

The original mapping completed after its host wrapper was interrupted. Recovery
retained that exact netlist/ODB/SDC and ran only the missing synthesis STA in a
new output directory, with mapping results mounted read-only. All 64 active RTL
files match the frozen 619-file source/ABI snapshot. After removing comments,
only the typed color store logic differs from the previous mapped candidate.
The 66 functional regressions remain recorded separately.

The worst reported core path now runs from the point/line raster request's
`lineWidth[30]` to its coverage `mask[0]`, instead of color SRAM write data.
The next timing iteration should cut that measured coverage path while
preserving sample modes, bounds, cancellation and primitive ownership.
This is one prepared replay engine, not the native host or a four-CU GPU.
PPA remains **synthesis only**; power, hold, routed timing, full Mobile runtime/
rates/budgets and the original H0-H8 requirements remain open.

## Point/line coverage and AXI ownership checkpoint (2026-10-20)

The [coverage preparation and carry corrections](POINT_LINE_COVERAGE_PIPELINE.md)
retain signed coordinate differences through checked weight exports and remove
the triangle-specific span restriction from safe lines. The original arithmetic
fails three of four extreme-coordinate checks; the corrected source passes all
19 coverage, primitive, interpolation and covered-sample-depth checks. Fifteen
matched ordinary-input primitives retain every quad output field with exactly
two added setup cycles for points/basic lines and three for multisample lines.

The [standalone synthesis comparison](performance/mobile-point-line-synthesis-2026-10-20.json)
changes the point/line core estimate from 273.089 to 278.539 MHz (+2.00%), with
mapped area up 2.78%. Including measured setup cycles gives 1.26–1.94% lower
estimated latency for those small fixtures. Both versions still fail 1 GHz
setup. This component result does not replace the 255.537 MHz prepared replay
engine estimate; composed synthesis and image/native regressions are pending.

The [host AXI ownership correction](HOST_AXI_RESPONSE_OWNERSHIP.md) fixes two
independently reproduced failures: simultaneous AR/AW acceptance into one address
state and live status changes leaking into a stalled RDATA response. Exclusive
transaction grants and a first-stall holding register preserve the existing
unstalled beat rate. All nine control/discovery/burst/reset/fill/VM-owner checks
pass on 627 unchanged source/ABI files. The [complete 37-check composition](performance/mobile-host-axi-native-composition-2026-10-20.json)
also passes on that same source: twenty component/image-owner and seventeen
native host cases, all 22 unfiltered faults, four converted-input profiles,
cached sources/resolves, held completion and reset/reuse. Default four-CU
emission completes with 371 active RTL files and unchanged SRAM/AXI capacities.
Later centroid and current-main core changes require their separate merged-source
regression. These feature-branch checkpoints do not close H0-H8,
GLES resource/format coverage, full Mobile throughput or physical qualification.
