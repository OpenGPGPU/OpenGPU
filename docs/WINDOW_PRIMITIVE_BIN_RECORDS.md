# General post-transform primitive bins

`WindowPrimitiveBinReplayPass` connects a post-viewport primitive stream to the
existing paged tile directory and bin replay scheduler. It stores each nonempty
primitive once and resolves its bin references through independently progressing
record readers. This internal stream interface is not a new native host ABI.

## Records and allocation

`WindowPrimitiveRecord` retains the complete `WindowPrimitive`: shaded vertex
values, all configured varying components, primitive and instance metadata,
clipping part/generated flags, viewport origins, signed 64-bit subpixel
coordinates, local extended-float coordinates, reciprocal W, window depth,
point-size outputs and guard-band flags. It also snapshots `Fp32RasterState`
and the immutable fragment/test/blend state handle, pass-wide bin sequence and
caller token. State storage and resource admission remain the enclosing owner's
responsibility. Records never refer back to released vertex shader scratch.

The arena uses `PrimitiveStateDescriptor` version 1, with a 64-byte aligned
base and exclusive limit of at most 4 GiB. A record handle is a record ordinal,
not a byte address or a legacy 64-byte state-cache handle. A transfer checks
`base + handle * recordBytes + recordBytes <= limit` in widened arithmetic
before creating a memory request. A record may end exactly at 4 GiB.

The internal GWP1 format starts with sixteen 32-bit little-endian words:

| Word | Meaning |
|---|---|
| 0 | Magic `0x47575001` |
| 1 | Complete padded record byte size |
| 2 | Format version 1 |
| 3 | Implemented varying-slot capacity |
| 4–6 | Context, pass and generation |
| 7 | Record ordinal |
| 8–15 | Zero |

Payload leaves follow in lexicographic named-field order and numeric vector
index order. Each scalar occupies one or two words, with line-boundary padding
before a multiword field when needed. Signed scalars sign-extend; unsigned
scalars zero-extend. Remaining words through the final 64-byte line are zero.
`WindowPrimitiveRecordLayout.fields` is the authoritative configuration-specific
map. This format is independent of Chisel Bundle bit packing, but its byte size
depends on the implemented varying capacity; it is not a stable software ABI.
At the full 16-slot capacity, 329 payload scalars occupy a padded 1,472-byte
record (23 lines). The final byte size includes the header and all padding.

Every line uses acknowledged uncached memory traffic. A writer publishes its
handle only after every store acknowledgement. A reader checks the complete
header, identity and padding before publishing one complete private record.
Faulted reads never publish substitute zero or partial successful records.
Cancellation drains an offered line and its reply. An offered result remains
stable even when cancellation arrives while the consumer withholds ready.

## Binning and ordering

`WindowPrimitiveBinProducer` accepts a fresh arena and a screen/tile grid.
Tile dimensions are powers of two from 8 through 64 pixels, and the active grid
must fit 65,535 tile IDs. The sequence supplied with each accepted record must
strictly increase across draws and clipped parts; draw-local primitive sequence
and part index remain separate fields in the stored payload. Sequence wrap is
an error. State handles and tokens are copied unchanged to every bin reference.

Bounds use widened signed arithmetic before screen/scissor clipping. Point and
line bounds include their clamped size, including shader point-size selection.
The rectangular fanout is conservative; it does not imply coverage of every
referenced tile. Empty bounds consume no arena space. Unavailable fixed
guard-band coordinates reference the effective scissor's tiles and preserve
the original primitive for a downstream reconstruction or numeric-fault policy.
This fallback does not implement guard clipping.

The producer stores a nonempty record once, then appends its handle to the
affected tiles in row-major order, waiting for each committed append reply.
Record arena exhaustion occurs before an unpublished record's first store;
the producer returns that exact input as an explicit restart record. A bin
rejection may follow a partially committed fanout, so it invalidates the pass
and does not claim a restartable prefix.

## Replay, cancellation and ownership

The pass owner checks matching identities and tile counts and rejects overlap
between records, bin pages and paged directory storage. Resume bindings are
rejected: preserving record allocation across continuation batches is separate
work. It ends the directory only after the producer has drained. Bins then
replay through one record reader per engine. Each fetched record must match
the bin reference's identity, state handle, sequence and token.

Each engine owns its tile through the final full-record output and tile-result
handshake. A stalled output cannot retire that tile, but other engines retain
their own dispatch credits and memory tags. The output carries the original
raster state and tile ID; the downstream renderer must intersect the raster
scissor with that tile's bounds before rasterization.

Cancellation first drains the producer's held append through a private bridge,
then cancels bins. The bridge can return a failed reply after a directory has
already closed. Held record outputs, tile results, queued memory requests and
all outstanding acknowledgements drain before the pass completion. Source
cancellation is a request to stop younger work, not proof that an attached
vertex producer has drained.

The enclosing owner must retain record, state, page and directory allocations
and all shader/image resource bindings until every replay engine, raster/FS
consumer and image publication acknowledgement has retired. This stream pass
completion proves its own bin/record drain; it does not prove downstream pixel
retirement or backing-image publication. All outputs of a failed pass must be
discarded. Native GTJ1 admission, general vertex-to-bin draw ownership,
fragment-state fetching, multi-engine typed rendering and VM/reset integration
remain required before this path can replace native per-tile vertex execution.
