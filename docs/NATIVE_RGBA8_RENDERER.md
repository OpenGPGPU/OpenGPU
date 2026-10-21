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


The 850-input renderer checkpoint above uses one pool lease per client. The
new four-credit candidate passes 27 component checks and six emissions on 853
frozen inputs. `NativeRgba8TexturePool` retains its one-credit default; the
opt-in shared native host selects four credits per stage. Each accepted row
retains its CU pair, full caller tag and work identity through public reply
acceptance. Pair-local results collect into reserved rows and retire in order
per client, allowing other clients and pairs to progress under backpressure.
Scoped cancellation matches every caller row across pairs, including queued
admission; global cancellation and VM/publication fences retain all old rows.
Already collected replies stay immutable, and primary memory faults survive
cancellation. The physical 8 KiB cache, sixteen data IDs and four page-walk IDs
per cluster stay unchanged.

The new checks include cross-pair out-of-order completion with ordered public
retirement, repeated full caller tags, a sixteen-request admission burst at
one request per cycle, held-public hot-client reuse, full-credit offer placement,
scoped/global cancellation, real Sv32 root changes, publication and fault
recovery. Emission passes the original one-credit pool, four-credit pools at
1/2/4 CUs and complete reduced typed/binned hosts (443/486 files). The physical
and client port headers match the original four-CU pool. These changes are included in the native warp/snapshot milestone; no synthesis
timing carries to the changed control path.

The four-credit 853-input pool passes all refreshed 22 typed and four binned
AXI frames. Later-source frame qualification and integrated synthesis remain required. The 855-input native lane candidate below overlaps eligible lanes. Broader
metadata and multiple-instruction issue remain serial; an admission burst
does not qualify sustained native sample rate. All remaining H0-H8 gates
stay open. PPA qualification remains synthesis only. See the
[four-credit qualification](performance/mobile-native-rgba8-pool-credits-2026-10-21.json).

The earlier pre-stream shared native vertex/fragment endpoints pass synthesis at
+106.973/+165.445 ps and +105.545/+169.443 ps core/I/O slack. The physical
memory arbiter also passes at +521.053/+395.659 ps. All three use 1000 ps,
TC/SLVT, 25% I/O, standard ABC and zero skipped instances, with no retiming or
place/route. These are scoped component gates; complete renderer timing
remains open. See the [current qualification record](performance/mobile-native-rgba8-renderer-2026-10-21.json).

The [native lane candidate](performance/mobile-native-rgba8-native-stream-2026-10-21.json)
passes nine checks through a real four-credit pool with 16 native lanes in
both shader stages. The first lane captures a checked level/sampler/swizzle
snapshot. The remaining same-binding explicit nonmip aligned RGBA8 2D lanes
issue with individual tags and overlapping replies; other requests retain
the general sampler. Tests check all components, signed offsets, distinct
texels and bilinear weights, masked destinations, one root read per eligible
instruction, held offers, invalidation, late faults and healthy reuse.

Two persistent-cancellation tests first reproduced deadlock with the host's
actual reset contract: cancellation stays asserted until rendering drains.
Queued requests waiting for a cancelled child and an unaccepted held offer
both stalled. The corrected cluster/pool retires those unissued requests as
local cancelled replies. Earlier numeric replies enter their reserved public
queues first; existing public replies remain immutable and actual physical
owners still drain. Both original reproductions now pass. All 27 component checks, 23 general
shared-sampler checks and four endpoint emissions also pass on this 855-input
source. They establish neither later-source whole-renderer qualification nor
sustained native II1.

The original complete coordinate-to-color synthesis has now finished from its
original owned mapping, with zero skipped instances. It misses the 1000 ps
core gate by 128.878 ps; I/O slack is +339.892 ps. The mapped path ends in
`planner.results_0_uniqueTaps[1]`. An independent timing candidate registers
projected locations before duplicate detection/counting and address issue;
all 14 planner/service checks, nine native checks and one complete-service
emission pass. Fresh synthesis is active; its timing gate remains open.


The original four-client/one-credit cluster mapping also completed, with
core/I/O slacks -250.869/+267.782 ps. Its scoped-cancel input reaches a backend
count register. A separate 859-input candidate registers the cancellation
handoff with lease-version protection and immediate queued-admission poison.
All 32 cluster/pool/reset checks, nine native checks and three emissions pass.
Fresh synthesis targets the actual three-client/four-credit renderer cluster.
The default standalone service's 80 emitted files match the planner candidate
after comments and one bijective compiler-generated wire rename; no cluster
or native-endpoint timing carries through that comparison.

Whole-host held reset now passes on the distinct 858-input checkpoint: four
MMIO reset cuts during real vertex/fragment texture reads and four healthy
frames across local/spilled payloads and 1x/4x MSAA. The checks retain VM
ownership, abort queued DMA, drain payload/physical owners, publish changed
texture data at the same addresses, and compare exact MRT/depth/resolve output.
The 857-input structural proof counts four CUs, one four-credit pool and two
clusters in each typed, binned and full-capacity product host. These proofs
remain scoped to their sources.

The [instruction-context candidate](performance/mobile-native-rgba8-instruction-contexts-2026-10-21.json)
adds up to four lightweight lane contexts around one metadata engine. Another
warp can enter descriptor resolution while an earlier instruction drains.
Per-warp tuple ownership, private context/lane tags, fault-atomic vector
retirement and held writeback ownership remain bounded. Invalidation drains
accepted offers, then returns untouched lanes to the general resolver. Three
isolated ownership tests and all11 real native endpoint tests pass on the861-input
source, including two-warp overlap in both stages. Fallback, stage emission,
latest whole-host runtime and synthesis gates remain open. Physical cache,
fill, data-ID and PTW budgets are unchanged. This is further H4 work; it does
not establish sustained one-sample-per-cycle native issue or H0-H8 completion.


The [checked-snapshot candidate](performance/mobile-native-rgba8-snapshot-cache-2026-10-21.json)
adds four task-scoped metadata entries keyed by the complete resource address,
work identity, binding unit and FP32 LOD word. An eligible later instruction
can dispatch every active lane directly into its reserved stream context,
reusing the checked level/sampler/swizzle metadata across components and warps.
Coordinates, offsets, masks, destination and data remain per instruction.
Invalidation, trusted task launch and cancellation clear the cache; a hit
invalidated before context admission resumes through the general resolver.
Only the existing explicit aligned nonmip RGBA8 2D eligibility is accelerated.
All six cache/ownership checks and15 native tests pass on863 inputs,
including zero descriptor reads on warm instructions, changed coordinates,
binding/LOD misses and same-identity new-backing publication. The44 current-source functional checks and seven emissions pass. Latest
whole-host runtime, sustained-rate and fresh endpoint timing gates remain open. The physical cache/fill/data/PTW limits do not change.


The [warm native rate and cached-edge checks](performance/mobile-native-rgba8-native-rate-2026-10-21.json)
add four passing tests with production/ABI bytes identical to that snapshot
source. Cached first-lane physical faults and NaNs suppress the entire vector;
held reset drains queued offers, drops snapshots and recovers. The default
16-lane/eight-warp endpoint executes32 warm instructions/512 samples with zero
descriptor or texture memory reads. Its448 middle samples span10216 cycles
(0.043853 samples/cycle), with an89-cycle maximum admission gap and four public
credits. This fails the native one-sample-per-cycle target. It measures one
native endpoint and one real shared cluster client, without CU issue or frame
performance. Component192-groupII1 cannot substitute for this result.

The861-input predecessor now passes all37 functional checks and six endpoint/
stage emissions. Its full endpoint/stage audit finds one metadata engine and
four stream contexts, with unchanged native public ports. Default legacy
wrappers each prove6617 equivalence cells and zero unproven cells against the
older qualified control, using unchanged leaf modules and one execution-wire
rename. This is functional composition evidence, not timing carry. The source-
scoped host refresh passes22 typed AXI frames; binned/reset cases remain active.
The changed snapshot source separately queues its full host and product refresh.
