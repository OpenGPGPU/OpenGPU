# Shared compute and graphics CU goal

The target is one configurable pool of CUs executing host compute kernels,
vertex batches and fragment batches. A single draw must expose enough
independent batches to occupy multiple CUs. Fixed-function rasterization and
depth/stencil/blending remain shared initially; their throughput is measured
as shader capacity increases.

## Baseline

The qualified 640x480 desktop build at `6ae4126` has four general compute CUs
with 16 lanes/eight warps, plus one graphics CU with four lanes/two warps.
Vertex and fragment launches share that graphics CU. Increasing the general
CU count does not increase graphics throughput. The general CUs' texture
interfaces are disconnected in that build.

The preceding optimization reaches a first verified desktop in about
350.8 seconds. Native GLES rendering has an 11.958-second median. These are
the reference wall times, not predictions for the shared architecture.
See [the recorded evidence](performance/rtl-speed-2026-10-15.json).

## Implementation milestones

1. Share task dispatch and execution. Keep compute and graphics tag namespaces
   separate, snapshot each CU's owner and task ID, route faults and completions
   to that owner, and expose a texture backend interface for each CU. Reserve
   completion storage on admission so one stalled producer cannot trap all CUs.
2. Connect the real vertex/fragment pipeline to the pool. First validate one
   graphics task at a time with an identical CU width and shader ABI. Staging
   invalidations must reach every relevant private cache, and reset must drain
   shader tasks, faults, completions and memory traffic.
3. Allow several fragment batches from one draw to execute independently.
   Allocate separate staging/result storage and retain draw, batch and task
   identity. Retire results in the order required by depth/stencil/blending;
   preserve four-lane helper quads, discard and shader-depth semantics. Bind a
   texture descriptor to each executing task and provide sampler capacity for
   each CU, rather than funneling every CU through one serialized sampler.
4. Coordinate the 16-lane graphics ABI with userspace. The existing shaders use
   VL=4 and fixed staging offsets; changing the hardware configuration alone
   is invalid. Initial CU-count comparisons must hold lane width, shader ABI,
   scene and memory configuration fixed.
5. Qualify and install. Measure pixel-checked 1/2/4-CU scenes, compute/graphics
   contention, faults and recovery, ordered blending, MSAA, texture sampling,
   and context transitions. Use fresh traces to report GPU cycles, CU busy
   time, sampler and memory stalls, simulator ticks/second and elapsed time.
   Run fresh native/GTK proofs and full 640x480 desktop capture before replacing
   the qualified default; retain rollback and complete generated-source hashes.

## Execution contract

`SharedCuKernelDispatcher` assigns already-partitioned tasks to available CUs.
It does not divide one `KernelLaunch` across CUs. Each producer can have at
most one CU-pool's worth of unconsumed tasks; independent completion queues
and admission credits keep a blocked graphics consumer from occupying every
CU with unretirable work. A CU may be reused after its tagged completion is
stored, while the producer retains its task metadata and staging allocation
until consuming that completion.

`GpuSystem(enableGraphicsKernels = true)` exposes the graphics task interface
on the existing physical CUs and shared L2. Texture request/writeback ports
are separate per CU and active only for its graphics owner. Graphics traps
carry task and CU identity; compute traps retain their existing interface.
The graphics producer must provide compatible kernel dimensions and ABI.

The current system has shared instruction/vector SATP and TLB controls.
Concurrent address spaces are not implied by shared dispatch: the existing
host context serialization must be preserved until a per-task context
scheduling contract is implemented and tested.

## Qualified dispatch foundation

The first milestone passes 43 tests across `SharedCuKernelDispatcherSpec`,
`MultiCuKernelDispatcherSpec`, `GpuSystemSpec` and `GpuHostSystemAxiSpec`.
Randomized 1/2/4-CU dispatch checks producer tag namespaces, stalled completion
stability, failure results and forward progress with one completion consumer
blocked. Execution tests run real compute and graphics instruction streams
on the same physical pool, route texture requests/writebacks to the owning
CU, verify memory stores, and recover from a tagged graphics fault. Texture
writeback is supplied by a test backend in these execution tests; a complete
render-pipeline/sampler integration is the next milestone. These tests do not
claim a graphics throughput or Debian startup improvement yet.

Validation command:

```sh
sbt 'testOnly opengpu.system.GpuSystemSpec opengpu.system.GpuHostSystemAxiSpec opengpu.dispatch.SharedCuKernelDispatcherSpec opengpu.dispatch.MultiCuKernelDispatcherSpec'
```

Local log: `.artifacts/shared-cu-foundation-regression.log`.

## Qualified single-CU renderer

`GpuHostSystemAxi(sharedGraphics = true)` now connects the existing vertex and
fragment pipeline to the physical system CU pool. The compatibility adapter
retains one graphics task at a time, preserving existing vertex/fragment
ordering, texture state and the two-bank ABI. This removes the dedicated
graphics CU. The subsequently qualified parallel path is described below.

Explicit staging invalidations broadcast to all shared CUs. Per-CU owner
queues route acknowledgements back to either L2 coherence or the graphics
requester. Reset waits for the graphics acknowledgement to be consumed.
During reset drain, an already admitted render may still launch its remaining
shader batches and invalidations; blocking those batches deadlocks the draw.
The command router rejects new renders, and shader admission closes once the
renderer reports drained.

The final regression passes 34 tests: 27 real programmable rendering/VM/fault
and recovery cases, three 1/2/4-CU invalidation tests, and four dispatcher
tests. A separate four-test reset regression covers legacy rendering, shared
rendering, shared cache acknowledgements and command-path reset. The initial
broader 75-test run passed 74 cases and exposed the reset deadlock above;
the failed case passes after the fix, including the complete rendering rerun.
The broader run also passed all 27 legacy shader execution cases and all
18 compute/system cases.

Emit the opt-in, existing-width configuration with:

```sh
sbt 'runMain opengpu.elaboration.EmitGpuHostSystemAxi .artifacts/shared-cu/rtl-one-cu --frag-core --vert-core --shared-graphics --compute-units 1 --cu-lanes 4 --cu-warps 2 --width 16 --height 16'
```

The generated RTL contains exactly one `GpuComputeUnit` instance in
`GpuSystem.sv` and none in `KernelShaderStage.sv`. The emitter requires an
explicit four-lane/two-warp selection for this initial shared shader ABI;
the normal default configuration remains separate. No new Debian executable
is installed by this milestone. Structural hashes, test scopes and commands
are retained in [the single-CU evidence](performance/shared-cu-single-2026-10-15.json).

Next, multiple shader clients need tagged task/texture routing, independently
allocated batch storage and ordered output retirement. The current two banks
must not be shared by concurrently executing fragment workers. Hardware and
software will need an explicit lane/warp/bank-count contract before enabling
the parallel mode.

`SharedShaderTaskRouter` provides the next routing layer: independent shader
clients retain their local task IDs while pool tags select a client. Scalar
and vector texture traffic follows that client's task onto whichever CU runs
it. Three tests pass for 2/4-CU routing, swapped physical CU placement,
out-of-order completions, faults, client reuse and held invalidation replies.
The parallel renderer now connects vertex and fragment worker clients through this router.

The shader resource query is now implemented at MMIO `0x158`, capability bit
23 and DRM parameter 1. It reports lane count, resident warps, fragment bank
count and graphics executor count in separate bytes. The current shared
compatibility path still advertises two banks. The driver and pipe library
validate the description and use its bank count for fragment allocation,
binding bounds and uniform replication; older hardware retains two banks.
Vertex bank allocation is unchanged. Zero-stride fragment bindings are
rejected when a future parallel configuration advertises more than two banks.

Resource discovery passes 21 Scala/AXI tests and eight kernel-free driver
test programs. AArch64 kernel-module and userspace compilation pass using
isolated output directories. The 16-lane case in the resource-register test
checks encoding only; shared 16-lane shader execution remains unqualified.
Logs are `.artifacts/shared-cu-shader-config-tests.log`,
`.artifacts/shared-cu-driver-unit-tests.log` and
`.artifacts/shared-cu-driver-build.log`.
Mesa staging also copies the shared resource decoder and ABI header; the
OpenGPU Gallium static library compiles successfully with those staged
headers (`.artifacts/shared-cu-mesa-compile.log`).

`ParallelFragmentScheduler` partitions whole quads into worker batches and
orders their output using batch tokens and distinct real draw-end markers.
Each worker seals one batch through its existing flush/retire interface and
owns a disjoint pair of kernarg banks. The three 1/2/4-worker tests delay
workers independently, stall output, preserve helper quads and depth
sidebands, and cover partial/no-output batches, empty draws, late flush and
zero-stride serialization. These scheduler unit tests use simulated worker backends; the real integration is qualified below. Extracting the reusable
frontend interface also passes all 16 existing `KernelFragStageSpec` tests.
Logs: `.artifacts/shared-cu-fragment-scheduler-tests.log` and
`.artifacts/shared-cu-fragment-interface-tests.log`.

`FragmentStagingArbiter` retains one registered worker request through its
response, preserving the existing staging-ID window, masks, cache policy and
fault indication. `FragmentTextureArbiter` permits distinct texture addresses
to complete out of order and delays duplicate addresses until their previous
owner consumes the response. Three tests pass for 1/4 staging clients,
colliding local IDs, held requests/responses, fault routing and duplicate
texture ownership. Log: `.artifacts/shared-cu-worker-memory-tests.log`.

## Parallel renderer qualification

`--shared-graphics --parallel-graphics` connects one fragment worker and sampler
per system CU. Independent worker tasks execute on the shared pool alongside
the vertex client. Each worker owns two advertised fragment banks. Batch tokens
restore raster order before sample expansion and output merging. Texture
responses follow their owning worker; the common address-only texture memory
port defers duplicate outstanding addresses until the earlier response retires.

The regression passes 36 tests: all 27 four-CU programmable texture, translated
instruction/staging, fault/recovery, reset and CPU-update cases plus nine task
routing, scheduling and memory arbitration tests. Four additional pixel-checked
cases compare one and two CUs, and the legacy vertex/fragment case also passes.
These 41 cases are recorded in
`.artifacts/shared-cu-parallel-render-regression.log`.

With identical four-lane/two-warp CUs, 16x16 scenes, eight-set/two-way L2 and
8-byte AXI beats, the counters report:

| Scene | 1 CU cycles | 2 CU cycles | 4 CU cycles | 1-to-4 speedup |
| --- | ---: | ---: | ---: | ---: |
| Fragment program | 18,137 | 12,451 | 10,639 | 1.705x |
| Vertex + fragment programs | 20,533 | 14,845 | 13,063 | 1.572x |

Peak simultaneously occupied CUs is exactly 1/2/4 in the respective runs.
All compared outputs pass the pixel reference. These are GPU-cycle results,
not host simulation wall-time or Debian startup results. Trace analysis,
16-lane software and guest qualification
remain required before installing a replacement desktop executable.

Reproduce the parallel tests with:

```sh
sbt 'testOnly opengpu.system.ParallelCuProgrammableTextureAxiSpec opengpu.graphics.FragmentWorkerMemorySpec opengpu.graphics.ParallelFragmentSchedulerSpec opengpu.graphics.SharedShaderTaskRouterSpec' 'testOnly opengpu.system.SingleParallelCuProgrammableTextureAxiSpec opengpu.system.TwoParallelCuProgrammableTextureAxiSpec -- -z "guest-sized"' 'testOnly opengpu.system.ProgrammableTextureAxiSpec -- -z "instruction vertex guest-sized fragment"'
```

Two further four-CU integration tests pass for ordered blending/depth at 1x
and 4x MSAA. Three overlapping draws must blend in submission order and reject
a later depth-failing draw. Every sample matches independent coverage, color
and affine depth references; all eight fragment banks retain their uniform
tails despite delayed memory responses. Log:
`.artifacts/shared-cu-ordering-tests.log`.

The initial shared ABI still requires explicit `--cu-lanes 4 --cu-warps 2`.
The previously installed desktop model remains unchanged.

## Performance interpretation

The previous trace attributes about 84% of textured batch cycles to shader
execution. If only this portion scaled perfectly over four identical CUs,
the upper estimate would be about `1 / (0.16 + 0.84/4) = 2.7x` for that batch
workload. This is not a desktop speedup promise. Shared memory bandwidth,
sampler throughput, staging and output ordering can limit cycle scaling.
More active CUs also increase host simulation work per tick. Acceptance must
therefore report both GPU cycles and actual simulator wall time.

## Wide-batch preparation

Fragment staging now coalesces line-aligned wide batches as well as the
existing eight-fragment ABI. Masks preserve shader colour outputs, inactive
lanes and uniforms; fully masked write lines and inactive output-read lines
are skipped. A full 128-fragment batch uses at most 64 writes and 24 reads
instead of 1,024 word writes and 384 word reads. These are transfer counts,
not measured application wall-time gains.

All 16 `KernelFragStageSpec` tests pass, with the staging case extended to
4x2, 16x2 and 16x8 configurations, full and partial batches, complete output
readback and sentinel preservation. Log:
`.artifacts/shared-cu-wide-staging-tests.log`. The wide checks use a cease-only
kernel to isolate staging. Stock wide shader binaries and desktop execution
still require coordinated software changes.

Wide `vtex.sample` now calculates LOD separately for each four-lane quad,
including masked helper coordinates, instead of reusing the first quad's
LOD. Gradient calculation reuses the existing arithmetic between quad
boundaries; inactive quads avoid unnecessary recalculation. Five sampler
tests pass, including distinct mip levels across a 16-lane vector, masked
helpers, inactive middle quads, consecutive requests, memory backpressure
and held writeback. The two four-CU ordered 1x/4x renderer tests also pass
after the wide-staging and sampler changes (seven tests total). Log:
`.artifacts/shared-cu-wide-texture-tests.log`.

The driver's boot-test shader now takes VL from the advertised lane count
and constructs large SoA addresses using bounded signed-immediate additions.
It preserves the four-lane fallback on older hardware. Nine kernel-free
driver test programs pass; the new builder test checks sandbox acceptance
and independently executes every warp's effective vector addresses for
8/32/128/252-fragment batches. The isolated AArch64 module builds against
kernel 7.2.0. Logs: `.artifacts/shared-cu-wide-driver-tests.log` and
`.artifacts/shared-cu-wide-driver-build.log`. The stock desktop payload is
unchanged; this does not yet migrate Mesa's shader binaries or uniforms.

Three real 16-lane/eight-warp, 128-fragment-batch renderer tests now pass for
1/2/4 shared CUs. Every 32x32 gradient pixel, depth value and bank uniform
sentinel matches its reference; peak occupied CUs is 1/2/4. This uses a wide
integer copy shader, with no Mesa/vertex/FPU qualification implied. The
simple-copy scene takes 40,940 / 38,445 / 38,577 GPU cycles: the fourth CU
does not improve on two, so shared-path bottlenecks require trace analysis.
Do not compare these times directly with the narrower 16x16 textured scene.
Reproduce with CI's heap allocation (Homebrew sbt reads `JAVA_OPTS`):

```sh
JAVA_OPTS='-Xms1G -Xmx6G -Xss6M -XX:+UseG1GC' sbt 'testOnly opengpu.system.WideSharedGraphicsSpec'
```

The first local run with the default 1 GB heap passed one CU and exhausted
heap while elaborating two CUs. The 6 GB rerun passes all three cases in
about four minutes. Log: `.artifacts/shared-cu-wide-render-tests.log`.

## Shared-path bottleneck trace

The wide copy scene's per-cycle trace reproduces the same 1/2/4-CU cycle
counts with instrumentation enabled. Four-CU output-merger backpressure
occupies 25,760 of 38,577 cycles; staging request backpressure occupies 9,023
cycles. The staging port never has more than one outstanding request.
These stalls overlap and must not be added. The one-CU phases are 11,869
write, 2,910 run, 2,172 read and 22,069 emit cycles (plus idle/launch).

The framebuffer word bridge expands 4-byte operations to 64-byte requests.
Uncached line reads can merge in L2, but each masked word write still consumes
a full eight-beat AXI burst. The lower-memory AXI bridge also has a single FSM
for both reads and writes. These shared resources, rather than additional
shader CUs, are the next optimization targets. Keep full-line L2/L1 coherence
for writes; compacting the final AXI byte lanes is a separate operation.

Trace format and reproduction are recorded in
[the baseline evidence](performance/shared-cu-trace-baseline-2026-10-15.json).
`scripts/analyze_shared_graphics_trace.py DIRECTORY` validates phase accounting,
counts overlapping event unions and classifies the scene's memory traffic.
The default executable remains unchanged.

## AXI bandwidth comparison

Widening only the external memory AXI bus gives the following pixel-checked
32x32 wide-copy results. All configurations retain the same cache, shaders,
staging policy and response delays (12 write-ack cycles, two read cycles).

| AXI width | 1 CU cycles | 4 CU cycles |
| --- | ---: | ---: |
| 64 bits | 40,940 | 38,577 |
| 256 bits | 29,978 | 27,141 |
| 512 bits | 28,293 | 25,292 |

The four-CU 512-bit case saves 34.4% of cycles (1.525x) versus 64-bit AXI.
One-to-four-CU scaling rises from 1.061x to 1.119x. Four wider-bus render tests
and five AXI bridge unit tests pass, including narrow accesses in the highest
byte lanes of 256/512-bit beats, independent AW/W stalls and acknowledged
responses. The test memory responder now derives its beat width from the DUT
and normalizes narrow writes into aligned line callbacks.

This remains a synthetic GPU-cycle result: simulator wall time and desktop
startup need separate measurements. FlashSim's memory wrapper already detects
up to 64-byte AXI ports; the QEMU callback ABI supports those sizes. Generic
ARTI/Verilator master discovery still limits widths to 64 bits. The installed
executable is unchanged. Full evidence and traces are recorded in
[the AXI comparison](performance/shared-cu-axi-width-2026-10-15.json).

## Two-ID fragment staging

The staging arbiter now uses both IDs already reserved by the fragment port.
It records worker/local-tag ownership and retains each slot until its reply
is consumed. Per-worker reply queues isolate stalled consumers; flow-through
replies preserve the existing ready-consumer latency. A worker cannot reuse
an outstanding local ID.

Three routing unit tests and seven wide/ordered-render tests pass. Four-CU
cycles fall from 38,577 to 36,440 on 64-bit AXI and from 25,292 to 23,344 on
512-bit AXI. One-CU counts remain exactly unchanged at both widths. Combined
with the wider bus, the four-CU copy scene is 1.65x faster in GPU cycles than
the initial 64-bit/one-slot configuration. The 512-bit four-CU trace still
records 16,221 OM stall cycles; this remains the dominant target.

The broader translated shader/fault/reset suite also passes all 27 cases,
including reset recovery and guest-sized fragment and vertex programs.
Results and overlap-aware counter interpretation are retained in
[the staging evidence](performance/shared-cu-dual-staging-2026-10-15.json).

## Framebuffer write combining

`--coalesce-framebuffer` enables a word-to-line write combiner only on the
framebuffer client. Four line buffers merge distinct pixel words; partial
buffers flush after 16 cycles or when all 16 word credits are occupied. Reads
remain full-line requests, preserving existing L2 read merging and coherence.
Each word retains its slot through upstream acknowledgement consumption;
one physical line acknowledgement fans out to its original word requests.
Same-word conflicts wait for retirement, and queued line payloads remain
stable under memory backpressure. No buffered write is acknowledged early.

On the same four-CU 512-bit copy scene, combining alone reduces 23,344 cycles
to 17,921 (23.2% fewer; 1.30x speedup). Framebuffer writes fall from 992 to 635,
and OM stalls from 16,221 to 10,861 cycles. Four dedicated unit tests and the
complete pixel/depth/uniform check pass. Ordered rendering, translated faults,
reset recovery and simulator wall time remain separate qualification steps;
this feature is opt-in. Reproduction details and trace hashes are in
[the write-combining evidence](performance/shared-cu-write-combining-2026-10-15.json).

## Concurrent AXI reads and writes

The lower bridge now has independent read/write engines and two queued
requests per direction. A pending second write cannot hold the shared
arbiter ahead of a read while the first write awaits B. Replies arbitrate
into a stable queue, with total credits retained until the consumer accepts
them. The L2 store table exposes a line-conflict query: unrelated reads
proceed, while same-line reads, atomics and host invalidations retain their
required store ordering.

A responder timing bug became visible with simultaneous R/B responses:
sampling RREADY before driving BVALID could discard an unaccepted read.
The integration harness now drives both channels before sampling either
handshake. All 35 memory unit cases and four combined-write translation/
fault recovery cases pass across the recorded final/rerun logs.

The matched 32x32 copy comparison is now 18,691 cycles on one CU versus
13,048 on four CUs (1.43x), compared with 28,293 versus 23,344 (1.21x)
before combining/overlap. Every color, depth and uniform sentinel passes.
Four-CU OM stalls remain 6,643 cycles; this is still weak scaling and needs
further optimization. Larger-workload, ordered-render, broad fault/reset
and guest qualification are separate remaining gates. Full traces and
scope are recorded in [the overlap evidence](performance/shared-cu-memory-overlap-2026-10-15.json).

A separate width-only guest candidate, using the old physical graphics-CU
arrangement, improves native GLES median draw time from 12.018s to 11.670s
(2.9%) across three fresh guests per binary, all pixel checked. It does not
include write combining or AXI/L2 overlap. See [the guest measurements](performance/shared-cu-axi-wall-time-2026-10-15.json);
GPU-cycle changes must not be presented as desktop wall-time improvements.

## Matched CU scaling diagnostics

The expanded 64x64 copy case is weaker than the small scene: 69,127 cycles
on one CU versus 56,611 on four (1.22x). The image is identical, but four CUs
produce 2,685 framebuffer writes versus 1,922, and 1,445 depth reads versus
1,112. Concurrency therefore amplifies memory transactions; this remains a
concrete optimization target rather than a completed scaling milestone.

A separate arithmetic diagnostic applies 1,024 dependent vector additions
of zero per lane before storing the same pixel color. It measures 441,328
versus 132,708 cycles (3.33x), with aggregate CU busy time almost unchanged:
380,861 versus 383,295 cycles. This supports effective CU execution scaling
while exposing the shared data path as the limiting factor in the copy case.
All six small/large/arithmetic renders check every color/depth pixel and
uniform tail, and verify the configured peak CU count. The arithmetic result
is a diagnostic, not a desktop-performance result or a substitute for copy
scaling. Evidence is in [the matched comparisons](performance/shared-cu-scaling-diagnostics-2026-10-15.json).

## Independent color/depth write acknowledgements

The OM can issue a passing pixel's depth write before its color write is
acknowledged. Separate pending bits accept either response order; the pixel
reservation and completion fence remain live until both replies arrive.
Aliased planes still issue serially, keeping address-tagged replies unique.
All 21 OM unit tests, both ordered MSAA scenes and both guest-sized textured
fragment/vertex scenes pass.

On the matched 64x64 copy, four-CU cycles improve from 56,611 to 52,978
(6.4%), while one-CU cycles regress from 69,127 to 70,533 (2.0%). Scaling
becomes 1.33x; this is a measured tradeoff and remains insufficient. These
numbers still use one write awaiting B in the AXI bridge. Multiple outstanding
write acknowledgements are the next bandwidth target. See [the OM evidence](performance/shared-cu-om-overlap-2026-10-15.json).

## Pipelined AXI write bursts

AW/W issue no longer waits for the preceding B acknowledgement. Separate
pending/completed ID maps restore out-of-order replies; the next burst can
start on the previous burst's final handshake edge. Total credits still
include queued replies until the upper consumer accepts them. The test
responder queues delayed B replies independently of accepting the next AW/W,
matching the existing FlashSim wrapper. Per-transaction latency is unchanged.

On the same 64x64 copy, one-CU cycles fall from 70,533 to 60,086 and four-CU
cycles from 52,978 to 26,853. Scaling becomes 2.24x; the user explicitly
requires further improvement, targeting at least 3x on this same workload.
Fourteen AXI, 27 shader VM/fault/reset, 23 host-system, two MSAA ordering and
two full copy tests pass. No simulator wall-time claim is made. See [the
pipelined-write evidence](performance/shared-cu-pipelined-writes-2026-10-15.json).

## Pending read merging and shared staging credits

The framebuffer combiner now merges same-line reads only while a lower read
is pending. Returning snapshots and writes close the affected merge windows;
completed lines are never cached on this uncached client. Per-word replies
still retain credits through consumption. The matched 64x64 copy improves
from 60,086/26,853 cycles (one/four CUs) to 59,839/25,754 cycles.

Large fragment pools can borrow all four existing staging IDs while vertex
staging is idle, restoring producer/local IDs on response. Smaller pools keep
their prior path. This reduces four-CU staging-blocked cycles from 3,578 to 28,
but elapsed cycles increase slightly to 25,943; this change is credit balancing,
not a solution to framebuffer throughput. Nine framebuffer and six routing
unit tests, two ordered MSAA tests and 27 shader VM/fault/reset tests pass.
See [read merging and staging evidence](performance/shared-cu-read-merge-staging-2026-10-15.json).

## Matched copy scaling above three times

The same 64x64 copy render now takes **48,225 cycles on one CU and 15,930 on
four CUs (3.03x, 75.7% parallel efficiency)**. Both use 16 lanes/eight warps
per CU, 512-bit AXI, OM32, eight combining groups, a 16-cycle combining age,
and unchanged two-cycle read/twelve-cycle write-response delays. Every pixel,
depth value and staging uniform sentinel is checked. Neither resolution nor
shader workload changes, and the one-CU baseline also improves.

| Incremental configuration | One CU | Four CUs | Scaling |
| --- | ---: | ---: | ---: |
| Pipelined writes, OM16/four groups | 60,086 | 26,853 | 2.24x |
| Pending read merge and four shared staging credits | 59,839 | 25,943 | 2.31x |
| OM32/four groups | 53,268 | 20,539 | 2.59x |
| L2 directory filter, OM32/four groups | 51,388 | 18,882 | 2.72x |
| L2 directory filter, OM32/eight groups | 48,225 | 15,930 | 3.03x |

Uncached reads bypass the synchronous L2 lookup. Writes bypass only when a
six-bit directory fingerprint proves that no resident tag can match; every
real tag installation updates the fingerprint. Matches and hash collisions
retain the full lookup and CU invalidation path. Concurrent fills disable the
write bypass conservatively. Cached readers cannot join uncached pending
reads, preserving both refill and directory sharer registration.

Four-CU OM-stall cycles fall from 11,693 with four groups to 4,065 with eight.
Doubling word-reply credits with separate physical line IDs does not improve
four-CU elapsed cycles (15,986 versus 15,930) and is rejected. OM32 is selected
with `--om-inflight 32`; OM16 remains the default.

Twenty-one L2 unit tests, 52 broader system tests, two matched copy renders
and two focused OM32/eight-group ordered MSAA tests pass (77 total).

This measures GPU cycles on a qualified RTL workload. It does not establish
Debian desktop wall time or complete the stock Mesa wide-ABI migration. See
[the matched trace evidence](performance/shared-cu-three-x-2026-10-15.json).

## Framebuffer request locality

With `--om-locality`, bounded same-line/same-direction bursts improve
framebuffer combining while a separate global round-robin cursor guarantees
service for other entries. At most eight local requests precede each fair
grant. Per-fragment hazards and acknowledged retirement are unchanged.

The matched copy improves from 48,225/15,930 to **47,529/15,428 cycles (3.08x)**.
Four-CU framebuffer writes drop from 1,406 to 989, depth reads from 717 to
524, and OM-stall cycles from 4,065 to 2,798. Staging now limits more of the
elapsed time, so the large traffic reduction yields a modest cycle gain.
Twenty-one OM functional cases, a continuous-hot-line fairness case, two
ordered MSAA cases and two full copy renders pass. Default arbitration stays
unchanged. See [the locality evidence](performance/shared-cu-om-locality-2026-10-15.json).
