# Control-CU isoline patch ownership

`TessellationCuIsolinePatch` retains one patch context from input loading and
sealing through a control CU workgroup, isoline generation, evaluation and
close. It accepts a single 1D workgroup whose size equals the patch output
vertex count and fits in the configured CU and the 32-invocation patch limit.
The task snapshots the kernel and warp-base mapping. The caller owns CU
reservation, shader resources and correct warp-base mappings for the duration.
After control completion, the owner reads the retained outer levels and sends
generated coordinates to the external evaluation interface. Evaluated outputs
are provisional until successful task completion.

A held kernel offer remains stable even on cancellation. The caller must
interrupt the CU when `cancelCu` is asserted, forward `terminalCu`, and return
its completion; accepted work and evaluator responses must drain before the
patch closes. Completion distinguishes admission/control failure, CU failure,
evaluation failure and cancellation. No rollback of accepted patch stores or
provisional output is implied.

The small real-CU test executes a control program that writes both outer
levels, synchronizes, and finishes, then checks the generated endpoints using
a backpressured synthetic evaluation responder. The admission test exercises
invalid launches, CU failure, cancellation of a held launch and reuse. This
does not yet connect a real evaluation shader, renderer, driver or compiler,
and is not default-profile timing or 720p product qualification.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationCuIsolineAdmissionSpec opengpu.graphics.TessellationCuIsolinePatchSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```
