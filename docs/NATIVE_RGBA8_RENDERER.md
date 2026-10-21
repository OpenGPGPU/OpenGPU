# Native shared texture renderer integration

The development profile `groupedTexture=true` now carries the shared texture
client interfaces through the complete native typed and binned renderers.
The default profile remains separately qualified. Both four-CU hosts emit
complete RTL: 437 files for typed, 480 for binned. Each contains one global
pool and two cluster instances, each with one 8 KiB cache. The binned renderer
has one vertex client and one fragment client per image engine; the typed
renderer has separate vertex and fragment clients.

Each task router's client-specific `owned` mask follows its sampler to the
pool. Combining masks at the renderer boundary would lose placement when
vertex and fragment tasks overlap. Prepared-geometry image engines expose
only their actual fragment sampler, with no unused vertex cache or client.

The shared pool retains one VM/publication epoch. Draw invalidation events,
host-job admission and TLB flushes reach the pool; scoped flushes conservatively
become global maintenance after all pair leases drain. Fault/cancel retirement
continues through the native sampler's original status and fallback owner.
Host completion and reset drain also wait for pool and memory-fabric ownership.

`NativeRgba8TextureMemoryFabric` attaches after the existing native renderer
translation fabric. Texture fills and page walks are already physical and must
not be translated again. If the existing fabric reserves `R` IDs, the namespace
is:

| Producer | Global IDs |
| --- | --- |
| Existing renderer, DMA and its page walkers | `0 .. R-1` |
| Cluster `n` physical data | `R + 32*n .. R + 32*n + 15` |
| Cluster `n` page walkers | `R + 32*n + 16 .. R + 32*n + 19` |

The outer namespace rounds up to a power of two. Unused IDs do not own reply
routes. Round-robin arbitration and a two-entry request queue preserve held
physical offers. Every reserved ID remains live until its producer consumes
the reply, including faults and public stalls; same-edge retirement/reuse
keeps the new lease. Three tests pass with all 84/104/124 available IDs occupied
for one/two/three clusters, reversed replies, exact request fields, held faults
and two complete reuse rounds.

The focused four-CU AXI matrix is prepared for both local and spilled payloads.
It submits eleven frames per mode using both native shader stages: aligned
RGBA8 with image swizzle, array/cube paths, automatic/explicit sampling,
general and compressed fallback, denied sampler pages, stale-operand rejection
and healthy reuse. Exact MRT/depth/resolve checks and completion VM pinning are
inherited from the existing native frame oracle. Simulation counters must
witness accepted shared groups, physical data reads and real page walks.
Those frame results are not yet claimed; frozen regression and complete
1/2/4-CU elaboration are the next gates.

Whole-binned shared texture frames, cross-pair publication/reset stress and
fresh integrated synthesis remain required. The sampler still serializes
native lane/metadata issue and allows one lease per client, so this wiring does
not establish sustained native sample rate. All remaining H0-H8 gates stay
open. PPA qualification remains synthesis only.
