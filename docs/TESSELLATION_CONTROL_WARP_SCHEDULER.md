# Tessellation control warp ownership

`TessellationControlWarpScheduler` composes one patch context with the warp
patch-access and barrier adapters. A warp owns its accepted access until the
result is consumed, or its accepted barrier until its resume is consumed.
Another operation for that warp cannot overtake it. A different warp may park
or make progress while an access is outstanding. The scheduler blocks finish
events for invocations in parked warps and blocks all finishes while the
single access owner is busy, including on the access-acceptance cycle. This
conservative rule covers predicated operations whose access mask omits other
live lanes. On a simultaneous park and finish, a finish belonging to that park
waits.

The enclosing dispatcher must provide stable, disjoint invocation-to-warp
mappings and stop issuing after an access error. It must consume accepted
access results, barrier resumes, control results and external patch replies,
including during cancellation. Patch closure waits for these owned warp results
and resumes; a context error terminates unaccepted adapter offers while accepted
patch accesses drain. The context retains its checked per-invocation permissions
and barrier-divergence rules. Stores completed before a later error are not
rolled back.

The composed test covers two 16-lane warps and 32 control invocations, exact
patch reads and writes around a barrier, held results, predicated finish
ordering, divergent barrier IDs, cancellation, close drain and reuse.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.productQualification=0 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationControlWarpSchedulerSpec opengpu.graphics.TessellationWarpPatchAccessSpec opengpu.graphics.TessellationWarpBarrierSpec'
```

This internal ownership layer does not yet connect CU issue, instruction
execution or warp finish to the context. The TCS/TES ABI and compiler, native
submission, other tessellation domains and physical and product qualification
remain open; it does not close H8.
