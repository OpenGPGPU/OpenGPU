# Shared-CU vertex draws into general tile bins

`VertexBinPipeline` connects `VertexViewportPipeline` to
`WindowPrimitiveBinReplayPass`. It owns multiple ordered vertex draws under one
bin binding, including source tasks, staging/payload transport, clipping,
viewport preparation, record publication and full-record tile replay. The
shared graphics port uses the system CUs; this block contains no private CU.

## Pass and draw interface

`begin` captures a `WindowPrimitiveBinReplayDescriptor`. The record, bin-page
and paged-directory arenas, identity, screen grid and tile size follow the
[general window-bin contract](WINDOW_PRIMITIVE_BIN_RECORDS.md). The parent
captures the payload context at pass acceptance and retains it until pass
completion. Later changes to input descriptors or context do not modify the
owned work.

Each `draw` captures a version-1 `VertexBinDraw` containing:

- A complete generic GPU vertex/geometry/viewport descriptor.
- Immutable raster state for every resulting clipped part.
- A fragment/test/blend state handle and caller token, retained unchanged in
  every stored primitive and bin reference.

Draw identity must match the pass. Raster/viewport encoding and topology are
checked before the source starts. For nonempty input, every enabled binding,
the active index view and the complete declared scratch view must be disjoint
from the record/page/directory arenas. The scratch check includes its uniform
tail. Detailed vertex input and scratch bounds remain checked by the existing
vertex source before its memory accesses. Code, textures and immutable fragment
state still require enclosing allocation/resource admission; this interface
does not add a resource table or fetch fragment state.

The source executes indexed/instanced vertices once, assembles each topology,
clips and prepares viewport data, and transfers complete `WindowPrimitive`
snapshots into the record producer. A 33-bit counter assigns monotonically
increasing 32-bit pass-wide sequences to accepted parts; an additional part
after the representable sequence space fails instead of wrapping. Draw-local
64-bit primitive sequences and clipping part indices remain in the record.
Empty raster bounds may leave gaps in bin reference sequences. State handles
and raster settings are snapshots of the draw that produced the part.

`drawCompletion` reports the draw ordinal, source summary, received/admitted/
dropped part counts and first/next pass sequence. Successful draw completion
requires both source drain and the final record store/bin append
acknowledgements. It does not require tile replay or image publication. Holding
the result retains pass ownership and prevents another draw from replacing
its metadata. If draw and end are offered together, draw takes priority.

An empty first draw owns no record/bin appends, so its result can precede
pass-owned paged-directory initialization. The pass result still waits for
those metadata-store acknowledgements. The runtime fixture holds initialization
responses across an empty draw and verifies this boundary, while continuing to
reject outstanding draw-owned stores and offered acknowledgements.

`end` is accepted between draws. On a healthy pass the bin owner closes its
directory and starts tile replay after all geometry has been submitted. Replay does not execute
vertex shaders again. Independent engines return full records and tile results
with the contracts of `WindowPrimitiveBinReplayPass`. The downstream renderer
must resolve immutable fragment state and intersect raster scissors with the
selected tile's bounds.

## Memory tags and ownership

For `E` engines, `W` write credits and `D=1` for a paged directory (otherwise
zero), bins use `B = W + 2*E + 1 + D` memory transaction IDs starting at zero.
The existing four-tag vertex client uses IDs `B` through `B+3`. Responses
restore the original vertex tag. A queued round-robin arbiter retains public
offers. The public namespace rounds `B+4` up to a power of two to match the
shared system's graphics-host port.

The default Mobile profile has `E=2`, `W=8`, `D=1`: bin IDs 0–13, vertex IDs
14–17 and 32 public slots. The reduced runtime fixture uses `W=2`: bin IDs 0–7,
vertex IDs 8–11 and 16 public slots. These are internal component IDs, not a
new host/AXI address-space or command ABI.

`busy` remains asserted through held draw results, all replay records, tile
results and the final pass completion. Wiring `graphicsDrained = !busy` to
`GpuSystem` retains its VM boundary after vertex payloads and tasks have
retired. The enclosing owner must additionally retain resources through any
downstream raster/FS/pixel work and backing-image publication; this pass
completion proves only its own source and bin/replay retirement.

## Faults and cancellation

Pass status uses the existing three-bit graphics codes, with stage attribution:

| Stage | Meaning |
|---|---|
| 0 | Success or external cancellation |
| 1 | Draw admission |
| 2 | Vertex input/shader, clipping or viewport |
| 3 | Bins, record transfer or pass-order exhaustion |

Source failures preserve the complete `VertexViewportCompletion` and draw
ordinal. A bin failure may cancel the vertex source; that induced source
cancellation cannot replace the primary bin status. A draw result waits until
the bin failure is known rather than publishing success during fault drain.
Replay faults after draw retirement do not invent a failing draw ordinal.

Cancellation preserves already captured child draw/begin controls until they
are accepted, then cancels the child. It drains every offered source primitive,
retaining already admitted records and counting discarded source parts. Pool
cancellation participates in the same owner protocol. Memory requests,
payload leases, tasks, source completions, record outputs and tile results
must all drain before the held pass result. A memory-failed pass must discard
its output, even when a committed prefix or earlier draw result exists.

This owner does not yet connect native GPU-memory submission, fetch immutable
fragment state, execute raster/fragment work in multiple typed image engines,
or implement the enclosing VM/reset/publication protocol. Indexed-vertex
post-transform reuse, continuation lifetimes, full resource provenance,
production throughput/storage and synthesis PPA qualification remain open.
