# Tessellation execution work packages

Two independent production gaps require P39 and P40 in the alignment PR plan.
Neither is covered by the P15 checked-uniform implementation, P16 private
program/resource admission or P17 Mesa lowering. Both remain unimplemented and
unqualified. Preserve the existing work-package IDs and acceptance conditions.

## P39: execute the original vertex shader before patch publication

The existing `TessellationSystemIsolineInputImage` task contains a raw
`VertexInputView` and stride. It sends those words through
`TessellationPatchInputDma` and seals the patch after DMA success. Triangle and
quad input paths use the same approach. No original VS is launched by this
source path. A CPU-generated patch can diagnose downstream stages but cannot
qualify the VS→TCS/TES graphics chain.

The production bridge must use the actual `VertexShaderStage` draw, shared CU,
payload and memory interfaces. Its raw `ShadedVertexRecord` position/varying
stream and independent `VertexShaderCompletion` supply the publication input
and retirement boundary. Map linked components into the owned patch, without
silently truncating applicable built-ins or user values. Keep the limits and
packing rules in [the stage resource contract](GLES32_STAGE_RESOURCE_LIMITS.md).

Required acceptance:

- Preserve draw identity, vertex/instance/index/restart order and linked input
  layout; publish only actual successful VS outputs.
- Seal input only after every accepted patch write has an acknowledged reply,
  the original VS has completed successfully and its required traffic has
  drained. A held output or reply keeps the complete source owner alive.
- Carry an explicit, versioned original-VS source descriptor through protected
  admission and discovery. Existing raw-input GNQ/GNT records do not authorize
  this new path; raw caller PCs and addresses cannot supply that authority.
- Cover held offers, first fault, source/patch bounds, cancellation, shared-CU
  arbitration, late responses, whole-owner cleanup and healthy no-reset reuse
  with two physical CUs and unrelated owners.
- Execute original ESSL VS→actual CU→owned patch→actual TCS/TES→image on all
  three domains. Mutating the original VS computation must change the
  independently checked final result. Precomputed CPU patch words are not
  execution evidence.

Implementation depends on P15–P17; full-chain acceptance also requires P40 and
a fresh P04 factory/model binding. P18, P25, P32, P37 and P38 retain this
dependency for their joint tessellation/graphics acceptance.

## P40: dynamically read the complete owned patch from TES

`IsolinePatchInputReader` currently selects at most 61 patch words before each
evaluation. `IsolineGeometryCuExecutor` places them after three tessellation
coordinate words in geometry input vertex 0. Triangle and quad evaluators
reuse this transport. It cannot implement general dynamically indexed
`gl_in[]` or the complete per-patch input contract. Raising 61 to 64 leaves
the coordinate collision and full-patch indexing unresolved.

Provide a TES task-owned, checked, read-only patch service and the corresponding
ISA/compiler/validator contract. Keep coordinates independently accessible.
The executing evaluation owns its patch and resource identity through accepted
requests, replies, writeback, actual CU completion and cancellation drain. It
cannot inherit TCS write/barrier permissions or unrelated GS emission/ID/layer
permissions. Version and discover the new interface explicitly; preserve old
record and instruction contracts. ISA additions require matching work in the
independent LLVM repository, including emission, decoding and tests.

Required acceptance:

- Read all legal 32-vertex, 64-component layouts, 120 per-patch components and
  six tessellation levels with dynamic vertex/component selection. Preserve
  the combined TCS output bound of 2048 counted components: `32*64+0` and
  `32*60+120` fit; `32*61+120` does not.
- Exercise vertices 0/31, components 0/63, per-patch 0/119 and levels 0/5;
  reject vertex 32, component 64, patch component 120, level 6, wrong identity,
  illegal selectors and any write before accessing another owner's storage.
- Keep divergent active-lane requests, inactive lanes, held requests/results,
  faults, first-fault priority, cancellation and late responses correct. Drain
  accepted traffic before freeing or reusing the patch, CU slot or command ID.
- Run original dynamically indexed ESSL through the real Mesa/native path and
  actual CU for isoline, triangle and quad evaluation. Use independent source
  arithmetic and final geometry/image oracles, source mutation, two owners and
  healthy no-reset reuse. A descriptor parser, CPU interpreter or selected-word
  fixture does not satisfy this gate.

Implementation depends on P15–P17. P18, P25, P26, P32, P37 and P38 retain the
full-patch service as an explicit dependency. Any new RTL/ABI requires a fresh
P04 binding before target execution or product qualification is reported.

## Validation scope

These gaps follow from the production source interfaces above; this document
records requirements, not passing RTL or product results. Reproduce the source
inspection with:

```sh
rg -n 'VertexInputView|inputDma|seal|patch.layout' \
  src/main/scala/opengpu/system/TessellationSystem*InputImage.scala
rg -n 'ShadedVertexRecord|VertexShaderCompletion|kernelDone|payload' \
  src/main/scala/opengpu/graphics/VertexShaderStage.scala
rg -n '61|patchWords|patchCount|inputComponents' \
  src/main/scala/opengpu/system/IsolinePatchInputReader.scala \
  src/main/scala/opengpu/system/IsolineGeometryCuExecutor.scala
```

Rendering tests follow `GraphicsTestResolution` and remain at or below 640×480.
The mandatory product target remains 2 CUs, 640×480 and 60 FPS. Keep
`Scala regression` disabled; use existing protected PR CI, focused local tests
and explicitly scoped qualification, leaving every unexecuted gate pending.
