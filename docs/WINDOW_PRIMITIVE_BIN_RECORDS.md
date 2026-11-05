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
| 2 | Version 1 without geometry metadata, or version 2 with it |
| 3 | Implemented varying-slot capacity |
| 4–6 | Context, pass and generation |
| 7 | Record ordinal |
| 8 | Version 2 input primitive ID; otherwise zero |
| 9 | Version 2 invocation ID in bits 5:0; otherwise zero |
| 10 | Version 2 layer; otherwise zero |
| 11–15 | Zero |

Legacy payload leaves follow in lexicographic named-field order and numeric
vector index order. Polygon-offset fields append after that legacy payload.
Each scalar occupies one or two words, with line-boundary padding
before a multiword field when needed. Signed scalars sign-extend; unsigned
scalars zero-extend. Remaining words through the final 64-byte line are zero.
`WindowPrimitiveRecordLayout.fields` is the authoritative configuration-specific
map. This format is independent of Chisel Bundle bit packing, but its byte size
depends on the implemented varying capacity; it is not a stable software ABI.
The final byte size includes the header and all padding.

Stores and replay misses use acknowledged uncached memory traffic. A writer publishes its
handle only after every store acknowledgement. A reader checks the complete
header, identity and padding before publishing one complete private record.
Faulted reads never publish substitute zero or partial successful records.
Cancellation drains an offered line and its reply. An offered result remains
stable even when cancellation arrives while the consumer withholds ready.

## Pass-local record line reuse

Replay readers share a raw-line cache indexed by the low four record-handle
bits. Each of the sixteen slots has a high-handle tag and individual line-valid
bits. At the full sixteen-varying capacity, a record uses 23 lines within a
32-line padded slot: 32 KiB of data in sixteen characterized 256×64 SRAM macros.
Tags, residency masks and held replies are separate register state. The data
capacity does not restrict the arena or the number of primitives; collisions
replace one slot and retain the original uncached transfer semantics.

Four independent miss ports retain the existing arbiter, transaction IDs and
fall-through request/reply path. One synchronous SRAM hit read can start per
cycle. Single-port bank fills have priority; each engine reserves its own
reply and captures it if its consumer stalls. Bank conflicts are filtered
before hit arbitration, so a blocked hit waits while another bank can progress.
A miss already offered cannot
turn into a hit after another engine fills that line.

Identical handle/line/address reads may share one physical miss before their
external offers become irrevocable. Four same-cycle requests select one
leader; later requests may join that owned leader until its reply cycle.
Every reader still owns its private reply, transaction ID and backpressure
state. A fault fans out to all accepted readers of that miss. Already offered
duplicate leaders retain their separate transactions and fault outcomes.
Cancellation or a memory fault disables new sharing, hits and fills while
owned requests and held replies drain. This avoids relying on SRAM residency
alone when four engines request a cold line before the first fill returns.

Every accepted pass clears residency only after the previous pass has drained.
The cache covers record readers only; record producer stores and bin metadata
bypass it. All header, padding, canonical scalar, identity and reference checks
still run on every complete record. Reuse relies on the existing immutable
record arena and pinned pass/VM owner; it does not provide coherence against
arbitrary host, shader or other-context writes. Full product frames and
whole-host physical timing remain separate qualification requirements.

The bounded four-engine control uses sixteen full-capacity 1472-byte records
in four 8×8 tiles (16×16 frame), with every renderer permit enabled from a cold
start and identical delayed/reordered memory and consumer backpressure:

| Path | Physical record reads | Replay cycles | Total pass cycles |
|---|---:|---:|---:|
| Original uncached control | 1472 | 2590 | 8389 |
| Residency only | 1437 | 2526 | 8325 |
| Residency and unoffered-miss sharing | 374 | 2379 | 8178 |

The shared path records 31 SRAM hits and 1067 joined reads; together with its
374 physical reads these conserve all 1472 logical line reads. Every record's
complete payload, metadata and per-tile order is checked. The existing pass
completion field `recordReadLines` counts logical reader handshakes, including
SRAM hits and joined followers; it therefore remains `references × 23` for
full-capacity records. Accepted external memory requests and host
`readBusBytes` measure physical traffic. An independent
sixteen-point zero-reuse control is identical for all three paths: 368 physical
reads, 741 replay cycles and 4140 total cycles. The regression requires at
least two-thirds fewer physical reads and 5% fewer replay cycles for shared
reuse, exact zero-reuse equality, unchanged producer writes, and fresh data
after a same-address next pass. These are component controls with bounded bin
storage, not a product-frame speed or clock qualification.

Reproduce with Java configured for the local JDK:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -J-XX:+ExitOnOutOfMemoryError \
  -Dopengpu.simJobs=1 -Dopengpu.productQualification=0 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.WindowPrimitiveRecordReadCacheSpec opengpu.graphics.WindowPrimitiveRecordsSpec opengpu.graphics.WindowPrimitiveBinProducerSpec opengpu.graphics.WindowPrimitiveBinReplayPassSpec'
```

The nine cache protocol cases cover one, three and four readers, reordered
physical replies, partial-line tag collisions, return-cycle late joins,
already offered duplicate leaders, held leader priority, independent held
replies, faults, cancellation and same-address rebind. Behavioral and pinned
native SRAM models both check concurrent conflicting/independent-bank hits
and a held SRAM result across a cancelled collision fill. The full native
capacity witness fills all 368 record lines and checks every 512-bit value
from another engine without additional physical reads.

The existing nonempty 17×9 local-payload shared-CU image case also passes:
two draws launch vertex work only twice, then two tile engines retire twelve
references across six tiles in 76,052 service cycles. It checks every expected
source/resolve byte for four color attachments and depth, late blending,
independent progress while one tile result is held, pinned contexts and final
payload/VM drain. This is one selected integration case, not the complete
shared-system suite or a product performance measurement:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -J-XX:+ExitOnOutOfMemoryError \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TypedVertexBinImageSharedSystemSpec -- -t "TypedVertexBinImagePipeline shared system should shade vertices once and publish ordered full-frame typed images through independent tile engines local=true"'
```

The standalone native cache passes hierarchy-preserving Yosys synthesis and
complete OpenROAD setup constraint coverage at 1000ps with 25% input/output
delay, TC/SLVT libraries, original SRAM libraries, and no retiming. Core and
I/O setup slack are +212.446ps and +26.311ps. All sixteen SRAM macros remain
live; estimated mapped area including macros is 15,862.136µm². The screen used
one CPU and a 6GiB memory limit. This is a component synthesis estimate;
routed timing, whole-host physical closure and product-frame qualification
remain open.

Reproduce the native component screen with fresh output directories:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 \
  'runMain opengpu.elaboration.EmitWindowPrimitiveRecordReadCache /tmp/record-cache-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  /tmp/record-cache-rtl WindowPrimitiveRecordReadCache --stage synthesis \
  --synthesis-engine yosys --yosys-preserve-hierarchy --period-ps 1000 \
  --io-delay 0.25 --output /tmp/record-cache-ppa
```

## Binning and ordering

`WindowPrimitiveBinProducer` accepts a fresh arena and a screen/tile grid.
Tile dimensions are powers of two from 8 through 64 pixels, and the active grid
may contain at most 65,536 entries, with IDs 0–65,535; the directory must
support that count. The sequence supplied with each accepted record must
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

The producer stores each nonempty record once. Its [bounded cursor batch](TILE_ORDERED_BINNING.md)
merges tile visits while retaining record order within each tile, waiting for
each committed append reply. Full batches and idle inputs flush pending work;
EOF waits for every reference. Record arena exhaustion occurs before an
unpublished record's first store. Older buffered references finish before the
producer returns that exact input as an explicit restart record. A bin
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
discarded. [VertexBinPipeline](VERTEX_BIN_PIPELINE.md) now supplies actual
shared-CU vertex-to-bin draw ownership and retains source/payload/VM drain.
`MemoryNativeBinnedGraphicsPipeline` composes native GTJ1 admission around
`TypedVertexBinImagePipeline`, which resolves fragment state and connects this
record path to per-engine typed rendering. See the [four-engine host profile](NATIVE_FOUR_ENGINE_HOST.md)
for scoped integration evidence. Complete product frames, general resource/API
coverage and whole-host physical timing remain required.
