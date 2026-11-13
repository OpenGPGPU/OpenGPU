# Mobile GPU hardware alignment proposal

Milestone status and acceptance evidence are recorded in the
[milestone acceptance record](MOBILE_GPU_MILESTONE_ACCEPTANCE.md).
Current PPA evaluation uses synthesis only; functional hardware
development remains in progress.

Evolve OpenGPU into a mobile rendering architecture by extending the shared CU pool with persistent tile storage, GPU geometry fetching, scalable texture and pixel backends, and general graphics resource descriptors. Keep shader arithmetic on the existing CUs. The first functional target is hardware sufficient for a complete GLES 3.0 implementation; a Mali-G31-class commercial comparison requires further feature, performance, and physical qualification.

The configuration and rates below are design targets, not measured capabilities. Implementation should proceed in bounded milestones, with correctness, throughput and timing gates before integration. Compiler and driver implementation are outside this plan; hardware interfaces needed by them are included.

The device compiler is already underway in a sibling tree, not in this repository. LLVM/clang on `../llvm-project` branch `opengpu` targets the current CU ISA as the RISC-V vendor extension `XOpengpu` (triple `opengpu-unknown-none-elf`). Assembler, codegen, clang and a fatbin loader in `userspace/libopengpu` are in place. The 2026-10-19 vertex+fragment Verilator guest ran `examples/compute_offload` and checked `kadd` (`1 + 2 + 3 = 6`). See [LLVM_TOOLCHAIN_PLAN.md](LLVM_TOOLCHAIN_PLAN.md). Keep the decoder encodings, custom opcodes (`cease`, `barrier`, texture sample, `vquad.dfdx`/`dfdy`), reserved registers `x1`/`x8`/`x9`/`x30`/`x31`, kernarg addressing, and `vsetvli` `e32,m1,tu,mu` stable, or update that branch in the same change. The driver validator in `driver/opengpu_shader_validator.h` is still what admits a shader.

[Quad-word fragment exports](FRAGMENT_QUAD_EXPORTS.md) pass the 32-clock
prepared-quad rate checks, six native development scenes and complete actual
fragment/vertex component synthesis. Whole-host timing and the six 2-CU VGA frame
budgets remain required.

## Target and scope

Use Mali-G31 as an older commercial reference class, rather than treating it as the definition of a current entry-level GPU. Arm lists GLES 3.2 support for that device. GLES 3.0 is therefore an intermediate capability target. [Arm Mali-G31](https://support.arm.com/compute-ip/mali-g31)

The current OpenGPU product target, revised by the user on 2026-11-13, is
**two shared CUs rendering 640×480 at 60 FPS**, at a physically qualified clock.
Larger frame sizes and four-CU runs remain optional extensions.

Test desktop composition, textured 3D, opaque overdraw, transparent layers,
small triangles, and 4x MSAA separately. Each complete scene must meet the
16.67 ms frame deadline on the same frozen two-CU configuration, including
geometry, binning, rendering, store/resolve, dependencies and drain. Check exact
stored samples, resolves, guards and traffic, then use the matching complete-host
clock and memory assumptions to establish FPS. Small-frame checks, display
refresh, four-CU results or resolution/CU-count scaling estimates cannot qualify
this target. These targets do not imply measured parity with a commercial GPU.

GLES feature/API limits, fault and ownership semantics, H0–H8 deliverables and
physical gates remain in scope. In particular, the 640×480 performance workload
does not lower API renderbuffer/texture capacity requirements. Existing four-CU
RTL defaults and optional extended runner commands retain their own scope;
qualification for this target must explicitly select and verify two CUs and
640×480. Update the product runner/profile and its stale-profile rejection
before using it for the new acceptance gate.

The [wider pixel arithmetic target](STREAMING_PIXEL_ARITHMETIC.md) specifies four
streaming component lanes and eight owned quad contexts per raster engine,
replacing serialized component work. The first streaming divider qualifies
one operation per cycle and 29-cycle latency; streaming add/multiply passes
one operation per cycle and 11-cycle latency. Both pass 1000 ps component
synthesis. The combined weighted-component lane sustains one component per
cycle with 64-cycle latency. The composed four-lane quad block accepts four
prepared components each cycle with 65-cycle row latency; both combined blocks
pass the same synthesis gate. A four-context prepared-quad owner now sustains
one full quad every 64 clocks and passes complete 1000 ps synthesis in both
fresh and parent-reserved admission modes. [Native spatial interpolation](SPATIAL_FRAGMENT_INTERPOLATION.md) connects
eight raw preparation owners to that bank, with four pixel-lane services per
owner and thirty-two preparation services per raster engine. The bounded
full64 fixture improves from four quads per 389 clocks at four contexts to
four per 256 clocks at eight contexts, against a 272-clock regression bound.
Nine expanded-profile owner/raster checks pass, including ordered rejection,
cancellation and reset. The four-context baseline passes complete raster/
interpolation synthesis at 1000 ps; the expanded owner also passes, at
+9.550/+477.735 ps core/I/O margin and 475,284 µm² of library area.
Raw preparation, point/line coverage, triangle coverage and
scalar arithmetic retain their component qualifications. Complete engine/
shared-CU/host timing, payload bandwidth, target-profile fragment concurrency and
product renderer rates remain open.

The hardware contract must cover shader execution, geometry, texture resources, rasterization, framebuffer operations, synchronization and faults. Display scanout remains a consumer of completed images. Its refresh rate is separate from rendering throughput.

The separate [four-engine native host profile](NATIVE_FOUR_ENGINE_HOST.md)
defines the expanded integration configuration and its frame validation
contract. Its emitted hierarchy contains 3.43 MiB of characterized SRAM macro
capacity before synthesis pruning, including replicas and padding. This is
not runtime occupancy or a final mapped resource budget; whole-host timing,
complete frame validation and product rates remain required.

Latest texture checkpoint: [native captured-operand execution](TEXTURE_NATIVE_EXECUTION.md) connects scalar/vector floating sampling to both shared-CU shader stages, with task-scoped operands, helper tuples, fault drain and all64 storage formats. Its 803-input qualification passes144 distinct tests, nine descriptor ABI checks and fresh full vertex/fragment native endpoint synthesis at1000ps including I/O. The main audit preserves the qualified bytes. The translated AXI qualification adds two passing local/spill tests and144 submitted frames (140 successful, four expected errors); the earlier144 tests, nine ABI checks and two synthesis screens carry by exact dependency hashes. [Captured integer fetch](TEXTURE_INTEGER_OPERANDS.md) is now integrated on main with checked signed offsets and base-relative shader LOD. Its 154-test qualification covers both real shared-CU stages and both payload modes, plus nine ABI checks and positive whole vertex/fragment endpoint synthesis at1000ps (core/I/O +101.893/+183.062ps and +97.808/+171.998ps). The integer main audit preserves803 exact inputs. Directed nonzero-base AXI proof now passes four frames across both payload modes;the complete74-frame integer matrix per mode also passes (148 frames total,144 successful,four expected errors). The corrected disjoint-source regression now passes four captured floating/integer AXI matrices with292 frames (284 successful,eight expected errors). The paired typed packet-fetch matrix also passes68 frames;all six corrected-source tests now pass360 frames. [Size queries](TEXTURE_SIZE_QUERIES.md) and [projective shader execution](TEXTURE_PROJECTIVE_EXECUTION.md) are now integrated on main:23 fresh composed sampling checks preserve the integer mip probes,the query AXI matrix passes148 disjoint-source frames,and the14 projective/backend/payload checks retain their qualified inputs. Other older-opcode disjoint-source AXI matrices,native banked throughput/precision integration and coherence remain open.

Historical H4 banked-service checkpoint: [read-through](BANKED_TEXTURE_READTHROUGH.md), [Q16-normalized FP32 output](BANKED_TEXTURE_FP32.md) and [virtual tap admission](BANKED_TEXTURE_TRANSLATION.md) are now integrated on main with 822 exact inputs. The composed candidate passes 69 checks and eight emissions. All three complete component synthesis screens pass 1000 ps including I/O: packed read-through +95.013/+44.671 ps, normalized FP32 +95.245/+59.166 ps, and translated admission +8.538/+181.588 ps. The service retains the 8 KiB/four-bank data store, sixteen physical sample slots, sixteen fills, thirty-two translated admission rows, precise virtual faults, uncached policy and scoped cancellation. It accepts prechecked spans and Q16 weights. The unquantized FP32 service and corrected native arithmetic are now also qualified for main integration, with 82 checks carried by 828 identical inputs and ten fresh byte-exact emissions. Native coordinate/resource, client and CU-pair pool developments remain on separate branches; whole-renderer routing, rates and coherence are still required for H4.

## Current hardware and gaps

| Area | Current RTL or interface | Required evolution |
|---|---|---|
| Shader execution | Shared compute CUs already execute vertex and fragment tasks; vector FP32 arithmetic, including FMA, is connected | Improve task input/output transport, occupancy, control-flow/resource contracts and sustained issue/writeback throughput |
| Configuration | `GpuConfig` defaults to 16 lanes and eight warps; the mobile CU-count constant is four | One compute/graphics execution configuration; discoverable resource limits and explicit supported configuration combinations |
| Interstage transport | Eligible Mobile fragments default to local payloads through the parallel/tile/AXI hierarchy; a composed generic vertex/raster/fragment owner qualifies two-client payload and shared-CU task lifetime | Integrate native bin/host and typed attachment ownership; budget composed SRAM and qualify broader resources and sustained transport |
| Geometry | Bounded GPU fetch, indexed/instanced shared-CU vertex execution, seven-topology assembly, FP32 clipping/viewport, owned point/line/triangle raster/interpolation and fragment shaders connect GPU vertex work to native GTJ1 submission and bounded typed image publication | General native binning/resource ownership, qualifier/guard-band closure, full Mobile runtime and [reuse workload qualification](VERTEX_POST_TRANSFORM_REUSE.md) |
| Tiling | Optional pass-lifetime RGBA8 plus D24S8 attachments with two SRAM slots per engine; paged rendering, two engines, automatic continuation, masked line transfers, bounded unified AXI host submission/reset/VM and sequential GPU input publication | Concurrent producer dependencies, physical allocation ownership and representative transfer/frame throughput |
| Texturing | Native per-worker samplers retain the shared word fabric; banked Q16/admission and unquantized FP32 components are qualified, with corrected native FMA arithmetic | Coordinate/resource, native-client and shared-CU cluster integration; whole-path format/precision, throughput and coherence |
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

## Starting resource sizing

Keep these values in one proposed hardware configuration record. Derive transaction IDs, queue indices and SRAM addressing from it. Architectural limits, runtime bindings and elaboration-time capacities must be separate fields.

The product CU count is two. Other resources below retain their independently
selectable starting budgets; freeze the actual engine/cluster counts for the
two-CU qualification instead of inferring them from a four-CU result.

| Resource | Initial proposal | Meaning and scaling rule |
|---|---|---|
| Shared CUs | 2 CUs, 16 lanes, 8 resident warps per CU | Product target; retain supported lane/warp parameterization |
| Tile engines | 2, initially 1 for bring-up | Independently selectable; `MobileGpuConfig.balanced(2)` selects one engine, so record the actual product choice |
| Raster output | 1 quad/cycle/engine | Eight candidate pixels/cycle at two engines; coverage and interpolation availability can reduce useful output |
| Texture clusters | 2 | Independently selectable; the balanced two-CU profile selects one cluster. Requests include task, lane, descriptor and destination tags |
| Texture throughput | 1 RGBA8 bilinear sample/cycle/cluster | Conflict-free resident-data target; four tap words/cycle/cluster. Trilinear requires two bilinear groups |
| Texture cache | 8 KiB data/cluster, 64-byte lines, at least 4 word banks | Starting point for a capacity/bank sweep, including refill and response bandwidth |
| Texture outstanding fills | Start at 16 line fills/cluster; sweep 8/16/32 | Final value follows measured miss demand and memory latency; separate line fills from sample/tap credits |
| Pixel throughput | 4 sample updates/cycle/engine | RGBA8 plus D24S8, conflict-free resident data; includes the required old-value reads and writebacks |
| Pixel word service | 8 ports of 64 bits, 16 banks per active 32 KiB slot | Four old-value reads plus four full-word writebacks per clock; [component qualification](WIDE_TYPED_TILE_MEMORY.md), native integration pending |
| Pixel operation window | Target 128 four-sample packet contexts/engine | Overlap read, late tests, blend, packing and writeback; retain word ownership through write acknowledgements and qualify the composed rate |
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

The [banked typed tile SRAM](TYPED_PIXEL_BANKING.md) qualifies four concurrent
word ports with atomic packed-channel masks. Parallel typed color/depth
controllers and end-to-end sample-update rates remain H5 integration work.

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

The current planning assumption, selected by the user, is 1000 MHz (1 GHz):
the 60 FPS target permits at most 16,666,666 whole service cycles/frame
(16.67 ms). Recalculate this budget as `floor(qualifiedClockHz / 60)` at the
actual qualified frequency. The assumption does not qualify the whole-host
clock or FPS. A single RGBA8 640×480 final store is 1,228,800 bytes/frame, or 73,728,000
bytes/second at 60 FPS. This excludes texture, geometry, bin, attachment-load,
spill and display-read traffic. At the same assumed frequency, the proposed
two-cluster texture cache-hit bandwidth and a 256-bit AXI read channel each
have an ideal rate of 32 GB/s. These are ideal bandwidths; actual DRAM service
and utilization must be modeled separately.

Tile storage reduces repeated external attachment traffic by retaining intermediate values on chip. This is a mobile bandwidth/power design principle, not a guaranteed speedup for every scene. [Arm Mobile GPU 101](https://developer.arm.com/cfs-file/__key/communityserver-blogs-components-weblogfiles/00-00-00-20-66/siggraph_5F00_mmg_5F00_2024_5F00_Mobile101.pdf)

| Gate | Acceptance criterion |
|---|---|
| Functional | Reference-image/numeric checks for formats, MSAA, order, hazards, faults and overflow; randomized backpressure and reordered memory responses |
| Texture throughput | Each cluster reaches one bilinear RGBA8 result/cycle on a long conflict-free resident-data stream; separately report trilinear, misses, bank conflicts and dependent samples |
| Pixel throughput | Each engine reaches four RGBA8+D24S8 sample updates/cycle on a long conflict-free resident stream; include enabled blend/depth/stencil cases and report supported rates per mode |
| Attachment locality | An eligible clear/discard-start pass avoids old attachment reads; blending/depth intermediates stay on chip; final stores/resolves match the requested outputs |
| CU scaling | Measure a specified long shader-bound 1-to-2-CU test with fixed non-CU resources and identical work; attribute the remaining gap with counters. The historical 1-to-4-CU target/result remains optional scaling evidence |
| Balanced scaling | Test 1/2 CUs with proportional texture/tile resources; retain a realistic fixed external-bandwidth case and identify its saturation point. Four-CU sweeps are optional |
| Physical | Meet setup/hold and I/O constraints at the selected clock, with SRAM assumptions and implementation stage reported; compare area and energy/frame |
| Product workload | Meet 60 FPS at 640×480 on two CUs for all six selected workloads at a qualified complete-host clock, including binning, geometry, rendering and store/resolve |

The current qualified textured scene is 32,341 cycles on one CU and 11,566 on four, or about 2.80x. It is a retained regression workload, not a general theoretical scaling limit. Qualification handoff

The separate [fixed-resource integer shader qualification](FIXED_RESOURCE_CU_SCALING.md)
passes the specified shader-bound target at 3.374x: 382,860 / 193,556 / 113,483
cycles on 1/2/4 CUs with four workers, eight scratch banks and identical work.
This legacy-renderer result does not establish native concurrency, whole-host
clock timing, balanced scaling or completion of H0–H8.

Current texture traces found a peak of 10 occupied slots out of 16 and about 0.06 percent cross-CU duplicate candidates in the captured desktop workload. Prioritize service bandwidth and bank conflicts over owner-table growth or duplicate multicast for that workload; these opportunity counters do not establish a causal speedup. Texture contention measurements

Existing OM screening also failed the 1 GHz timing gate for both baseline and sharing candidates. Use that result to justify pipelined admission/address/hazard control. A design with fewer cycles must also improve or preserve elapsed time at its achievable clock. Paused physical screening

## Implementation milestones

| Milestone | Deliverable | Exit evidence | Primary RTL touchpoints |
|---|---|---|---|
| H0 | Hardware capability/configuration record, descriptor definitions, counters and analytic workload budgets | Units and limits documented; supported elaboration combinations checked; baseline counters explain stage utilization | `GpuConfig`, `GraphicsConfig`, `JobConfig`, `SharedCuGraphicsPort`, performance bundles |
| H1 | One tile engine with pass-lifetime attachments and versioned state | Multiple ordered draws retain attachments; clear/load/store/discard/resolve and dependency boundaries are correct | `TileAttachmentStore`, `RenderPipeline`, render descriptors |
| H2 | Scalable bin pages and two independently progressing tile engines | Overflow/spill/restart, ordering, load balancing, double buffering and backpressure pass; attachment bytes/frame measured | Replacement/evolution of `TileDrawBinner`; new tile scheduler and transfer engine |
| H3 | Banked pixel backend, legal early rejection and pipelined hazards | Pixel throughput gate, adversarial same-sample ordering, early/late equivalence and physical timing | `OutputMerger`, tile SRAM, sample expansion and retirement |
| H4 | Banked texture clusters with general resource descriptors | Cache-hit throughput gate, miss/translation stress, multiple bindings and render-to-texture visibility | `TextureUnit`, `FragmentTextureArbiter`, `BufferedTextureWordPort`, translation routing |
| H5 | On-chip shader payload/export path and GPU geometry fetching | No mandatory per-fragment global staging in the common path; index/instance reuse, fault drain and task progress verified | `KernelFragStage`, `ParallelKernelFragStage`, shared task router, vertex stages and CU interfaces |
| H6 | Required format/primitive/resource coverage and product qualification | Hardware feature matrix closed, targeted API integration tests, two-CU 640×480/60 FPS six-scene suite and matching physical reports | Format, texture, raster, execution, query and synchronization blocks |
| H7 | GLES 3.1 hardware closure | Storage/image access, atomics, barriers, indirect work and resource-limit matrix verified | CU memory, descriptor tables, command processor and shared cache/translation fabric |
| H8 | GLES 3.2 hardware closure | Geometry/tessellation, bounded amplification, stage ordering and remaining feature matrix verified | Shared task scheduling, patch SRAM, primitive generation and graphics backends |

H3 and H4 depend on the H0 interfaces but can be developed against unit harnesses before H2 completes. H5 payload and geometry changes should be separate reviewable series. Bring up one attachment and existing formats first; add MRT and the wider format contract after ownership and ordering are stable. Apply synthesis/timing gates throughout. Place-and-route and broader physical closure belong to later qualification under the current synthesis-only development policy.

The first implementation slice should be **H0 plus H1**: define the hardware contract, then make several compatible draws render into one resident tile without intermediate attachment writeback. This creates a measurable bandwidth result and a stable base for subsequent parallel engines.

For H1, use overlapping triangles from multiple draws with blending and depth/stencil enabled, all within one tile. After a clear-start pass, require zero external reads of the cleared attachment contents and no intermediate attachment writes before the pass ends. Compare final stored samples against the existing ordered path. Repeat with a load-start pass, partial clear, texture/DMA dependency boundary, cancellation and a store fault. These checks distinguish persistent tile lifetime from merely changing triangle traversal order.

## Verification and delivery discipline

Routine render workloads use at most **640×480**, with smaller images preferred
for functional checks. Shared frame-scale fixtures select this size by default;
PR and nightly CI explicitly keep product qualification disabled. Use
`-Dopengpu.productQualification=1` only for a separate, deliberate 1280×720
extended qualification run. The mandatory product target is two CUs at
640×480/60 FPS, while supported RTL and API capacities remain separate from
workload resolution. Bounded numeric/address boundary checks do not need
to render a full-size frame. See [development testing](DEVELOPMENT_TESTING.md).

Add counters for tile residency/evictions, attachment bytes by operation, bin allocation/spill, active engines, primitive occupancy, payload traffic, queue stalls, eligible/rejected early tests, texture bank conflicts, useful cache bytes, fill occupancy and per-CU active/helper lanes. Avoid summing overlapping stall counters as if they were exclusive causes.

Use arithmetic/ordering proofs and component tests first, followed by small cycle benchmarks, synthesis, native trace replay and selected full-system smoke tests. Run full desktop validation at integration milestones. Freeze source/configuration and workload hashes for comparisons; report simulator wall time separately from GPU cycles and estimated silicon frame time.

Deliver each milestone as independently reviewable changes with its functional and throughput evidence. Keep the currently qualified desktop path available during bring-up. This proposal does not promote the paused private OM experiments into production RTL.

