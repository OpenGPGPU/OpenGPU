# Geometry shared shader client

`GeometrySharedShaderClient` presents the serial geometry primitive pipeline as a client of `SharedShaderTaskRouter`. It snapshots native and uniform resource permissions with task admission, passes geometry launch/event/cancellation and completion through the router, and exposes texture, trap and invalidation service to the enclosing renderer. The enclosing renderer owns those services and must retain the command tag until pipeline completion.

`GeometrySharedShaderClientSpec` connects the client and a second graphics client through the router to a two-CU `GpuSystem`. Both use the same local task tag. The test checks independent translated ownership, concurrent execution, retained resource permissions, mapped geometry output and both completions under backpressure. Run:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.GeometrySharedShaderClientSpec'
python3 scripts/test_test_selection.py
```

This is an internal renderer client, not yet connected to production draw admission, resource binding, clipping, binning, transform feedback or the public API. H8 remains open.
