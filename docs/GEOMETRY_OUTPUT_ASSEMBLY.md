# Geometry output assembly

`GeometryOutputAssembler` is an internal, single-invocation output assembler.
It accepts an ordered task and a stream of emit, end-primitive and finish
events. Points, line strips and triangle strips produce bounded point, line
and triangle records; triangle strips alternate the first two vertices to
preserve winding while retaining the last vertex. Ending a strip discards an
incomplete primitive and resets strip parity. Each vertex carries 64 opaque
32-bit component slots and a layer value. The caller defines the linked layout
and includes applicable built-ins in the component count.

The minimum-limit profile accepts up to 32 invocation IDs, 256 emitted
vertices, 64 components per vertex and 1,024 total declared output components.
Admission checks the coupled product before any event is consumed; output past
the declared vertex count produces an internal failure. This is an internal
safety policy, not an API error definition for otherwise undefined shader
behavior. A stalled primitive is immutable. Cancellation retains an already
offered primitive until it is consumed, then reports cancellation; it does not
undo primitives already delivered.

The component processes one invocation at a time. Its caller must supply tasks
in input-primitive/invocation order and separately capture the emitted vertex
stream for transform feedback. CU geometry execution, topology/adjacency input,
layer semantics, concurrent invocation scheduling, binning, queries, host/API
publication and timing are not integrated. The component therefore does not
close E32-01 or H8. The output topology contract follows [OpenGL ES 3.2,
§11.3.2](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf).

Validation covers point/line/triangle strips, alternating winding, incomplete
strips and restarts, all 64 component slots, held outputs and cancellation,
all 32 invocation IDs, 256 vertices × 4 components and 16 vertices × 64
components, over-limit rejection and healthy reuse:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GeometryOutputAssemblerSpec'
```
