# Native binned graphics descriptor reader

`NativeBinnedGraphicsJobReader` reads GNJ1 jobs, GNF1 frames and ordered GND1
vertex draws from GPU memory. The fixed layouts retain 16 attribute, binding and
varying slots, four colors and four engine views; smaller implementations reject
unsupported active fields before copying them into child controls. The existing
GTJ1/GTI1/GTD1 and GFS1 interfaces are unchanged.

Four independently tagged 64-byte uncached reads populate private scalar
snapshots in any reply order. Magic, sizes, versions, capacities, reserved words
and canonical narrow-field padding are checked. Widened job/frame/draw bounds
permit an exclusive 4 GiB end without wrapping. Complete job/frame snapshots
precede begin; each draw is fetched and retired before the next draw is offered.
An empty job still begins and ends a frame so the child can clear empty tiles.

Known writable virtual record/page/directory/fragment staging spans and vertex
prefixes cannot overlap job/frame/draw descriptor storage. Physical image view
provenance and translated physical aliases remain the enclosing allocator/host's
responsibility. The combined child validates its complete frame and every edge
tile before image/bin/geometry traffic; this reader does not replace that check.

Cancellation retains offered line requests and begin/draw/end controls until
accepted. The child is cancelled after its private controls are accepted; its
draw and frame results must drain before completion. All descriptor tags and
queued offers remain owned. The result retains command/identity, primary stage,
draw counters, descriptor traffic, nested vertex fault address and the full
structured child frame result. A child frame without an aggregate fault address
reports address zero while retaining tile and nested fault metadata.

Eight checks across two reader suites pass on 610 frozen source/ABI files. New
coverage includes exact snapshots with signed/raw values and four reordered
tags, all three record types ending at 4 GiB, empty-frame begin/end, 23 malformed/
capacity/bounds/alias cases, three descriptor-memory fault positions, nested
vertex and frame faults, five cancellation phases, held completion and healthy
reuse. The harness models child controls/results; it does not execute shaders.
Short test-only port aliases avoid a simulator DPI identifier limit while
preserving every nested production result field.

The default reader emits seven RTL files without SRAM, CU, payload pool, FMA or
divider instances. Both native ABI byte checks and a C11 size/magic/offset probe
pass. See [frozen evidence](performance/mobile-native-binned-reader-2026-10-20.json)
and the [wire-layout draft](NATIVE_BINNED_GRAPHICS_ABI_DRAFT.md).

Connecting the reader to the combined renderer, real shared CUs/payload,
virtual/physical memory translation, native host/MMIO discovery and reset remains
open. Counts are structural, PPA remains synthesis only, and H0-H8 remain open.

The unpublished prototype previously used the legacy bin job magic. The
current GNJ1/GNF1/GND1 namespace avoids that collision; earlier frozen evidence
retains the prototype values as historical data. No published legacy ABI changed.
