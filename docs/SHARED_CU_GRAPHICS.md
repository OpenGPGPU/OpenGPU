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
