# Ordered geometry scheduling on shared CUs

`GeometrySharedCuScheduler` connects the serial geometry invocation controller to the shared graphics CU interface. It retains the task, shader launch and reserved graphics command tag, launches one scalar work-item per invocation, and selects events and cancellation by the matching CU ownership tag. It accepts completion only for its own tag. The caller must reserve that tag exclusively and route unrelated graphics traffic separately until the geometry task completes. Accepted output remains subject to the controller's drain and cancellation contract.

`GeometrySharedCuSchedulerSpec` runs four ordered invocations of a real shader through a two-CU `GpuSystem`, checking captured input, invocation IDs and held primitive output. A focused test checks unrelated tags and cancellation drain. Reproduce with:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.GeometrySharedCuSchedulerSpec'
python3 scripts/test_test_selection.py
```

This is an internal client, not yet wired into the production graphics scheduler. Input primitive assembly, inter-primitive ordering, transform feedback, queries, binning, host and API integration, and physical qualification remain open. H8 remains open.

The optional geometry-enabled `SharedShaderTaskRouter` retains the task with its queued client launch and translates per-CU event and cancel traffic using the active pool tag. The router test covers two clients with the same local tag, changed input after admission, a stalled pool launch and independently owned CUs:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.SharedShaderTaskRouterSpec -- -z geometry'
```
