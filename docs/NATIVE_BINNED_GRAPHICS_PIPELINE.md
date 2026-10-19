# Native binned graphics owner and memory fabric

`MemoryNativeBinnedGraphicsPipeline` connects GPU-memory GNJ1 jobs, GNF1 frame
snapshots and ordered GND1 draws to `TypedVertexBinImagePipeline`. Geometry runs
once on shared CUs; complete prepared records feed independent typed image
engines. The enclosing system supplies the CUs, payload SRAM and allocation pins.

The unpublished prototype reused the legacy GBJ1 job magic. The current draft
uses distinct `0x474e4a01`/`0x474e4601`/`0x474e4401` values and retains the same
512/2048/2048-byte layouts and 563 scalar fields. A regression checks disjoint
magic sets. Legacy bin jobs, GTJ1/GTI1/GTD1 and GFS1 are unchanged.

Payload contexts are captured at submit, before fetching descriptors, and remain
stable when raw inputs change. Reader begin/draw/end offers and renderer memory,
shader, primitive/pixel/tile results and physical publication remain owned through
fault or cancellation. The outer held result retains the complete structured
frame result. Final completion cannot release queued or accepted memory traffic.

Descriptor reads use IDs 0-3. Renderer IDs begin at 16 in a separately sized
range. The two-engine profile reserves 256 native IDs. With `B` equal to the
vertex/bin namespace size, engine `e` owns IDs `16+B+32*e` through `+31`.
Engine-local IDs 8-15 are physical source/resolve transfers; state IDs 16-19 and
geometry/bin/staging requests remain virtual.

`NativeBinnedGraphicsMemoryFabric` translates virtual requests through Sv32 and
bypasses translation for trusted physical image views. Four DMA IDs follow the
native range; two independent walker IDs follow DMA. The default external range
is 512 IDs. Separate buffered renderer and DMA replies preserve immutable offers
under backpressure. VM drain includes local permission faults, physical replies,
queued requests and all accepted producer lifetimes.

Eighteen checks across five suites pass on 614 frozen source/ABI files. Actual
two-CU tests fetch native records and publish exact four-MRT/depth source and
resolve bytes for a 17x9 frame, including indexed instances, 4x MSAA and partial
tiles. Both CUs are observed busy simultaneously. A held tile result leaves the
other engine progressing. Cancellation after shader leases retire retains a held
physical acknowledgement and then permits exact healthy reuse. Descriptor/fabric
tests cover four reordered tags, canonical fields, capacity/alias rejection,
exclusive 4 GiB ends, nested faults, cancellation, both physical engine ranges,
state translation, independent walkers, write permissions and held replies.

The default 1280x720-capacity renderer emits 278 files, retaining 56 characterized
256x64 and two 256x32 SRAM macros (114 KiB), one vertex stage, two fragment stages,
six FMA lanes and four dividers. Its four CU ports and payload pool are external.
The standalone reader/fabric emit 7/35 files; the fabric has two translators and
buffered replies without SRAM/CU/FMA/divider instances. These are structural
counts. Both ABI byte checks and a C11 size/magic/offset probe pass. See the
[frozen qualification](performance/mobile-native-binned-image-2026-10-20.json).

Actual shader tests connect the owner to `GpuSystem` in Bare mode. Sv32 fabric
checks are standalone. Production owner/fabric/host/MMIO/reset composition remains
pending. Trusted physical image provenance and translated cross-resource aliases
remain enclosing responsibilities; numeric admission checks do not establish
physical provenance. Runtime uses four lanes/two warps and small images, while
the full default is emitted only. Full Mobile runtime/rates/budgets and H0-H8
remain open. PPA remains synthesis only; no new timing or power result is claimed.
