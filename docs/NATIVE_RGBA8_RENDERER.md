# Native shared texture renderer integration

The development profile `groupedTexture=true` now carries the shared texture
client interfaces through the complete native typed and binned renderers.
The default profile remains separately qualified. Eleven frozen component
checks and seven complete 1/2/4-CU emissions pass. The reduced simulation
profiles use four lanes, two warps, two attributes and two varyings; they emit
437 files for typed, 480 for binned. A separate full-capacity structural
check passes with 16 lanes, eight warps, four CUs, full attribute/varying
limits, 128 KiB L2, 64 KiB payload and 64 KiB attachment storage per engine;
it emits 478 files with SRAM black boxes. Each contains one global
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
The corrected typed matrix passes two cases and 22 frames, with actual shared
group, data and page-walk witnesses. The binned matrix adds two passing spilled
frames and two corrected-local frames on four CUs and two image engines. It
rewrites the same texture addresses through coherent GPU DMA between 1x/4x
frames and checks exact MRT/depth/resolve output. Individual source snapshots
and the initial insufficient-port/unused-layer-pitch fixture failures remain
recorded. The combined 850-input composition preserves every production input
of these frame and full-capacity structural checks.


Cross-pair publication/reset stress and
fresh integrated synthesis remain required. The sampler still serializes
native lane/metadata issue and allows one lease per client, so this wiring does
not establish sustained native sample rate. All remaining H0-H8 gates stay
open. PPA qualification remains synthesis only.

The shared native vertex/fragment endpoints now pass synthesis at
+106.973/+165.445 ps and +105.545/+169.443 ps core/I/O slack. The physical
memory arbiter also passes at +521.053/+395.659 ps. All three use 1000 ps,
TC/SLVT, 25% I/O, standard ABC and zero skipped instances, with no retiming or
place/route. These are scoped component gates; complete renderer timing
remains open. See the [current qualification record](performance/mobile-native-rgba8-renderer-2026-10-21.json).
