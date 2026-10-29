# CU fence ordering

`GpuComputeUnitFenceOrderingSpec` executes ordinary stores, `fence rw,rw`,
loads and a global `amoadd.w` through the real instruction frontend, scalar
backend, data cache and atomic request path. One four-lane/two-warp CU runs
three single-invocation kernels: a store fault, an atomic fault and successful
reuse without reset.

The external memory responder holds the first store request for thirteen
clocks, delays store acknowledgements by twenty-three clocks, and delays atomic
replies by thirty-one clocks. Writes become visible in its byte oracle only
when acknowledged. Assertions reject a younger atomic request or load before
its predecessor's acknowledgement, and reject register/output publication
after either fault. Precise fault PCs/addresses, held traps/completions, exact
result words (7, 41, 48), surrounding byte guards and drained responses pass.

The test passed locally with three executed kernels, two expected faults and
zero skipped cases. The actual test run took approximately fifty seconds,
including model preparation. Source/class dependency checks and raw results
remain local under `.artifacts/`. The changed-test PR gate selects this suite;
it does not require a frame render or product qualification.

```sh
sbt -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'testOnly opengpu.core.GpuComputeUnitFenceOrderingSpec'
```

`GpuSystemCachedPublicationSpec` adds ordinary cached-store publication through
four real CUs, their private caches and all four shared L2 banks. Only lower
memory is modeled. After warming all four private caches, three consumers load
the old data and poll the old flag at least twice before a producer is released.
The producer stores new data, executes `fence rw,rw`, then stores a flag on a
different cache line. Each consumer observes the new flag, executes the fence
and checks the new data. Both lower store acknowledgements are delayed by at
least 73 clocks; the flag store must follow the data acknowledgement. Memory
requests and public completions are backpressured, neighboring words stay
unchanged, and a second publication reuses command IDs and owners without reset.

This local case passed with twelve kernels, two publications and six consumers
in 996 simulated clocks, with no skipped cases. The complete test took about
three minutes and fourteen seconds including model preparation. It retains
16 lanes/eight warps per CU, with one active invocation per kernel, an eight-set,
two-way L2 and four-set, one-way private caches. It is explicitly excluded from
PR/nightly CI; reproduce it locally:

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  'testOnly opengpu.system.GpuSystemCachedPublicationSpec'
```

The single-CU fixture models atomic effects; actual L2 RMW has separate tests.
The four-CU fixture qualifies these ordinary cached-store/fence sequences with
real coherence. Neither establishes general GLSL memory barriers, typed
image/storage bindings, texture/attachment/DMA visibility, full resource
capacity, sustained rates or whole-host timing. Those E31-02–04/H7 requirements
remain open; simulation wall time is not GPU performance evidence.
