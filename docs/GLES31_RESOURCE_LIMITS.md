# GLES 3.1 compute and aggregate resource limits

This is the quantitative requirement map for E31-01–04 and H7. H7 remains
open. GCR1 reports an implemented compute profile; it does not advertise GLES
support. Optional image-command transport likewise does not establish a
complete resource contract.

The numeric requirements use [GLES 3.1, November 3, 2016, tables 20.45–20.47](https://registry.khronos.org/OpenGL/specs/es/3.1/es_spec_3.1.pdf#page=421).
Values are minimum supported limits unless marked as alignment ceilings.
Inherited vertex/fragment and texture/attachment limits remain in the
[resource map](MOBILE_GPU_RESOURCE_LIMITS.md). The later-stage extension is
covered separately by the [3.2 contract](GLES32_STAGE_RESOURCE_LIMITS.md).
None of these counts requires a dedicated parallel execution unit per binding.
An API namespace may also map to a smaller task-local hardware namespace if
the complete legal active set fits and immutable publication is preserved.
Ordinary CU loads/AMOs through a validated ABI can supply resource operations;
they need evidence for limits, ownership and visibility before qualifying a row.
The original alignment plan excludes compiler and driver implementation.

## Compute stage

The current default and mobile execution profiles have 16 lanes, eight warps
and 16 KiB shared memory per CU. Compact test profiles can be smaller and must
not inherit the default profile's limits. The implementation column records
source-level bounds and existing directed coverage, not complete API validation.
The [native two-CU system witness](NATIVE_SYSTEM_RESOURCE_PUBLICATION.md) now
composes the mandatory active set through `GpuSystem` unified commands with
default caches, real SRAM/FPU and L2. It covers scoped cross-CU resource
publication, not the remaining host, graphics/DMA, broader four-CU product or
API gates. The [four-CU coexistence run](COMPUTE_RESOURCE_COEXISTENCE.md#four-cu-coexistence)
retains the same complete mandatory set on all four stock CUs simultaneously
through one shared interconnect and real L2. The [four-CU product shared-memory run](GALLIUM_SHARED_COMPUTE.md)
adds one product-path witness for the workgroup size, invocation and 16 KiB
shared-memory rows below.

| API limit | Required limit | Current hardware contract and remaining qualification |
|---|---:|---|
| `MAX_COMPUTE_WORK_GROUP_COUNT` | 65535 on each axis | GCR1 reports unsigned 32-bit axes and admission preserves their full width. A dispatcher witness distributes and retires all 65535 workgroups on each axis in order. Native CU execution of a full boundary grid, product capacity and API accounting remain open. |
| `MAX_COMPUTE_WORK_GROUP_SIZE` | 128, 128, 64 | GCR1 reports a common axis bound of 128 for the default profile. Hardware and [direct Linux admission](COMPUTE_RESOURCES.md#linux-direct-compute-workgroups) check the complete product; userspace can query the driver's effective limit. A bounded Linux guest passed on an existing model. The [four-CU product shared-memory run](GALLIUM_SHARED_COMPUTE.md) submits 128x1x1 workgroups through unified commands. Current-hardware and full-resource qualification remain open. |
| `MAX_COMPUTE_WORK_GROUP_INVOCATIONS` | 128 | One resident group per CU; 16 × 8 resident threads. Hardware and direct Linux admission cover 128 and reject overflow. The [native coexistence witness](COMPUTE_RESOURCE_COEXISTENCE.md) executes 128 work-items with the mandatory resource set on one default CU and real L2. The [four-CU product shared-memory run](GALLIUM_SHARED_COMPUTE.md) executes 128-invocation workgroups through unified commands. Host/API and physical qualification remain open. |
| `MAX_COMPUTE_SHARED_MEMORY_SIZE` | 16384 bytes | The [native coexistence witness](COMPUTE_RESOURCE_COEXISTENCE.md) and [bounded Linux shared qualification](COMPUTE_SHARED_PROTECTION.md) write all 4096 words and read them from different warps after a barrier with the full resource set. The [four-CU product shared-memory run](GALLIUM_SHARED_COMPUTE.md) executes the same all-16-KiB programs through unified commands with exact outputs. Linux admission requires typed local provenance and definite initialization on every CFG path. General shared-variable layout and complete barrier semantics remain open. |
| `MAX_COMPUTE_UNIFORM_BLOCKS` | 12 | [Uniform execution](COMPUTE_UNIFORM_EXECUTION.md), the [two-default-CU host witness](NATIVE_HOST_RESOURCE_PUBLICATION.md) and the [Linux resource-view guest](LINUX_NATIVE_RESOURCE_EXECUTION.md) retain twelve simultaneous 16 KiB views with independent endpoints. Linux now executes those snapshots through the actual driver lifecycle. API linkage and physical qualification remain open. |
| `MAX_COMPUTE_UNIFORM_COMPONENTS` | 1024 | Every distinct default offset executes beside twelve full blocks and the other resource services in the native CU/host witnesses and Linux guest. The Linux producer writes all 1024 values on the GPU before the consumer snapshot. Compiler/API support and physical qualification remain open. |
| `MAX_COMBINED_COMPUTE_UNIFORM_COMPONENTS` | 50176 at minimum limits | Native CU/host and Linux witnesses retain `12 * 16384 / 4 + 1024 = 50176` components. Block endpoints are sampled; every default component executes. Linux's independent counter source preserves all four SSBO windows alongside this set. Complete profile coexistence, API accounting and physical qualification remain open; recalculate when reported limits increase. |
| `MAX_COMPUTE_TEXTURE_IMAGE_UNITS` | 16 | [Submission](COMPUTE_TEXTURE_SUBMISSION.md) retains up to 32 views. The [native coexistence witness](COMPUTE_RESOURCE_COEXISTENCE.md) executes nearest R32UI samples and typed fetches through sixteen independent checked view/sampler records, real Sv32 and L2. Broader sampler/format coverage, whole-system coherence and API/physical qualification remain open. |
| `MAX_COMPUTE_IMAGE_UNIFORMS` | 4 | Four GIU1 units execute loads, stores and atomic adds on the default CU in the [native coexistence witness](COMPUTE_RESOURCE_COEXISTENCE.md), with storage/texture aliases and real L2. Broader formats, whole-system coexistence and complete ordering/API qualification remain open. |
| `MAX_COMPUTE_SHADER_STORAGE_BLOCKS` | 4 | Four task-owned 128 MiB GSB1 views execute sparse boundary loads, stores and atomic adds in the [native coexistence witness](COMPUTE_RESOURCE_COEXISTENCE.md), alongside all mandatory services. [Linux submission](COMPUTE_STORAGE_SUBMISSION.md) retains four backings. Complete storage semantics, whole-system/API and physical qualification remain open. |
| `MAX_COMPUTE_ATOMIC_COUNTER_BUFFERS` | 1 | [Counter submission](COMPUTE_ATOMIC_COUNTER_SUBMISSION.md) supplies a retained GAM1 table using checked GSB1 binding zero. The [native coexistence witness](COMPUTE_RESOURCE_COEXISTENCE.md) retains its one 32-byte counter view with all mandatory services through held completion. API linkage, whole-system and physical qualification remain open. |
| `MAX_COMPUTE_ATOMIC_COUNTERS` | 8 | All eight translated counters execute through real L2 in the [native coexistence witness](COMPUTE_RESOURCE_COEXISTENCE.md): sixteen contending increments/decrements per counter, exact returned-value sets, barrier-separated reads and restored backing words. Broader contention/API and whole-system/physical qualification remain open. |

Source anchors: [executing profile](../src/main/scala/opengpu/config/ComputeResourceProfile.scala),
[GCR1 fields](../src/main/scala/opengpu/system/ComputeResourceCapabilities.scala),
[task admission](../src/main/scala/opengpu/system/GpuSystem.scala),
[image descriptors](SHADER_IMAGE_DESCRIPTORS.md),
[native image commands](SHADER_IMAGE_COMMANDS.md) and
[word-atomic admission](COMPUTE_WORD_ATOMIC_ADMISSION.md).

## Aggregate namespaces and coupled limits

Binding slots, linked-program usage and storage capacity are different limits.
Existing vertex/fragment resources do not imply a compute binding or enlarge
the context namespace. The native uniform ABI has 24 bindings and five-bit
mapping entries. Directly exposing 36 indices needs a compatible successor;
remapping API indices needs a qualified publication/admission contract instead.
Legacy graphics records use a one-bit stage and two-bit stage mask. The checked
compute reader extends those fields to represent stage two and its permission
bit; this does not enlarge the 24-entry native binding namespace.

| API limit | Required limit | Current hardware contract and remaining qualification |
|---|---:|---|
| `MAX_UNIFORM_BUFFER_BINDINGS` | 36 | Open: the native table has 24 entries. Qualify wider direct indexing or lossless API-to-task remapping of every legal active set. |
| `MAX_UNIFORM_BLOCK_SIZE` | 16384 bytes | Native 128-item CU and bounded host witnesses retain twelve 16 KiB views with independent first/last words, including a native view ending at 4 GiB. Linux publication copies each complete 16 KiB view into a job-owned allocation. Broader whole-system/API execution and physical qualification remain open. |
| `UNIFORM_BUFFER_OFFSET_ALIGNMENT` | At most 256 bytes | The existing 256-byte graphics block alignment fits this ceiling; qualify the successor compute/context path. |
| `MAX_COMBINED_UNIFORM_BLOCKS` | 24 | Vertex/fragment program maps exist. Compute linkage and binding ownership remain open. |
| `MAX_COMBINED_VERTEX_UNIFORM_COMPONENTS` | 50176 at minimum limits | Existing twelve-block/default graphics views; retain full-capacity execution qualification. |
| `MAX_COMBINED_FRAGMENT_UNIFORM_COMPONENTS` | 50176 at minimum limits | Same coupled accounting, independently for the fragment stage. |
| `MAX_VARYING_COMPONENTS` / `MAX_VARYING_VECTORS` | 60 / 15 | Native transport permits 64 raw components; packing, built-ins and linkage still require the graphics feature matrix. |
| `MAX_COMBINED_TEXTURE_IMAGE_UNITS` | 48 | Open: the native 32-slot stage namespace does not establish combined vertex/fragment/compute accounting. |
| `MAX_COMBINED_SHADER_OUTPUT_RESOURCES` | 4 | Open: account for the applicable image, storage-block and fragment-output combination at admission. |
| `MAX_UNIFORM_LOCATIONS` | 1024 | API/linker namespace remains unqualified. The native compute default view now executes all 1024 checked component offsets; API locations and linked stage values still need their own admission contract. |
| `MAX_ATOMIC_COUNTER_BUFFER_BINDINGS` | 1 | GAM1 and GCR1 discover the opt-in checked counter view; Linux requires that transport, unified commands and protected VM state. One native-CU counter binding executes with the other mandatory resources in the [coexistence witness](COMPUTE_RESOURCE_COEXISTENCE.md). API binding/linkage and whole-system qualification remain open. |
| `MAX_ATOMIC_COUNTER_BUFFER_SIZE` | 32 bytes | The counter ioctl accepts one to eight adjacent words with checked offset/backing bounds. The [native coexistence witness](COMPUTE_RESOURCE_COEXISTENCE.md) executes every word of a translated 32-byte view through real L2; components also cover an exclusive 4 GiB endpoint. API and whole-system qualification remain open. |
| `MAX_COMBINED_ATOMIC_COUNTER_BUFFERS` | 1 | A captured counter view retains its mutable backing through retirement. [Independent Linux source admission](COMPUTE_COUNTER_SOURCE_SUBMISSION.md) preserves four full SSBO windows and all uniform/texture/image views; actual-function/module checks and the [Linux resource-view guest](LINUX_NATIVE_RESOURCE_EXECUTION.md) pass. Linked-program, cross-stage accounting and full qualification remain open. |
| `MAX_COMBINED_ATOMIC_COUNTERS` | 8 | The native namespace has eight checked offsets; all eight contend in one 128-item default-CU task with the other mandatory services. API linkage, graphics-stage admission, whole-system and physical qualification remain open. |
| `MAX_IMAGE_UNITS` | 4 | All four units execute in the [default-CU coexistence witness](COMPUTE_RESOURCE_COEXISTENCE.md); a compact two-CU native-host fixture also exists. Whole-system coexistence, broad formats and complete ordering remain open. |
| `MAX_COMBINED_IMAGE_UNIFORMS` | 4 | The [native coexistence witness](COMPUTE_RESOURCE_COEXISTENCE.md) executes four compute-stage image units on one CU. Linked-program/API accounting and whole-system qualification remain open. |
| `MAX_SHADER_STORAGE_BUFFER_BINDINGS` | 4 | Four retained GPU-backed GSB1 bindings execute on a default native CU with all mandatory services in the [coexistence witness](COMPUTE_RESOURCE_COEXISTENCE.md); compact host/Linux tests also exist. Whole-system/API binding and physical qualification remain open. |
| `MAX_SHADER_STORAGE_BLOCK_SIZE` | 134217728 bytes | The checked component accepts a 128 MiB view ending at 4 GiB and checks its last/first-out-of-range words, oversize and permission rejection. The [native coexistence witness](COMPUTE_RESOURCE_COEXISTENCE.md) translates four simultaneous 128 MiB views and executes sparse first/last words alongside all mandatory services. Host/API and whole-system qualification remain open. |
| `MAX_COMBINED_SHADER_STORAGE_BLOCKS` | 4 | Four simultaneous compute bindings execute in the [native coexistence witness](COMPUTE_RESOURCE_COEXISTENCE.md). Linked-program/API accounting and whole-system qualification remain open. |
| `SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT` | At most 256 bytes | Open: descriptor alignment and nontruncating range admission. |

The stage uniform relationship is `blocks * blockBytes / 4 + defaultComponents`;
`12 * 16384 / 4 + 1024 = 50176`. The vertex and fragment minima for image
uniforms, storage blocks, atomic-counter buffers and atomic counters are all
zero in tables 20.43–20.44. Positive support in those stages is optional and
needs its own qualified admission. Compute's positive requirements above
remain mandatory. Graphics image admission must not be mistaken for a positive
GLES 3.1 minimum or silently enabled by a compute image flag.

## Acceptance evidence

The existing directed regressions are reproducible with:

```sh
sbt 'testOnly opengpu.dispatch.ComputeResourceAdmissionSpec opengpu.system.ComputeResourceCapabilitiesAxiSpec'
sbt 'testOnly opengpu.core.GpuComputeUnitSharedSramSpec opengpu.system.ComputeTextureAvailabilitySpec'
```

These cover admission/discovery, shared SRAM boundary execution and deliberate
compute sampler rejection. They do not complete the open rows. Descriptor-only
image checks likewise do not substitute for actual full-capacity CU execution.

The explicit [two-CU system qualification](NATIVE_SYSTEM_RESOURCE_PUBLICATION.md)
passes direct and indirect parents, each with two concurrent 128-item groups
using all twelve 16 KiB uniform blocks, 1024 defaults, sixteen texture views,
four image units, four 128 MiB storage views, eight counters and 16 KiB shared
storage per group. Exact cross-CU counter sets and ready/fence/publication
dependencies precede checked image/storage/texture/cached-alias reads. Each
healthy parent accounts for 984 vector results, 192 scalar endpoints, 512 atomic
results and 772 delayed lower write acknowledgements; an intervening bounds
fault produces eight precise warp traps without writes. All five leases and
public completions remain stable under backpressure, followed by healthy reuse
with a fresh VM root. This strengthens native command-system evidence without
qualifying host MMIO/software fences, API namespaces or physical timing. It is
property gated and excluded from routine PR/nightly discovery.

The explicit [two-CU host qualification](NATIVE_HOST_RESOURCE_PUBLICATION.md)
also passes all five resource publications through real MMIO/SUBMIT, protected
instruction/data VM roots, held software completion/IRQ and POP before reuse.
Each healthy parent checks 20,384 uncached result words, 772 original plus
1274 result-line acknowledgements and 4092 W beats on the 256-bit lower AXI
port. Cross-CU publication and the cached-alias check retain the full resource
counts. This closes that bounded host path; Linux admission/compiler/reservation
fences, API namespaces, graphics/DMA/four-CU coexistence and physical gates
remain open. It adds no routine CI case or frame render.

The [Linux resource-view qualification](LINUX_NATIVE_RESOURCE_EXECUTION.md) now
passes two healthy 128-item-per-group parents and an intervening bounds fault
on a real two-default-CU, SRAM/FPU, 256-bit AXI host model. It retains twelve
full blocks, all 1024 defaults, 32 textures, four images, four independent
128 MiB SSBOs and eight independent original-GEM counters, checking 10,240
result words per healthy parent plus backing/guards and a texture consumer.
It confirms actual two-CU discovery and a process-lifetime active-CU peak of
two, without attributing that peak to each resource kernel. The separate
[Linux shared qualification](COMPUTE_SHARED_PROTECTION.md) retains every view
and all forty resource planes, then adds thirty-two planes to check every
shared word from another warp. Its two healthy epochs and intervening fault
pass on the retained hardware-base `00197df1` model with `aed4b012` Linux
software plus the shared patch; matching hardware inputs predate the later
scheduler change. This is not a current-scheduler requalification. General shared layouts/semantics,
forced reservation-wait attribution, compiler/API and complete H7 gates remain
open; no default CI GPU case is added.

For each new resource path, require exact-limit acceptance, over-limit rejection
before memory access, nontruncating end-of-address-space checks, distinct live
bindings, held offers/results, primary faults, cancellation/reset drain and
healthy reuse. Exercise actual shared CUs with protected translations and
coenabled resources. Qualify visibility across the relevant cache, image,
texture, attachment and DMA domains. Discovery must report the executing
profile, preserve old ABI behavior and keep unavailable features disabled.

Use sparse boundary data and bounded kernels for resource-capacity checks;
these are not full-frame performance qualifications. Relevant component and
enclosing physical results, targeted API integration and the original H6/H7
acceptance gates remain required. Keep raw evidence local under `.artifacts/`.
