# Geometry stage

Programmable geometry (GLES 3.2) runs on the shared CUs: task routing,
primitive input/output mapping, clip/viewport/raster clients, window-bin
passes and replay. Each subsection below is one bounded contract; the
capability rows stay in `MOBILE_GPU_CAPABILITY_MATRIX.md` (E32-01) and
`GLES32_HARDWARE_GAPS.md`.

## Geometry metadata in bin records

Window-primitive bin records retain the existing payload offsets and record
size. Version 1 keeps the reserved header words zero. Version 2 uses header
words 8–10 for the geometry input primitive ID, invocation ID and layer;
remaining reserved words stay zero. Readers reject unknown versions, nonzero
legacy reserved words and invocation IDs with nonzero upper bits before
publishing a record. The ordinary vertex-bin path continues to emit version 1.

The full real-CU vertex-bin suite runs locally. Routine CI selects bounded
record, producer and replay checks. Reproduce local validation with:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.WindowPrimitiveRecordsSpec opengpu.graphics.WindowPrimitiveBinReplayPassSpec opengpu.graphics.WindowPrimitiveBinProducerSpec opengpu.graphics.VertexBinPipelineSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

This record extension provides storage for geometry metadata; connecting a
geometry producer to the native bin/replay and layered attachment paths is
still required for H8.

## Geometry execution in a compute unit

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

Hierarchical Verilator builds retain the single-work-item and worker ownership
assertions inside their owning child contracts, so they do not reference private
CU nets across a compiled hierarchy boundary. The direct-CU, 32-invocation
composition and standalone worker-drain checks also pass with the CI settings:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simSeparateBuild=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.core.GpuComputeUnitGeometrySpec' \
  'testOnly opengpu.core.GeometryCuCompositionSpec opengpu.graphics.GeometryWorkerCompositionSpec'
```

## Geometry and fragment CU routing

`GeometryFragmentShaderRouter` shares a geometry-capable CU pool between one
geometry and one fragment client. Task ownership, texture traffic, traps and
payload bindings follow the shared task router; geometry sidebands are exposed
only to the geometry client. The bounded test checks held launches, per-client
payload capture, CU ownership, geometry events and reordered completions:

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GeometryFragmentShaderRouterSpec'
```

The enclosing draw owner, native submission and full H8 qualification remain
open.

## Geometry-to-fragment draw stream

`GeometryFragmentDrawStream` owns a draw descriptor across a stream of
assembled primitives. It serializes each primitive into the existing
one-primitive geometry/fragment interface, carries the returned geometry
sequence forward, and assigns draw-wide fragment-quad sequence numbers.
Completion retains the source report, primitive and quad counts, and the first
reported status until consumed.

An offered child draw remains stable until accepted even if cancellation
arrives; cancellation then reaches the child and the primitive source. The
source stream is drained, accepted child output is retained through
backpressure, and a source fault takes precedence over the resulting child
cancellation. A mismatched source identity or primitive count fails the draw.

Run the bounded controller tests:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 'testOnly opengpu.graphics.GeometryFragmentDrawStreamSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

`GeometryFragmentDrawPipeline` connects that controller to the real
geometry/fragment CU pipeline. Its local 4×4 qualification feeds two assembled
primitives through real geometry and fragment shaders, checks draw-wide output
sequence and shader-written builtins, and waits for memory and completion to
drain. Run it outside the PR/nightly lanes:

```sh
sbt -J-Xmx5G -J-XX:ActiveProcessorCount=2 -Dopengpu.productQualification=0 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GeometryFragmentDrawStreamSpec opengpu.graphics.GeometryFragmentDrawPipelineSpec'
```

Native draw submission, attachment ownership, broader topology and real-source
integration remain open. No product frame-rate or physical timing result is
implied; H8 remains open.

## Geometry to fragment pipeline

`GeometryFragmentPipeline` admits one linked geometry/raster task and fragment
draw together, routes both shader clients through the shared CU pool, and
retains ownership until raster and fragment completion and CU drain. It checks
work identity, varying count, sample mode and centroid qualifiers before
launch; cancellation propagates to both clients while offered output drains.
Local shader payload routing is supported through the shared payload ports.

The local full-system test uses a 4×4 frame and a real CU to run a geometry
shader through clipping, viewport and rasterization into a fragment shader.
It checks point, line-strip and triangle-strip coverage, shader-written
primitive ID and layer (including the provoking vertex), invalid admission,
and cancellation with held fragment output. The compact `GeometryFragmentAdmissionSpec`
checks cross-stage admission in CI; the full pipeline test is local-only:

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simSeparateBuild=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GeometryFragmentAdmissionSpec opengpu.graphics.GeometryFragmentPipelineSpec'
```

This is a one-input-primitive client. Native draw submission, complete shader
compiler/public ABI integration, full primitive/topology qualification,
attachment ownership and H8 completion remain open.

## Geometry invocation ownership

`GeometryInvocationController` retains one input primitive and its six
possible 64-component vertex records, validates its input count and coupled
output limits, and launches 1–32 invocations in ascending order. It combines
with the [bounded output assembler](#geometry-output-assembly); a later
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

## Geometry output assembly

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

## Geometry primitive clipping client

`GeometryPrimitiveClipClient` connects the shared-CU geometry client to the existing serial primitive clipper. It holds the source task until both geometry execution and clipping drain, retains the input primitive ID, invocation ID and layer for each clipped fan part, and reports clipped input, output and cull counts. The downstream primitive carries the shader-written primitive ID and mapped flat values.

Cancellation drains unaccepted geometry outputs, asks the active CU and clipper to stop, and retains an already offered clipped primitive until accepted. A cancelled scalar geometry operation may report a failed-operation trap, which the enclosing renderer must consume. Clip faults cancel remaining geometry work; accepted output is not rolled back. Completion is held until consumed.

`GeometryPrimitiveClipClientSpec` runs a real CU shader through output mapping and clipping, checking an in-range point, near/far culling, a nonzero layer and primitive ID, held output and cancellation recovery. Run:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.GeometryPrimitiveClipClientSpec'
python3 scripts/test_test_selection.py
```

This is an internal client and has not been connected to production draw admission, viewport/raster, bins, transform feedback or layered framebuffer selection. Triangle intersections through this composed client, native API/compiler integration and physical qualification remain open. H8 remains open.

## Geometry primitive input mapping

`GeometryPrimitiveInput` turns an assembled point, line or triangle into one geometry invocation task. A linked component map selects each active input from four clip-position words and the source varying words; any 64 source words can be selected. The adapter snapshots the primitive and map, copies one word per cycle, and retains the completed task under backpressure. Invalid vertex counts, varying bounds, component counts or source indices produce a held fault instead. Input primitive order is preserved by accepting the next primitive only after the current task or fault is consumed.

The 64-component ceiling matches the minimum geometry input limit recorded in the [OpenGL ES 3.2 specification](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf). The link is responsible for supplying the active component map and retaining draw identity and other primitive metadata until downstream ownership ends. The maximum copy time is 192 cycles for a triangle with 64 components per vertex, excluding stalls.

`GeometryPrimitiveInputSpec` checks all 64 mapped words for points, lines and triangles, changed inputs after acceptance, held tasks, invalid sources and empty input. A system composition test sends a mapped triangle through the adapter and scheduler into a real shared CU, and checks two ordered invocations with held output. Run:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GeometryPrimitiveInputSpec' \
  'testOnly opengpu.system.GeometrySharedCuSchedulerSpec -- -z mapped'
python3 scripts/test_test_selection.py
```

This is an internal adapter and is not connected to a production draw. Adjacency input assembly, draw cancellation, output mapping, transform feedback, binning and host/API integration remain open; this does not close H8.

## Geometry primitive output mapping

`GeometryPrimitiveOutput` converts a completed geometry output primitive into the existing `AssembledPrimitive` format. A linked component map selects the source words for clip position and active varyings; inactive mappings produce zero. For flat varying components it copies the provoking vertex's value to every vertex before clipping. The shader-written primitive ID is selected from that vertex, while the source primitive ID remains separately available for ownership. Layer is retained in a separate output field; when the linked shader does not declare either built-in, its corresponding field is zero.

The adapter checks the retained task and source primitive IDs, vertex and varying bounds, linked output component budget and active map indices. It holds a mapped primitive or a fault under backpressure. It copies at most 204 words for a three-vertex primitive with sixteen varying slots, excluding stalls. The caller supplies a unique output sequence and retains the original draw/instance metadata and linked layout.

`GeometryPrimitiveOutputSpec` checks point, line and triangle mapping, flat components, built-in defaults, held output and invalid maps/ownership. A composition with the geometry strip assembler verifies the selected primitive ID through both triangle windings. Run:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GeometryPrimitiveOutputSpec'
python3 scripts/test_test_selection.py
```

The adapter is internal and not connected to production clipping, binning, fragment input or layered framebuffer selection. Draw cancellation, transform feedback, point-size output and host/API integration remain open. This does not close H8.

## Geometry primitive pipeline

`GeometryPrimitivePipeline` owns one assembled input primitive across linked input mapping, ordered geometry invocations on the shared CU scheduler and output mapping into downstream primitives. It snapshots the input, link layouts, shader launch, output sequence and command tag; the enclosing router must reserve that tag through the held completion. The pipeline assigns output sequence numbers in order and reports emitted primitive and completed-invocation counts. A mapping fault or sequence exhaustion cancels remaining work and reports an invalid result; an assigned sequence is not reclaimed after a fault.

Cancellation during input mapping drains and discards the input task. After CU admission it requests cancellation on the owning CU and waits for worker acknowledgement. An output already offered to the consumer remains stable and must be accepted before completion. A held completion prevents reuse. Accepted output is not rolled back.

`GeometryPrimitivePipelineSpec` covers two invocations, retained metadata, output backpressure, malformed output mapping, cancellation before and after CU admission, and cancellation with held output. Its real-system case sends an assembled triangle through a physical shared CU and checks two ordered mapped primitives, shader-written IDs and completion. Run:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.GeometryPrimitivePipelineSpec'
python3 scripts/test_test_selection.py
```

This is a serial internal pipeline, not a production draw path. Adjacency input, transform feedback, queries, layered framebuffer selection, native host/API/compiler integration, throughput and physical qualification remain open. H8 remains open.

## Geometry primitive raster client

`GeometryPrimitiveRasterClient` connects the shared-CU geometry, clipping and viewport client to the existing point, line and triangle raster/interpolation engine. It captures raster state, centroid qualifiers and depth format at admission and retains the input primitive ID, invocation ID and layer with every output quad. It holds task completion until geometry, viewport, raster and offered quads drain. Invalid raster state or qualifier bounds are rejected before geometry launches; cancellation preserves an already offered quad until accepted.

`GeometryRasterAdmissionSpec` is a compact CI check for raster state, qualifier and depth admission. The complete `GeometryPrimitiveRasterClientSpec` is local-only: it runs a real CU shader through clipping, viewport and a 4×4 raster, checks exact covered coordinates, depth, shader primitive ID and layer, and exercises near/far culling, invalid admission and cancellation with a held quad. The local fixture uses two interpolation contexts and took about 1 minute 40 seconds for a cold simulator build on this host; the client default remains eight. The eight-context version passed before admission was factored into the compact check; the revised two-context fixture is validated separately. Reproduce with:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.GeometryRasterAdmissionSpec opengpu.system.GeometryPrimitiveRasterClientSpec'
python3 scripts/test_test_selection.py
python3 scripts/test_ci_case_partitions.py
```

This internal client does not yet launch fragment shaders or connect to native draw admission, bins, transform feedback or layered framebuffer selection. Triangle intersections, broader coverage/qualifier combinations and physical qualification remain open. H8 remains open.

The optional internal fragment geometry input extends the centroid/point-coordinate
SoA input with one primitive-ID array and one layer array. A fragment stage must
explicitly enable this input and capture a valid layer with each accepted quad;
missing metadata faults the input. The public native fragment versions remain
unchanged. A real shared-CU fragment shader has read distinct IDs and layers
from consecutive quads, and the missing-sideband fault was checked with:

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "deliver captured geometry metadata"'
```

Connecting raster output to the fragment client, native submission and
shader-compiler/public ABI support remain required for H8.

`GeometryFragmentBridge` preserves the quad and its layer through fragment
backpressure, translates the raster result to fragment EOF, and retains the
raster report and EOF independently until consumed. Its bounded test covers a
stalled final quad, count mismatch, source cancellation and reuse:

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GeometryFragmentBridgeSpec'
```

The bridge is an integration component; an enclosing draw owner must still
connect the geometry and fragment clients and coordinate their cancellation.

`GeometryFragmentIngress` atomically admits a fragment draw and its raster
bridge identity. Its bounded test checks backpressure and independent EOF and
raster-report ownership. The enclosing owner still connects the producer,
shared-CU client and cancellation paths. Run:

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GeometryFragmentIngressSpec'
```

## Geometry primitive viewport client

`GeometryPrimitiveViewportClient` connects the shared-CU geometry and clipping client to the existing FP32 viewport stage. It captures viewport state at task admission, retains the input primitive ID, invocation ID and layer through each prepared part, and waits for both the geometry source and viewport stage to drain before returning a held completion. Invalid viewport state is rejected without launching geometry work. Guard-band requirements remain explicit in the window primitive.

Cancellation stops the owned source and viewport work, drains unaccepted upstream offers and keeps an already offered window primitive stable until accepted. Status and per-stage counts are reported separately; accepted output is not rolled back.

`GeometryPrimitiveViewportClientSpec` runs a real CU shader through output mapping, clipping and a 4×4 viewport. It checks exact window coordinates and depth, shader primitive ID, nonzero layer, near/far culling, invalid viewport rejection and cancellation with a held output. Run:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.GeometryPrimitiveViewportClientSpec'
python3 scripts/test_test_selection.py
```

This internal client is not connected to production draw admission, rasterization, binning, transform feedback or layered framebuffer selection. Triangle intersections, broader viewport combinations, native API/compiler integration and physical qualification remain open. H8 remains open.

## Geometry replay to fragment shader

`GeometryReplayFragmentPipeline` binds one stored geometry replay record to a
tile-clipped raster task and a geometry-aware fragment draw. The shader sees
the emitted primitive ID and the retained layer through internal fragment ABI
version 5. Identity, varying count, sample mode and centroid qualifiers must
match before either child accepts the draw. The replay source, fragment
client and held output drain before the completion is released.

The 4×4 real-CU qualification checks the two shader inputs, tile clipping,
identity rejection and held-output cancellation. Run it with the bounded
admission check:

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GeometryReplayFragmentAdmissionSpec opengpu.graphics.GeometryReplayFragmentPipelineSpec'
```

CI runs the admission check; the shared-CU composition runs locally. A native
image owner, layer-specific attachment storage, draw submission and
tessellation are still required. H8 remains open.

## Geometry replay raster client

`GeometryReplayRasterClient` rasterizes a retained window-bin record within
the supplied tile rectangle, intersecting it with the draw scissor and screen
bounds. It validates the replay identity, state handle, token and sequence.
With the default `requireGeometry=true` it also requires geometry metadata and
carries input primitive ID, invocation and layer with every held output quad.
Isoline window-bin records leave that sideband unset and use
`requireGeometry=false`; see
[CONTROLLED_ISOLINE_REPLAY_FRAGMENT.md](CONTROLLED_ISOLINE_REPLAY_FRAGMENT.md).
The primitive ID used by geometry fragment inputs remains the geometry
shader's emitted primitive ID. The raster completion can feed
`GeometryFragmentIngress`; the upstream geometry source summary is not present
in a stored record and remains zero in this completion.

The focused test compares both halves of an 8×8 tile split and a clipped
negative-origin tile against the raster reference. It also checks descriptor
rejection, output backpressure, cancellation and healthy reuse:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GeometryReplayRasterClientSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

The client is a replay handoff; native pass submission, the production image
owner and layered attachment selection remain open. H8 is not complete.

## Geometry scalar worker adapter

`GeometryScalarWorker` holds one invocation binding and a scalar warp owner.
Its internal CUSTOM-0 operations read an input vertex component or invocation
metadata, stage an output component or layer, and offer emit/end events to the
[geometry invocation controller](#geometry-invocation-ownership). Input and
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

The [CU execution path](#geometry-execution-in-a-compute-unit) is qualified only in scoped
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

## Geometry on shared compute units

The optional shared graphics port captures a geometry task with an accepted graphics launch and routes each compute unit's output events to the graphics owner. Cancellation is per compute unit, with command reset cancelling all units. Compute launches do not acquire geometry permission.

`GpuSystemSharedGeometrySpec` exercises two concurrently owned graphics compute units with different captured inputs, held output events, a competing compute launch, and a graphics launch without geometry permission. Run it with:

```sh
sbt 'testOnly opengpu.system.GpuSystemSharedGeometrySpec'
python3 scripts/test_test_selection.py
```

This interface does not yet connect the production graphics scheduler, transform feedback, or binning. Geometry qualification and H8 remain open.

## Ordered geometry scheduling on shared CUs

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

## Geometry shared shader client

`GeometrySharedShaderClient` presents the serial geometry primitive pipeline as a client of `SharedShaderTaskRouter`. It snapshots native and uniform resource permissions with task admission, passes geometry launch/event/cancellation and completion through the router, and exposes texture, trap and invalidation service to the enclosing renderer. The enclosing renderer owns those services and must retain the command tag until pipeline completion.

`GeometrySharedShaderClientSpec` connects the client and a second graphics client through the router to a two-CU `GpuSystem`. Both use the same local task tag. The test checks independent translated ownership, concurrent execution, retained resource permissions, mapped geometry output and both completions under backpressure. Run:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.GeometrySharedShaderClientSpec'
python3 scripts/test_test_selection.py
```

This is an internal renderer client, not yet connected to production draw admission, resource binding, clipping, binning, transform feedback or the public API. H8 remains open.

## Geometry window bin pass

`GeometryWindowBinPass` sends shared-CU geometry through clipping and viewport
preparation into acknowledged window-primitive bin storage. It snapshots each
task's raster state, state handle and token; assigns pass-wide 32-bit bin
sequences; and preserves the geometry input primitive ID, invocation and layer
in each replayed record. A task owns the source until its completion is
consumed; the pass owns its arenas until both the source and bin engines drain.
Mismatched pass identities are rejected before source launch, and a bin fault
cancels the source while retaining the bin failure at pass completion.

The local 4×4 real-CU test covers two ordered tasks, replay backpressure,
identity rejection, a record-store fault and a clean pass after the fault:

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GeometryWindowBinPassSpec'
```

CI runs `GeometryWindowBinAdmissionSpec` for identity and sequence-boundary
admission; the real-CU qualification above stays local.

Native draw submission, replay into fragment builtins, layered attachment
ownership and tessellation remain open. This test does not establish product
frame rate or physical timing; H8 remains open.

## Separate geometry uniform transport

The opt-in `ShaderUniformLoadUnit` and `ShaderUniformExecuteUnit` stage 3 use
GUR2/GUB2 records (version 2, magics `0x47555202`/`0x47554202`). Their constructor
requires `geometryAvailable=true`; default construction rejects stage 3.
Stage 2 remains compute. The existing VS/FS/compute providers still require
version 1 and reject geometry records before reading their data. GUR2's stage
is exactly 3 and GUB2's stage mask is exactly 8; unknown mask bits and reserved
words are rejected. Frozen GUR1/GUB1 words and GUC1 discovery are unchanged.

One GS root supplies twelve block mappings, up to 16 KiB per block, and 1024
raw default-uniform components. Its separate 24-entry binding table and default
binding 31 do not consume the VS/FS aggregate table. This transport is not an
implementation of the context's API binding-point namespace: GLES 3.2 requires
at least 72 uniform-buffer bindings, with stage and combined block limits
specified separately. See [GLES 3.2, table 21.50](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf).

`opengpu_geometry_uniform_snapshot_append` builds one kernel-private aggregate
using the existing opaque snapshot owner. It accepts four-byte-aligned original
source windows and aligns private UBO data to 256 bytes. The caller retains
original GEM references and waits producer fences before the copy callback.
Only exact source windows deduplicate; defaults retain a separate record.
Root, table and data pointers use checked VA32/LIMIT33 owner relocations, and
actual mappings are read-only and non-executable. A failed append or relocation
requires retirement of the unpublished owner; no failed call publishes output.

Local checks pass for scalar/vector raw-bit reads, inactive lanes, all twelve
block endpoints, all 1024 defaults, old-version rejection, held requests and
responses through cancellation, first memory faults and healthy reuse. The
actual-owner/MMU C check independently checks every word of twelve full blocks
and defaults, copied bytes after original-source overwrite, real RO/NX leaves,
empty/default-only/partial views, exact-window deduplication and all allocation
failure gates through success. Both Clang and Linux GCC `-O2` ASan/UBSan checks
pass, including user and freestanding kernel ABI probes. Reproduce with:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  'runMain opengpu.elaboration.EmitGeometryUniformAbi --check' \
  'testOnly opengpu.graphics.ComputeUniformRecordAbiSpec opengpu.graphics.ShaderUniformLoadUnitSpec opengpu.graphics.ShaderUniformExecuteUnitSpec'
python3 scripts/test_geometry_uniform_snapshot.py --sanitize
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

This provider and encoder do not yet connect a native GS draw or advertise GS
capability. Versioned GND/GS program admission, complete preflight, third-stage
resource routing, six-vertex adjacency assembly and actual native GS execution
remain required. Production API submission and complete H8 qualification stay
open.
