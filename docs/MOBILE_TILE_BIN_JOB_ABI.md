# Bounded tile-bin memory submission, version 1

`MemoryTileBinRenderPipeline` accepts a command ID, a 64-byte-aligned descriptor
base, a 33-bit exclusive allocation limit and an envelope-valid bit. The
allocation must contain the entire 512-byte descriptor and end at or below
4 GiB. The engine accepts one submission at a time and retains ownership
through consumption of its final completion, including internal bin restarts.

This format is separate from the existing opcode-7, 256-byte single-tile
job ABI. `GpuHostSystemAxi(..., binnedRendering = Some(mobile))` selects the
GBJ1 host profile described below. The existing `PersistentTileJobs` and
`TypedTileCommands` bits do not advertise GBJ1. Legacy profiles retain their
original register map and descriptor formats.

All words below are unsigned 32-bit little-endian values. Packing a Chisel
Bundle is not an ABI encoding. A 33-bit limit uses a low word and a high word
whose only legal bit is bit 0. Limits are exclusive; `0x1_00000000` is legal.

| Descriptor word | Meaning |
|---|---|
| 0 | Magic `0x47424a01` |
| 1 | Descriptor size in words: 128 |
| 2–4 | Context, pass and generation identity |
| 5–7 | Append base, limit low, limit high |
| 8 | Number of 32-byte append records |
| 9 | Restart budget in bits 15:0; other bits zero |
| 10 | Screen width in bits 15:0, height in bits 31:16 |
| 11 | Covered grid width in bits 15:0, height in bits 31:16 |
| 12 | Grid origin X in bits 15:0, Y in bits 31:16 |
| 13 | Tile log2 in bits 2:0; sample mode in 5:4; color format in 10:8; resolve enable in 16; other bits zero |
| 14 | Grid tile count in bits 15:0; other bits zero |
| 15 | First immutable-record handle |
| 16–18 | Reserved bin envelope base, limit low, limit high |
| 19–20 | Initial bin-prefix limit low/high for pool version 2 |
| 21 | Pool version in bits 7:0; spill-segment count in 11:8; other bits zero |
| 22 | Bin page-size log2 in bits 5:0; other bits zero |
| 23–25 | Paged directory base, limit low, limit high |
| 26–28 | Immutable primitive/state arena base, limit low, limit high |
| 29–32 | Shader staging base, limit low/high, per-engine byte stride |
| 33–38 | Color view: base, limit low/high, row pitch, load/store flags, packed clear word |
| 39–44 | Depth/stencil view, with the same six-word layout |
| 45–48 | Resolve base, limit low/high and row pitch |
| 49+3i–51+3i, i=0..7 | Spill-segment base, limit low/high |
| 73–127 | Reserved zero |

Attachment flags encode load/clear/discard as 0/1/2 in bits 1:0 and store in
bit 2. All other flag bits are zero. Sample mode 0/1/2 means 1x/2x/4x. Tile
log2 3/4 means 8x8/16x16. The renderer performs its ordinary whole-grid,
format, image-pitch, sample-count, record, segment and shader-window admission;
the memory reader does not replace those checks.

The memory format constructs version-1 render, replay, record and attachment
descriptors with one common identity. Paged bindings start with directory
version 2; the continuation controller selects version 3 and owns resume.
Software cannot request an arbitrary previous sequence journal as a new job.
Pool version 1 requires words 19–20 and all spill fields to be zero. Resident
builds require directory fields to be zero. Fixed-function builds require all
shader-staging fields to be zero. Unsupported formats, versions or reserved
bits are rejected rather than truncated into a supported encoding.

The append allocation is 64-byte aligned at both ends, including an optional
padded final half-line. The full-precision calculation
`appendBase + appendCount * 32 <= appendLimit <= 2^32` must hold. A zero record
count is legal and needs no append read. Both descriptor and append allocations
must be disjoint from the complete writable bin envelope, paged metadata,
color/depth allocations, enabled resolve allocation and programmable staging
allocation. Empty ranges do not overlap. Immutable record, descriptor and
append allocations may share read-only storage if their encodings permit it.
Physical aliases require equivalent exclusion by the enclosing host owner.

Each append is eight words: tile ID, primitive handle, state handle, sequence,
opaque token, and three reserved zero words. The tile ID must be below the
declared grid count. Handles retain the existing arena-relative 64-byte
record semantics and `firstHandle` mapping. One line fetch provides two records;
the reader retains each exact accepted append until its producer reply, even
when the renderer internally drains, reloads and retries a bin-exhausted batch.
It never advances the memory producer by resubmitting a successful reference.

Descriptor reads reserve IDs 0–7 and may return out of order. Append reads use
ID 8. The default integrated engine adds these nine IDs before the existing
51 renderer IDs, for 60 reserved IDs. A registered output queue holds external
request payloads stable under backpressure and cancellation. The enclosing
fabric must preserve these tags and return one response per accepted request.
Faulted descriptor/append data never becomes a successful input record.

An offered begin remains stable through cancellation; the reader cancels the
renderer only after that begin is accepted. Cancellation can terminate
unaccepted private append/end offers. Every accepted append reply, offered or
accepted memory request and renderer completion must drain before the final
host completion. Late read faults retain priority over cancellation. A
terminal renderer append failure stops further input; the renderer's final
status preserves its exhaustion, ordering or execution failure. Tile events
are diagnostic valid pulses and are drained internally, so host progress does
not require a per-tile software event consumer.

Completion contains the command ID, final image-validating status, separate
frontend status, descriptor bytes requested, append-line bytes requested,
accepted producer-append count and the renderer summary. `renderValid` means a
renderer begin was accepted and its summary was collected; it does not imply
successful admission or a valid image. A failed final status invalidates any
earlier tile stores. Requested bytes include faulted issued reads and padded
append lines. Renderer work/traffic counters remain separately scoped. Held
completion bits remain stable, including when cancellation is asserted later.

The host must publish descriptor, append and immutable-record writes before
submission, pin VM mappings and all referenced allocations, and preserve them
until final completion is consumed. This engine does not perform CPU cache
coherence or an automatic producer-cache publication barrier. Root semantic
rejection may overlap one already bounded immutable append read; that read is
still drained. Format and input-envelope rejection happen before renderer begin.

## Resource record

The standalone engine exposes eight constant `capabilityWords`. The opt-in
binned AXI host maps them at `0x160..0x17c`; legacy profiles return SLVERR for
that extended range. The always-mapped reserved word at `0x68` reads zero on
legacy profiles and `0x160` on the GBJ1 profile, allowing safe discovery before
reading the record. The discovery pointer and record reject writes with
SLVERR. No retired job-ring or persistent-tile capability bit is reused.

| Word | Encoding |
|---|---|
| 0 | Resource-record magic `0x47424301` |
| 1 | Record size: eight words |
| 2 | Supported job-descriptor magic `0x47424a01` |
| 3 | Descriptor bytes in bits 15:0; append bytes in 23:16 |
| 4 | Engine count in 7:0; maximum sample mode in 9:8; paged/programmable/shared-CU/coalesced-transfer flags in bits 16/17/18/19 |
| 5 | Tile capacity in 15:0; resident directory entries in 23:16 |
| 6 | Page-size log2 in 5:0; bin write credits in 15:8; attachment write-word credits per slot in 23:16; reader ID count in 31:24 |
| 7 | Maximum restart budget in 15:0; integrated line-port ID count in 31:16 |

Unassigned bits are zero. These fields describe the elaborated engine, not
completion of the GLES feature matrix or measured frame throughput.


## Unified AXI host profile

The normal emitter selects this profile with:

```sh
sbt -J-Xmx8G -batch 'runMain opengpu.elaboration.EmitGpuHostSystemAxi generated/mobile-host --binned-tile-jobs --frag-core --shared-graphics --parallel-graphics --sram-black-boxes'
```

The defaults are 1280x720, four 16-lane/eight-warp CUs, two tile engines,
128 KiB shared L2, 4 KiB bin pages, eight bin/attachment write-word credits,
16,384 directory tiles and 32-byte AXI beats. Programmable rendering uses the
same system CUs as compute. The old renderer does not allocate private shader
CUs or attachment SRAM in this profile. Fixed-function GBJ1 operation is also
available without the programmable/shared flags. `--tile-engines`,
`--l2-sets`, `--l2-store-entries`, `--tile-store-credits`, CU-count/shape and AXI
width options must satisfy the checked implementation limits. In particular,
L2 store capacity must reserve at least twice the tile-engine count.

Before choosing opcode 7, software reads `GPU_REG_BIN_CAPABILITY_PTR` (`0x68`)
and validates the GBC1 record. A zero pointer means GBJ1 is unavailable. In
the GBJ1 profile, SOURCE is the descriptor VA, DESTINATION is the exclusive
allocation limit's low word, PATTERN bit 0 is its high bit, BYTES must be 512,
and DMA/event dependency flags must be zero. The header's sample/format and
view fields are authoritative. Opcode 6 rejects as an invalid opcode; old
256-byte opcode-7 packets reject before descriptor traffic. Command IDs must
remain unique in software until their results are retired.

Results use the existing unified completion and IRQ registers. Status values
are `GPU_BIN_RESULT_*`: input memory faults are 4, attachment faults 10,
shader faults 11 and texture faults 12. These differ from the old single-tile
status encoding. The success bit validates only a zero final status. Unified
completion bytes are requested append-line bytes, including padding/faulted
requests and excluding descriptor reads. The standalone engine retains the
more detailed `TileBinJobCompletion`; its nested counters are not additional
job-result MMIO registers. Software uses unified STATUS/COMPLETION for GBJ1,
not the retired direct-render START/status protocol.

Ordinary kernel commands may dispatch in the same pinned VM while rendering.
Unified memory/DMA commands wait for the bin engine to retire; a bin command
waits for preceding memory-engine work and legacy DMA registers to drain.
Legacy fill/blit/strided register kicks reject with SLVERR while rendering or
a queued bin command owns that boundary. Final image exports therefore precede
a following copy. One registered completion queue arbitrates bin and ordinary
command results and retains stalled results through software backpressure.

VECTOR_SATP, INSTRUCTION_SATP and TLB_FLUSH writes return SLVERR without
changing state while queued commands, either renderer/fabric, compute work,
probes, translated/local-fault replies or software completions retain VM
ownership. Every new bin/descriptor, per-engine staging and legacy-DMA client
uses that same context. For the default programmable profile, the coherent
host namespace reserves 196 IDs in a 256-ID interface: four legacy DMA slots,
60 bin-engine slots, two 64-ID staging windows and four walker slots. The
whole GPU interconnect reserves 308 IDs, requiring nine AXI ID bits; this is a
namespace bound, not a claim of 308 simultaneous DRAM misses.

The existing RESET register (`0x138`, write bit 0) stops new dispatch, drops
queued unissued commands and cancels the bin engine. Accepted/offered memory,
active compute/DMA, renderer completion queues and late faults still drain.
Only then does reset acknowledgement raise the IRQ and clear reset-busy.
Old stream completions are discarded during reset. VM changes remain blocked
through reset. The next submission must publish a fresh valid binding; a
failed/reset image is never usable merely because some tiles were exported.

Host integration does not add CPU coherence, a producer-cache publication
barrier, physical-alias validation or per-job concurrent address spaces.
Those ownership/publication requirements above remain in force. Debug system
CU/L2, lower-memory and translation counters remain available when enabled;
the old single-renderer OM/fragment/texture counters do not measure GBJ1 work.
For this profile the four translation-counter groups are legacy DMA,
bin/descriptor, stage 0 and remaining stages (summed worker cycles).

## Publishing GPU-produced inputs

The current CU data caches and L2 use write-through stores. A successful
producer kernel completion follows the lower-memory store acknowledgements;
there are no dirty cache lines requiring a writeback command. To publish a
GPU-generated descriptor, append stream, primitive/state records or shader:

1. Finish all producers and observe their successful unified completions.
   Queue order alone is insufficient: ordinary compute can overlap bin work,
   and GBJ1 rejects event/DMA dependency flags. Discard the generated inputs
   if any producer fails.
2. Retire the completions and preserve the same mappings/backing ownership.
   Keep the published inputs immutable through final bin completion, including
   internal continuation batches.
3. If executable bytes were replaced, write TLB_FLUSH while the context is
   idle before submitting the consumer. This also invalidates private CU
   instruction caches; data stores do not snoop those caches. A data-only GPU
   publication does not require an L2 invalidate.
4. Submit the GBJ1 consumer with the completed resource bindings. Deliberate
   VA aliases of the same physical resource use the same publication boundary;
   aliases do not authorize overlapping independently owned writable images,
   metadata or staging arenas.

Opcode 5 is a separate **physical** L2/private-data-cache invalidate, unaffected
by VECTOR_SATP. It covers every 64-byte line intersecting `[SOURCE, SOURCE +
BYTES)`, including unaligned endpoints. An exclusive end of `2^32` is legal;
a greater end returns `GPU_INVALIDATE_RESULT_ADDRESS_OVERFLOW` (1), zero
bytes processed and no cache requests. Empty ranges succeed without traffic.
Success reports the rounded extent, which can be `2^32` in the 64-bit result.
Each line is acknowledged before the next is issued. The command does not
flush CPU caches or private GPU instruction caches. A CPU/noncoherent writer
still needs the SoC's cache-clean/release protocol before GPU consumption;
cached GPU views additionally need the appropriate physical invalidate.
