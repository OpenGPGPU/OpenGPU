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

## Production host integration checkpoint

The optional `GpuHostSystemAxi.nativeBinnedGraphics` profile now connects the
native owner, translated memory fabric, shared CUs and optional payload pool to
the existing command/MMIO host. It supplies completion and drain signals and
uses the separate GNC1 discovery namespace (`0x474e4301`). Eight discovery words
report runtime frame, shader, engine and payload limits independently of the
fixed record capacities. The generated C header and JSON layout describe 24
fields; payload slot capacity is reported in 64-byte lines.

This is a development checkpoint. Main and test Scala sources compile, the
discovery ABI generator runs successfully, and an independent C11/schema probe
checks the 32-byte discovery layout, masks and non-overlapping fields. The first
translated full-frame host test passes with local payloads: four ordered draws
across twelve tiles, four MRTs, depth, exact source/resolve guards, 1x/4x MSAA,
cached compute consumers and VM writes rejected while a completion is held. The
global-payload fixture is present but has not run at this checkpoint. Fault and
reset composition, default host emission and full host qualification remain
pending. This initial host test is separate from the eighteen-check frozen
qualification above.

## Expanded host validation in progress

The default four-CU 1280x720-capacity host now has a dedicated RTL emitter. It
emits 768 files with four actual CUs, one shared payload pool, two image engines
and a 256-bit AXI data path. Nine existing host/fabric compatibility checks pass.
These structural and regression results do not establish a new PPA result.

The first expanded five-case native host run passes local/global full frames and
empty/indexed frames, but fails its broad write-permission expectation and the
second reset trigger. The permission fixture now uses 4 KiB pages to deny only
vertex staging, excluding unrelated bin failures from the first-fault race. The
fixture also parks memory handshakes during command MMIO and diagnoses completion
before a reset trigger. The corrected sources compile; the full five-case rerun
is pending. Eight fault cases, both reset phases and full host qualification must
not be reported as passing until that rerun finishes.


## GNX1 completion-detail control checkpoint

GNC1 word 4 bit 31 now advertises twelve read-only GNX1 words at 0x180..0x1ac.
The record accompanies the held native completion; ordinary compute/DMA results
and empty/reset slots read zero. Its words are:

| Word | Meaning |
|---|---|
| 0 | GNX1 magic, `0x474e5801` |
| 1 | Native status; reader/frame/geometry stages; tile/draw/source/viewport/clip validity |
| 2–4 | Raw recorded address, reader draw index, valid fault tile |
| 5 | Nine nested owner statuses, including bin rejection and vertex shader status |
| 6–8 | Context, pass and generation |
| 9–10 | Valid 64-bit geometry draw ordinal |
| 11 | Reserved zero |

Invalid source snapshots and tile/geometry indices are zeroed. The recorded
address remains a raw owner field: some child/pass failures do not carry an
address, and zero does not establish a known address. Use stage and valid fields
when interpreting the record. The generated C/JSON layout defines every field.

The command port queues details with each result, and MMIO captures the complete
record on the same handshake as the software completion. Backpressure and reads
retain it; POP/reset remove visibility, and simultaneous POP/replacement swaps
all words together. A completion on the RESET-write edge is now discarded before
it can survive reset or emit a stale completion event. Legacy profiles retain
that fix without gaining the native register block.

Sixteen control/codec checks on 621 frozen source/ABI files, C11/schema checks
and the default 768-file four-CU host emission pass. See the
[control checkpoint](performance/mobile-native-binned-completion-info-2026-10-20.json).
Real native shader/Sv32/MMIO fault-detail and reset composition is still pending;
this does not close full host or H0-H8 qualification. Structural counts are not PPA.


A further [production AXI envelope check](performance/mobile-native-binned-completion-axi-2026-10-20.json)
passes on the same production RTL, bringing bounded GNX1 control checks to 17.
It independently checks all twelve words, exact stage/address attribution before
any memory access, held reads, read-only writes, protected VM state and zero
visibility after POP. This is an admission-path check; translated shader/bin/image
fault details and both full-host reset phases remain pending for GNX1.


## Base host composition qualification

The corrected base host run passes all five cases, including eight attributed
faults with an exact healthy frame after each, and reset during a live shader or
an unacknowledged empty-frame image write. Both reset phases discard queued DMA,
block VM mutation, drain allocations/pins and permit exact healthy reuse.
Nine compatibility/fabric checks also pass. See the [14-check base host record](performance/mobile-native-binned-host-composition-2026-10-20.json).

This record uses the earlier GNC1 profile with 24 discovery fields, before GNX1
and the bounded color-clear change. Native tests use 619 frozen source/ABI files;
the compatibility snapshot differs only in the unselected native host fixture.
The production RTL and all selected compatibility sources are identical. GNX1
and color each have separate passing evidence; their combined full-host runtime
qualification is still pending. Physical provenance, translated aliases, full
Mobile workloads/rates/budgets and H0-H8 remain open.
