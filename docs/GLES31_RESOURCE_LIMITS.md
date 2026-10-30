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

| API limit | Required limit | Current hardware contract and remaining qualification |
|---|---:|---|
| `MAX_COMPUTE_WORK_GROUP_COUNT` | 65535 on each axis | GCR1 reports unsigned 32-bit axes. Admission preserves their full width; distribution and completion at boundary counts still need bounded witnesses. |
| `MAX_COMPUTE_WORK_GROUP_SIZE` | 128, 128, 64 | GCR1 reports a common axis bound of 128 for the default profile. Exact-product admission prevents multiplying independent axis maxima into an unsupported group. |
| `MAX_COMPUTE_WORK_GROUP_INVOCATIONS` | 128 | One resident group per CU; 16 × 8 resident threads. Admission covers 128 and rejects overflow. Full-resource composed execution remains required. |
| `MAX_COMPUTE_SHARED_MEMORY_SIZE` | 16384 bytes | Configured per CU. The actual-CU SRAM test touches both ends using eight warps, vector stores/loads and AMOs. General shared-variable layout and barrier visibility remain required. |
| `MAX_COMPUTE_UNIFORM_BLOCKS` | 12 | [Compute uniform execution](COMPUTE_UNIFORM_EXECUTION.md) checks twelve mapped blocks in direct/indirect four-CU workgroups, alongside storage, images and textures. Native host/API integration and broader ordering remain open. |
| `MAX_COMPUTE_UNIFORM_COMPONENTS` | 1024 | The four-CU coexistence test reads the final component of a checked 1024-component default view. Native host/API integration remains open. |
| `MAX_COMBINED_COMPUTE_UNIFORM_COMPONENTS` | 50176 at minimum limits | The four-CU test accesses the last word of twelve 16 KiB blocks and the last default component, with held publication ownership. Aggregate API admission and broader resource/visibility qualification remain open; recalculate when reported limits increase. |
| `MAX_COMPUTE_TEXTURE_IMAGE_UNITS` | 16 | [Compute texture execution](COMPUTE_TEXTURE_EXECUTION.md) captures stage-2 resources and exercises sixteen bindings in direct/indirect four-CU, 128-thread workgroups with uniforms, storage and images. Host/API integration, ordering and physical qualification remain open. |
| `MAX_COMPUTE_IMAGE_UNIFORMS` | 4 | Four GIU1 units execute in direct/indirect four-CU, 128-thread workgroups alongside textures, uniforms and storage. Complete image semantics and ordering remain to be qualified. |
| `MAX_COMPUTE_SHADER_STORAGE_BLOCKS` | 4 | [Checked storage word execution](COMPUTE_STORAGE_EXECUTION.md) exercises four task-owned bindings in direct/indirect four-CU, 128-thread workgroups alongside textures, uniforms and images. Host admission and complete storage semantics remain open. |
| `MAX_COMPUTE_ATOMIC_COUNTER_BUFFERS` | 1 | Open: generic word AMOs do not supply an atomic-counter binding/offset namespace. |
| `MAX_COMPUTE_ATOMIC_COUNTERS` | 8 | Open: qualify distinct counters, contended updates, returned values and completion visibility. |

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
The existing one-bit stage and two-bit stage mask do not represent compute.

| API limit | Required limit | Current hardware contract and remaining qualification |
|---|---:|---|
| `MAX_UNIFORM_BUFFER_BINDINGS` | 36 | Open: the native table has 24 entries. Qualify wider direct indexing or lossless API-to-task remapping of every legal active set. |
| `MAX_UNIFORM_BLOCK_SIZE` | 16384 bytes | Checked graphics and compute views exist; full-profile capacity and composed resource ownership remain open. |
| `UNIFORM_BUFFER_OFFSET_ALIGNMENT` | At most 256 bytes | The existing 256-byte graphics block alignment fits this ceiling; qualify the successor compute/context path. |
| `MAX_COMBINED_UNIFORM_BLOCKS` | 24 | Vertex/fragment program maps exist. Compute linkage and binding ownership remain open. |
| `MAX_COMBINED_VERTEX_UNIFORM_COMPONENTS` | 50176 at minimum limits | Existing twelve-block/default graphics views; retain full-capacity execution qualification. |
| `MAX_COMBINED_FRAGMENT_UNIFORM_COMPONENTS` | 50176 at minimum limits | Same coupled accounting, independently for the fragment stage. |
| `MAX_VARYING_COMPONENTS` / `MAX_VARYING_VECTORS` | 60 / 15 | Native transport permits 64 raw components; packing, built-ins and linkage still require the graphics feature matrix. |
| `MAX_COMBINED_TEXTURE_IMAGE_UNITS` | 48 | Open: the native 32-slot stage namespace does not establish combined vertex/fragment/compute accounting. |
| `MAX_COMBINED_SHADER_OUTPUT_RESOURCES` | 4 | Open: account for the applicable image, storage-block and fragment-output combination at admission. |
| `MAX_UNIFORM_LOCATIONS` | 1024 | API/linker namespace; hardware must back the linked values with checked stage views. The compute view remains open. |
| `MAX_ATOMIC_COUNTER_BUFFER_BINDINGS` | 1 | Open: versioned checked counter view and discovery. |
| `MAX_ATOMIC_COUNTER_BUFFER_SIZE` | 32 bytes | Open: eight independent 32-bit counters with checked view bounds. |
| `MAX_COMBINED_ATOMIC_COUNTER_BUFFERS` | 1 | Open: linked-program accounting and immutable binding ownership. |
| `MAX_COMBINED_ATOMIC_COUNTERS` | 8 | Open: counter linkage, offsets and actual concurrent execution. |
| `MAX_IMAGE_UNITS` | 4 | Four units execute in the compact two-CU native host; full-profile coexistence and ordering remain open. |
| `MAX_COMBINED_IMAGE_UNIFORMS` | 4 | Open: program-level image accounting and full-unit execution. |
| `MAX_SHADER_STORAGE_BUFFER_BINDINGS` | 4 | Open: checked GPU-backed descriptor namespace. |
| `MAX_SHADER_STORAGE_BLOCK_SIZE` | 134217728 bytes | Open: 32-bit addressing alone does not establish a 128 MiB checked block. Boundary probes need not traverse the entire block. |
| `MAX_COMBINED_SHADER_STORAGE_BLOCKS` | 4 | Open: linked-program accounting and simultaneous binding execution. |
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
sbt 'testOnly opengpu.system.GpuSystemTextureResourceCoexistenceSpec'
sbt -J-Xmx3G -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.GpuSystemFullTextureResourceCoexistenceSpec'
```

These cover admission/discovery, shared SRAM boundary execution, disabled-task
sampler rejection and compact and full-profile coenabled resource execution. The
full-profile case runs locally, outside PR and nightly CI. They do not complete the open rows. Descriptor-only
image checks likewise do not substitute for actual full-capacity CU execution.

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
