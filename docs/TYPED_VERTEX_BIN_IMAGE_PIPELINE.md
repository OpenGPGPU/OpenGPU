# Typed vertex/bin/image pipeline

`TypedVertexBinImagePipeline` owns an ordered frame from shared-CU vertex draws
through general window-primitive bins to independent typed image engines. It is
an internal physical-address component. Native descriptor submission and MMU/host
integration for this combined path remain separate work.

The version-1 frame descriptor binds a version-3 bin directory, whole-frame image
views, immutable fragment-state table, and per-engine scratch arenas. Resume is
disabled: every tile, including empty tiles, receives a terminal report and image
publication. Whole-grid admission checks every partial edge tile before exposing
bin or image traffic. Images start at origin zero; the owner derives tile origins
and extents from the frame dimensions and 8/16-pixel tile size.

Ordered `VertexBinDraw` inputs retain the existing indexed, instanced, topology,
clip and viewport contract. Vertex work executes once. Prepared records carry
state handles into fragment replay, so replay performs no vertex shader tasks.
Known vertex scratch aliases with state, fragment scratch or image allocations
are rejected before source admission. Draw input wins over simultaneous end.

One vertex producer and two default fragment engines share an external CU pool.
The task router captures payload contexts at begin and retains prospective and
queued payload pins. With two engines the owner exposes three logical payload
clients, fourteen producer pins and a 128-entry memory transaction namespace.
The enclosing system supplies the CUs, shared payload pool and allocation pins.

Each engine retains its renderer credit until its image result is queued after
source/resolve acknowledgements. A one-entry public result queue per engine
allows another engine to progress when one result is held. Cancelled directory
reports also need returned credits to drain. Credit gating is optional and
defaults off in the standalone vertex/bin components.

Primary geometry/bin and image faults retain their stage and tile attribution.
A held source draw fault is observed before its public handshake; secondary
cancelled tile reports cannot replace it. Cancellation preserves already offered
tasks and results. Completion waits for shader/payload, physical memory, child
owners and public result queues to drain. The enclosing owner holds VM/resource
ownership through the final completion and discards failed-pass outputs.

Qualification on 604 frozen sources passes 24 checks across four suites. Actual
two-CU tests cover local/global payloads, two ordered draws, indexed instances,
17x9 partial tiles, four typed MRTs and depth, 1x/4x coverage and exact guarded
source/resolve bytes. They hold one engine's result while the other progresses,
verify immutable payload contexts, and check record-read, fragment-trap and
physical-store failures plus healthy reuse. Cancellation covers held primitive
results, queued shader launch/live lease, and a final physical acknowledgement
after shader leases have retired. Three direct-owner and fifteen existing
vertex/bin regressions also pass.

The default combined emission has 267 files, 56 characterized 256x64 macros and
two 256x32 macros (114 KiB), one vertex stage, two fragment stages, six FMA lanes
and four dividers. No private CU or payload pool is instantiated. The standalone
vertex/bin default still emits 147 files and 30 KiB of SRAM. These are structural
counts. See [frozen evidence](performance/mobile-typed-vertex-bin-image-2026-10-20.json)
for exact sources, logs, RTL hashes and the corrected follow-up emission command.

Runtime uses four lanes, two warps, two CUs and small images. Native combined
admission/MMU/reset, allocation provenance, full Mobile runtime/rates/budgets and
H0-H8 remain open. PPA evaluation remains synthesis only.
