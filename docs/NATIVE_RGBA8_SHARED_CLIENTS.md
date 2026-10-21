# Native sampling through shared texture clients

The optional `groupedTexture` profile connects both native shader endpoints and
both shader-stage interfaces to a shared RGBA8 service. Its
[841-input qualification](performance/mobile-native-rgba8-clients-2026-10-21.json)
passes 73 fresh checks and eight emissions. Shared native vertex/fragment synthesis now passes 1000 ps with I/O at
+106.973/+165.445 ps and +105.545/+169.443 ps core/I/O slack. Both are fully
mapped with zero skipped instances; the four-client cluster screen remains
running.
The constructor default remains disabled while renderer placement and timing
are completed.

The selected-level owner sends eligible unshadowed RGBA8 2D/array/cube work
after its existing binding, sampler, identity and selector checks. It receives
the spatial color, precise fault, fallback indication and actual data-read
charge. The native owner performs its existing mip interpolation and final
swizzle. General formats, shadow/volume modes and valid unaligned words retain
the general sampler. A held shared request drains after cancellation; accepted
reads remain counted even on error.

The client reports invalidation as an event. If invalidation arrives before an
offered request is accepted, or while it is live, the owner emits another event
when that request retires. This purges any late shared-cache publication while
preserving numeric results. The router closes new admission until existing
client leases and queued requests drain, then performs cache/TLB maintenance.
Connected clients and their cluster must use matching address-limit policies.
Enclosing native tasks retain their existing publication and VM pinning duties
through descriptor preparation and complete instruction retirement.

Each cluster has one 8 KiB data cache and four client leases. Original caller
tags are full-width and local to a client; the shared service sees lease/client
indices. Public replies restore the original tags and remain immutable. Sticky
cancellation covers a request queued before a previous service epoch drains,
while selective cancellation cannot poison another client's identical tag.
Each client's lease ends only when its response is consumed. One VM epoch is
pinned across queued work and public responses, and scoped flush pulses
coalesce into a full idle flush.

The fresh checks include 23 default and 23 shared native instruction cases,
covering all64 formats, scalar/vector operations, 1/4/8/16 lanes, helper quads,
integer fetch and size-query controls. Six focused native cases prove actual
aligned banked reads, nearest/bilinear filtering, two-level mip interpolation,
swizzle after interpolation, held-offer invalidation and fault-atomic drain in
both shader stages. Seven cluster cases verify four clients, identical tags,
shared fills, independent held responses, real Sv32 roots, flushes and faults.
Eight coordinate-to-color and six planner cases complete the 73-check run.

The default vertex/fragment emissions each retain all 77 qualified files after
removing comments and empty lines. All78 coordinate-to-color service files are
byte exact. Shared endpoints each emit 77 files; full vertex/fragment stages
emit 91/85, and the shared cluster emits 83. These counts do not establish
timing for the changed profiles.

Full renderer wiring, pair affinity, global physical/PTW port ownership and
render-to-texture visibility remain open. One response lease per client and
the existing serial native lane/metadata owners do not yet sustain one native
sample per cycle; native issue and outstanding-work changes are still required.
All remaining H0-H8 gates stay open. PPA remains synthesis only.
