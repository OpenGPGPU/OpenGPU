# Geometry execution in a compute unit

An opt-in `GpuComputeUnit(geometryAvailable=true)` snapshots one geometry
invocation when a kernel is accepted and binds its actual warp to the scalar
geometry worker when the warp launches. The internal test profile uses one
work-item in one workgroup per invocation. The task lease remains asserted until
CU completion is accepted; emit/end events leave through a held Decoupled
sideband. The parent must retain the task, drain events and preserve invocation
order. Geometry cancellation is passed to the worker.

Seven internal CUSTOM-0 scalar operations read input components or metadata,
write staged output components, layer or primitive ID, emit a vertex, and end a primitive.
Task-specific decode enables them only for the captured geometry task, even
when graphics texture operations are enabled. Other tasks receive an illegal
instruction. These encodings are internal and are not a public compiler or
software ABI.

Local tests execute a real CU program that reads a captured adjacency input,
stages a vertex, and emits/end-primitives under backpressure; the disabled task
traps. Cancellation before warp launch binds a cancelled worker, returns a
failed geometry instruction and drains to completion without emitting. The
geometry path also passes with the compute-uniform interface compiled in;
concurrent geometry and uniform operations remain untested. A separate controller/CU composition runs all 32 invocations in order
through a real CU with captured inputs and held primitive output. These tests
use a test harness, not the production graphics host. Reproduce them with:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'testOnly opengpu.core.frontend.decode.GeometryAvailabilityDecodeSpec opengpu.core.GpuComputeUnitGeometrySpec opengpu.core.GeometryCuCompositionSpec'
```

Production graphics scheduling and input assembly, multiple primitive order,
transform feedback, queries, binning, native resource publication, compiler
and API integration, multi-CU throughput and physical timing remain open.
This scoped path does not close E32-01 or H8.
