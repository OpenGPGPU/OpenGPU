# Typed tile images from post-transform replay

`TypedReplayTileImagePipeline` consumes a stream of resolved
`WindowPrimitiveReplay` records for one tile. It fetches
[GFS1 fragment state](TYPED_FRAGMENT_STATE_ABI.md), selects the engine's private
scratch view, clips the raster scissor to the tile, and uses the existing typed
image owner for raster/fragment execution, late tests, blending, residency,
source stores and resolves. Vertex input, shading, assembly, clipping and
viewport preparation are not instantiated in this path.

## Binding and records

`TypedReplayTileDescriptor` captures the typed image descriptor, tile ID,
immutable state table, engine count, per-engine scratch arenas/stride and three
protected memory spans. Spans describe actual record, page and directory
allocations; the enclosing owner supplies and retains them. Empty protected
spans are allowed. These fields are an internal component interface, not a new
host descriptor layout.

The state table and active engine scratch arenas must be bounded, aligned,
large enough for all state slots, mutually disjoint and disjoint from protected
memory. The descriptor's identities must match. The image descriptor and backing
views retain the existing typed image admission rules. Those physical views
remain trusted pinned allocator metadata; this component does not replace
allocation provenance or VM permission admission.

Each input record must match the bound identity and tile, carry the next tile
ordinal, and retain matching state handle, pass sequence and token in its bin
reference and payload. Sequences strictly increase, with gaps allowed. Header,
state identity, shader types/versions and scratch bounds are checked before
shader execution. Every state view must also fit its engine-and-handle slot.

The original window primitive remains unchanged. Raster scissor bounds are
intersected with the tile rectangle and elaborated/runtime screen limits using
widened signed arithmetic before narrowing. This retains the original draw
scissor while preventing helper/covered lanes from updating another tile.
Triangle, point and line rasterization and sample-depth interpolation use the
same `Fp32PrimitiveRasterStage` as the ordinary path.

## Prepared geometry mode

`TypedTileImagePipeline`, `TypedTileRenderPipeline` and
`VertexFragmentPipeline` have an optional `preparedGeometry` elaboration mode.
The default keeps the existing vertex pipeline. Prepared mode associates a
complete `WindowPrimitive` sideband with each accepted draw, captures it, and
starts at primitive rasterization. The unused vertex CU/payload client stays
quiet; the existing fragment client, task router, output protocol and typed
pixel/image ownership remain in use.

`VertexFragmentCompletion.preparedGeometry` identifies that mode. Its vertex
summary reports the actual raster/interpolation counters with zero nested
vertex-execution counters. A prepared tile primitive therefore does not claim
that another vertex shader ran. The original shader ISA, scalar/register ABI,
SoA offsets and default native GTJ1/GTI1/GTD1 layouts remain unchanged.

The existing two logical payload ports are retained. Prepared mode uses the
fragment port at index 1, with physical owner `payloadOwner+1`; index 0 is
inactive. The enclosing shared pool/router must connect the prospective and
queued payload pins at every router tier. A two-engine composition can retain
owner 0 for geometry and use owners 1 and 2 for fragment work without adding
private CUs or another payload pool.

## Results, end and cancellation

`primitiveDone` retains the bin reference, state fetch/hit information, status
and local typed render result. `renderStarted` means the image owner's draw
control was accepted; status/counters determine whether it executed or retired
successfully. A successful primitive result waits for raster/fragment drain
and local pixel retirement. It does not publish backing images.

Holding the result prevents the next record from replacing its metadata.
Input takes priority over a simultaneous end. End initiates source stores and
resolves through the existing image transfer owner. An empty tile can end and
publish its clear/discard/load semantics without any state fetch or shader
task. Final tile completion waits for all physical acknowledgements, memory
queues, state lookups, payload/task pins and held primitive results.

Fault stages are 0 success/cancel, 1 record/state admission or fetch, and
2 image/render/admission/publication. The first failure retains its stage and
address. `faultRecordValid` attributes a failure only while that record is
owned; later publication failure does not invent an earlier primitive culprit.
Outputs of a failed tile/pass must be discarded.

Cancellation preserves private lookup/begin/draw controls until accepted and
drains already offered memory, launches, leases, results and physical writes.
A terminal child image result closes a draw control that could no longer be
accepted after a spontaneous child failure. A published primitive result stays
immutable when the tile is cancelled later. `cancelSource` requests the
enclosing producer to stop and drain younger record offers; it is not proof
that the producer has drained.

## Memory and enclosing ownership

The component uses 32 internal transaction slots. Existing image IDs 0–15 are
preserved: prepared fragment staging uses IDs 4–7 and physical attachment
transfers use IDs 8–15. The four state tags use IDs 16–19. A queued arbiter
retains offers and replies return to the original client tags. CU instruction,
shader data and texture traffic keep their existing shared-system ownership.

An enclosing fabric must preserve the coherent L2 store contract and route
physical attachment traffic separately from virtual state/staging resources.
It must pin VM context, all state/uniform/geometry allocations and images until
source/bin consumers and every tile publication have retired. Holding this
component's completion retains its local ownership; it does not prove that
another replay engine, upstream bin owner or host job has drained.

The production native geometry/bin scheduler is not composed with these image
engines yet. Full-frame empty-tile scheduling, native job descriptors, resource
permissions/alias provenance, reset integration, cached ordinary-CU consumers
on the new native path, full Mobile runtime/rates and synthesis PPA remain open.


## Frozen qualification

The [2026-10-20 checkpoint](performance/mobile-typed-replay-image-2026-10-20.json)
records 33 passing cases across five suites on 601 frozen source files.
The two-engine checks require simultaneous CU ownership, distinct first-state
bindings, exact masked publication on the cache line shared by adjacent tiles,
a delayed consumer invalidation acknowledgement, and repeated cached reads.
A 1 KiB pool forces one engine to use global staging; a 4 KiB pool permits
both local payloads. These are real shared-CU executions with resolved geometry
fixtures, not a native vertex/bin/frame submission result.
