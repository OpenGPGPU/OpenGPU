# Geometry invocation ownership

`GeometryInvocationController` retains one input primitive and its six
possible 64-component vertex records, validates its input count and coupled
output limits, and launches 1–32 invocations in ascending order. It combines
with the [bounded output assembler](GEOMETRY_OUTPUT_ASSEMBLY.md); a later
invocation starts only after the previous worker has acknowledged completion
and its assembled primitives have drained. The caller supplies input primitives
in the required order. A stalled launch and its input payload remain immutable.

The worker supplies emit and end-primitive events, then acknowledges completion
after withdrawing its commands. A worker fault, invalid output or cancellation
stops further launches. An offered primitive remains valid until accepted, and
the controller waits for an active worker to acknowledge termination before
releasing the input. Already accepted output is not rolled back. Invalid tasks
complete without launching a worker. This is a serial ownership contract; it
does not claim concurrent invocation throughput.

Local tests cover six input vertices with all 64 component slots, all 32
invocation IDs, point/line/triangle and adjacency input counts, held launches
and primitives, cancellation before and after launch, worker faults,
over-emission, invalid tasks and healthy reuse:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GeometryInvocationControllerSpec'
```

The worker is an internal interface, not a physical CU. Input topology
assembly, shader invocation data and instructions, actual inter-primitive
ordering, transform feedback, queries, binning, host/API integration and
physical qualification remain open. This component does not close E32-01 or H8.
