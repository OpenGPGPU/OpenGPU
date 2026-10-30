# Geometry scalar worker adapter

`GeometryScalarWorker` holds one invocation binding and a scalar warp owner.
Its internal CUSTOM-0 operations read an input vertex component or invocation
metadata, stage an output component or layer, and offer emit/end events to the
[geometry invocation controller](GEOMETRY_INVOCATION_OWNERSHIP.md). Input and
output indices are checked before use. Each emit snapshots the full 64-slot
record; the next staged record is cleared. The binding and the active outputs
must remain owned until the worker and accepted instructions drain.

A held emit/end event remains stable through backpressure and cancellation.
The composed controller accepts and discards a cancelled offered event, waits
for scalar retirement and worker acknowledgement, and retains any primitive
already offered downstream. A failed or cancelled scalar instruction returns
an unsuccessful commit for the caller to handle. These encodings and the
worker binding are internal. An opt-in CU path now decodes and executes these
operations for an enabled geometry task; the shader compiler and host remain
unconnected, and this is not a public instruction ABI.

The focused checks cover boundary input/output slots, six retained input
vertices, metadata, invalid indices and warp ownership, event/commit backpressure,
cancellation, reuse and composed controller drain:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GeometryScalarWorkerSpec opengpu.graphics.GeometryWorkerCompositionSpec opengpu.graphics.GeometryInvocationControllerSpec'
```

The [CU execution path](GEOMETRY_CU_EXECUTION.md) is qualified only in scoped
internal tests. Production input assembly, invocation scheduling across input
primitives, transform feedback, binning, resource publication, API/compiler
integration and physical qualification remain open. This adapter does not
close E32-01 or H8.

The linked task also declares a two-bit built-in output mask: bit 0 enables
layer and bit 1 enables the shader-written primitive ID. Undeclared built-in
writes fail the instruction. Each emitted vertex carries its own values; the
input primitive ID remains separate. The downstream stage must select the
primitive ID from the provoking output vertex, as required by the
[GLSL ES specification](https://registry.khronos.org/OpenGL/specs/es/3.2/GLSL_ES_Specification_3.20.html).
Fragment-stage selection and layered framebuffer integration are not yet wired.
The number of set built-in bits is included with linked component slots in the
64-component and 1,024 total-output-component admission checks. The linker is
responsible for including position and every other active output in the linked
component layout.

The output-ID and mask checks additionally exercise task-gated decode, strip
winding and a real shared-CU invocation with distinct input and output IDs:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'testOnly opengpu.core.frontend.decode.GeometryAvailabilityDecodeSpec opengpu.graphics.GeometryScalarWorkerSpec opengpu.graphics.GeometryInvocationControllerSpec' \
  'testOnly opengpu.graphics.GeometryOutputAssemblerSpec -- -z built-in' \
  'testOnly opengpu.graphics.GeometryOutputAssemblerSpec -- -z assemble' \
  'testOnly opengpu.system.GeometrySharedCuSchedulerSpec -- -z mapped'
```
