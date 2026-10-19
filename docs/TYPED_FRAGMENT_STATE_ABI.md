# GFS1 immutable typed fragment state

`TypedFragmentStateReader` reads a bounded, immutable GPU-memory state table.
[TypedReplayTileImagePipeline](TYPED_REPLAY_TILE_IMAGE.md) uses its entries to
rasterize and shade resolved post-transform records without executing a vertex
shader again. This format
does not add a native host opcode or change GTJ1/GTI1/GTD1 or the shader ISA.

## Word format

A GFS1 entry contains 128 little-endian 32-bit words (512 bytes). A table base
is 64-byte aligned. Handle `h` addresses `base + 512*h`; the complete entry must
fit the exclusive 33-bit limit, which may equal 4 GiB. Address arithmetic is
widened before checking and narrowing. An entry may end exactly at 4 GiB.

| Word | Value |
|---|---|
| 0 | Magic `0x47465301` |
| 1 | Byte size 512 |
| 2 | Format version 1 |
| 3 | Scratch engine capacity 4 |
| 4–6 | Context, pass, generation |
| 7 | Entry handle |
| 8–15 | Zero |

The 71 payload scalars follow the explicit generated
[C word map](../driver/opengpu_typed_fragment_state_abi.h) and
[JSON map](abi/typed-fragment-state-v1.json). Fields follow lexicographic named
bundle order and numeric vector index order. Every scalar occupies one or two
words, with padding before a multiword field when needed to keep it within a
64-byte line. Signed narrow fields sign-extend; all other padding and reserved
words are zero. Offsets do not depend on Chisel Bundle packing, lane count or
implemented varying-slot capacity. This typed format has four color locations.

The payload includes a version-1 state, active engine count (1–4), complete
fragment descriptor, late depth/stencil tests, blend state and three additional
scratch views. Engine 0 uses `fragment.scratch`; engines 1–3 use
`scratch[0]`–`scratch[2]`. Inactive additional views must be zero. Every active
view must satisfy the existing fragment descriptor and shader prefix bounds,
be disjoint from the state table, and be disjoint from the other active views.
Payload fragment identity must also match the table/header identity.

The kernel PC, raw output types/masks, depth-override permission, sample mode,
sampler and shader ABI versions retain their existing meanings. This state
table does not bound arbitrary shader instruction memory accesses or establish
code/texture/resource allocation provenance; those remain enclosing admission
and VM responsibilities.

## Scratch roles and uniform lifetime

A replay consumer must supply four engine scratch arena spans, an active engine
count and a 64-byte aligned `scratchStride`. For state handle `h`, engine `e`
may use only a view within:

`[scratch[e].base + h*scratchStride, scratch[e].base + (h+1)*scratchStride)`.

The complete table's slots must fit every active engine arena. Engine arenas,
the state table and protected geometry/page/directory allocations must be
disjoint. Inactive engine arenas are zero. A fetched state must declare the
same active engine count as the tile binding, and every active view must fit
its assigned slot before any shader task is offered.

These rules protect both concurrent engines and later replay of another state
in the same engine. A kernel prefix cannot overwrite another state's uniform
tail and corrupt a subsequent tile. Publish the required uniform data in each
active engine's view before binding. Equivalent logical draws must supply
equivalent uniforms in their views; hardware does not compare their contents.
The enclosing producer must also protect those immutable tails from vertex,
compute or other resource writers while the pass owns them.

The shader SoA layout is unchanged. For physical CU thread capacity `T`, active
varying slots `V` and color slots `C`, the writable prefix occupies
`4*T*(8 + 4*V + 4*C)` bytes for shader ABI 1, or two additional arrays for ABI 2.
Uniforms begin after that prefix. Allocation bounds must include the rounded
cache-line accesses as required by `FragmentShaderDescriptor`.

## Fetch, caching and cancellation

Four tags independently fetch the eight state lines. Replies may reorder into
a private snapshot; all reads drain and the full header, canonical encoding,
identity and descriptor checks pass before `stateValid` is offered. Faulted or
cancelled reads do not publish partial or substitute successful state.

The reader retains one complete immutable entry, keyed by the full table
descriptor and handle. The tile owner invalidates it at every pass binding,
including reuse of the same address and generation. Invalidation during a fill
prevents that fill from repopulating the cache. Cached entries remain subject
to the tile owner's scratch-slot and record matching checks.

Already offered requests and results remain stable through backpressure and
cancellation. Cancellation stops younger reads, drains all reserved/offered
tags and acknowledges the completed lookup before rebinding. A held successful
result remains immutable if cancellation arrives after publication. Producers
must keep table entries immutable through all lookups, rendering and the held
tile/pass result. Mutation during an active binding violates that contract.

Reader status values are 0 success, 1 invalid bounds/descriptor/alias, 2 shader
scratch prefix bounds, 3 memory fault, 4 cancellation and 7 record encoding or
header mismatch. `readLines` includes consumed fault replies; a hit issues no
memory read. The enclosing tile result adds record/render/publication stage
attribution.

## Reproducibility

Generate the two published maps with:

```sh
sbt 'runMain opengpu.elaboration.EmitTypedFragmentStateAbi'
```

Use `--check` to require identical checked-in bytes without changing files.
The state-reader qualification also compiles an independent C11 size/offset probe.
This ABI does not imply native job admission, full resource-table protection,
native reset integration or API conformance.
