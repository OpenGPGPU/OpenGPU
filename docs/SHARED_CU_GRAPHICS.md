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

## Performance interpretation

The previous trace attributes about 84% of textured batch cycles to shader
execution. If only this portion scaled perfectly over four identical CUs,
the upper estimate would be about `1 / (0.16 + 0.84/4) = 2.7x` for that batch
workload. This is not a desktop speedup promise. Shared memory bandwidth,
sampler throughput, staging and output ordering can limit cycle scaling.
More active CUs also increase host simulation work per tick. Acceptance must
therefore report both GPU cycles and actual simulator wall time.
