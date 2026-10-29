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

This qualifies the composed CU's fenced instruction sequence against an
adversarial memory responder. The responder models atomic effects; actual L2
RMW/coherence has separate tests. It does not establish general GLSL memory
barriers, multi-CU ordering, typed image/storage bindings, texture/attachment/DMA
visibility, or whole-host timing. Those E31-02–04/H7 requirements remain open.
