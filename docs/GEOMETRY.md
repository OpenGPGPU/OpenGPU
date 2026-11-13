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

## Initial scalar GS program admission

The independent `opengpu_geometry_shader_validate_words` entry requires explicit
geometry enablement and scalar program version 1. Its private-code helper
copies the full original view unchanged into RX/no-W memory, validates only
the entry suffix, and rejects a branch into the unvalidated prefix. Original
GEM references and producer fences remain the submitter's responsibility.
Existing compute, VS and FS validators cannot opt into the GS instructions.

This first dialect admits scalar ALU/FPU, checked forward branches, canonical
bounded output loops and fences, task-owned geometry input/output/event
operations and, with an owned GUR2 root, scalar uniform loads. It rejects
ordinary memory access, vector
instructions, workgroup barriers and the other resource domains. These are
explicit implementation limits of this initial opt-in dialect, not the final
GLES geometry resource contract. It does not advertise a native/API capability.
Full geometry resources, six-vertex adjacency, 32 invocations, 256 output
vertices and the independent 1024-component bound remain required for H8.

A bounded output loop contains only register-preserving geometry output writes
and emits, followed by `addi counter,counter,-1; bne counter,x0,body`. Every
incoming path must prove a constant remaining count of 0–255 at the first
backedge, and every body input must remain defined there. No other register
write, nested/control-flow instruction or pending alternate exit is admitted
inside this proof. The exit counter is known to be zero. This supports a
19-word program emitting 256 four-component vertices within the unchanged
512-word code limit. Unknown bounds and other backward branches remain
unsupported; general GLSL loops and the full geometry resource contract remain
required work. Task-owned output/component limits and production enablement
retain their original checks.

The actual-owner/MMU check passes for all seven geometry operations, disabled
and wrong-version rejection, old-validator isolation, scalar floating-point
gating, resource and malformed-instruction rejection, 512-word code, exact RX
bytes after source overwrite, entry-suffix bounds, allocation/copy failures and
healthy reuse. Reproduce the owner check and run the full Linux GCC `-O2`
driver suite with:

```sh
python3 scripts/test_geometry_program_snapshot.py --sanitize
python3 scripts/test_driver.py
```

The CPU sanitizer gate also covers every loop bound 1–256, malformed bounds,
counter/body mutations, skipped initialization at a CFG join, a pending
alternate exit, zero-counter fallthrough, and immutable private RX publication
of the original 19-word program. The native program owner and the full shader,
storage, image, atomic and SIMT validator corpora pass ASan/UBSan with the loop
extension. The GS and native RX owner sanitizer gates also pass with Linux
GCC, along with the full Linux GCC `-O2` driver suite and its real Mesa callback
tests. The same 19-word program separately passes direct physical-source
execution with 256 points and 1024 components in the
[native GS viewport gate](#native-gs-clipping-and-viewport-component). CPU
protected validation/private RX ownership and direct physical execution are
separate passing gates; execution through complete protected submission remains
unqualified.

## Bounded GNG1 metadata and private links

GND2 has a distinct magic/version (`0x474e4402`, 2) and retains all GND1 payload
positions. Words 508–511 hold only a bounded GNG1 reference. GND1 continues to
reserve these words. GNG1 is a separate 1024-byte record with accepted identity,
program/code allocation bounds, GUR2 reference, complete 64-input/68-output
component mappings, VS/FS varying layouts and geometry invocation/output limits.
Adjacency modes preserve four or six inputs as defined by the [Khronos GLES 3.2
header](https://github.com/KhronosGroup/OpenGL-Registry/blob/main/api/GLES3/gl32.h).

The explicitly enabled `NativeGeometryProgramReader` reads all sixteen lines
with four owned tags, handles reordered replies, and validates the full record
before publishing metadata. It checks the 1024 total-output-component bound
separately from 256 vertices and 64 components, counts enabled built-ins, and
keeps 32 invocations and all sixteen varying slots. Invalid metadata exposes no
program. Cancelled offered requests and accepted replies drain before reuse;
held results remain stable. This is a static metadata component, not an
instruction validator, a CPU immutability guarantee or a native GS draw route.

`opengpu_geometry_snapshot_append` captures the metadata and source plans before
callbacks, requires zero raw addresses, checks identity/topology/layout and
registers typed private links to real RX code and RO GUR2 data. Code retains its
original logical end and validated entry suffix; its separate allocation view
covers real zero-padded 64-byte lines. Owner relocation bounds are unchanged.
The C fixture checks full copied UBO/default bytes, all unpatched metadata words,
exact code and zero padding, actual Sv32 permissions, two simultaneously live
owners and independent retirement, original-source/plan overwrite, cheap
metadata rejection, every copy/allocation failure gate, cleanup and reuse.
These component checks pass with Clang and Linux GCC `-O2` ASan/UBSan, including
freestanding kernel/user ABI probes. The bounded RTL suite passes for all
reserved words/narrow padding, full mappings, all input topology classes,
capacity boundaries, actual four-tag reordering, 4 GiB end bounds, single-pulse
cancellation and faults on each of the sixteen lines. Reproduce with:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  'runMain opengpu.elaboration.EmitNativeGeometryAbi --check' \
  'testOnly opengpu.graphics.NativeGeometryProgramSpec'
python3 scripts/test_geometry_snapshot.py --sanitize
```

The opt-in `NativeBinnedGraphicsJobReader(nativeGeometry=true)` scans every
referenced GND, GFS and GNG before offering the pass `begin`. A later invalid
geometry node therefore offers neither a pass nor a draw. GND1 retains zero
reserved words and exact magic/version pairing; only this explicit reader option
accepts GND2. GFS and GNG serialize through the existing four child tags, and
execution rereads the accepted metadata under the caller's immutable-publication
contract. The geometry program is a held sideband of its draw tuple, with one
shared acceptance; a consumer must route it through GS execution.

The metadata-only integration test passes for mixed ordinary/GS records, all
six adjacency inputs, 32 invocations, 256 vertices, the separate 1024-component
limit, distinct VS/FS varying layouts, reordered replies, later malformed
records and identities, every GNG line fault, alias rejection, single-cycle
cancellation, held memory and paired draw ownership, and healthy reuse. Existing
default and advanced whole-job preflight checks also pass. Reproduce with:

```sh
sbt -J-Xmx2G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'testOnly opengpu.graphics.NativeBinnedGraphicsJobReaderSpec -- -z "preflight every"'
```

This component's child acknowledges metadata tuples and executes no shaders.
No product constructor enables the new option. GND2 production submission,
physical ownership across the complete protected job graph, actual native GS
assembly/execution/binning, full stage resource routing and API qualification
remain required. The existing ordinary assembler has three vertex slots and
cannot substitute for the required six-vertex adjacency path. No native/API GS
capability is advertised here.


## Six-vertex GS input assembly

The independent `GeometryAdjacencyAssemblyStage` retains all four line-adjacency
or six triangle-adjacency VS records before GS execution. Ordinary assembly's
three-slot interface is unchanged. Triangle-strip adjacency uses the ordering
in [GLES 3.2 section 10.1, table 10.1](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf).
Its bounded ten-record history distinguishes a complete following triangle from
an odd trailing vertex; it handles first/only, odd/even middle and last cases
at restart and instance boundaries. Input vertex IDs and raw varying values do
not undergo flat replication before the GS.

Two RTL tests pass for 216 draw parameter combinations (four adjacency modes,
counts 0 through 17 and three restart patterns, each with two instances), literal
strip-order oracles, all 64 varying components, point-size bits, source gaps and
stalled consumers. They also cover held output across a single-cycle cancel,
stream identity/order errors, source faults, invalid modes, exact drain and
healthy reuse.

`NativeGeometryInputMapper` captures the complete primitive and GNG program,
validates their identity, topology, vertex count and all active source records,
and maps up to six times 64 input components. Code bounds, GUR2 root and output
link remain paired with the held invocation task. Its RTL test passes for all
ordinary and adjacency input topology classes, zero/one/64 components, complete
six-input data, 32 invocations and the independent output-component bound,
accepted-source overwrite, malformed links and records, held task/fault and
healthy reuse. Reproduce with:

```sh
sbt -J-Xmx2G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'testOnly opengpu.graphics.GeometryAdjacencyAssemblyStageSpec opengpu.graphics.NativeGeometryInputMapperSpec'
```

These are pre-execution components; no product constructor or native/API GS
capability enables them. A real VS-to-GS-to-clip/bin owner remains to be connected
and qualified. GNG1 currently maps position and user varyings, with layer and
primitive-ID output built-ins. Retained VS point-size bits do not establish a GS
point-size read/write interface. Core GLSL ES 3.20
[section 7.1.4](https://registry.khronos.org/OpenGL/specs/es/3.2/GLSL_ES_Specification_3.20.html#geometry-shader-special-variables)
defines GS per-vertex position, PrimitiveIDIn/InvocationID inputs and
PrimitiveID/Layer outputs; it does not declare GS point size. A point-size
extension is outside this initial transport. Actual native execution, all GS
resource domains and the complete native/API contract remain required.


## Shared-CU scalar geometry client

`NativeGeometryPrimitivePipeline` connects the captured six-input task to the
existing physical shared-CU scheduler, geometry events and output mapper. Its
stage-3 uniform provider reads the retained GUR2/GUB2 root instead of a VS/FS
provider. One paired launch/binding queue holds an already offered pool launch
through cancellation; the client waits for its tagged physical completion,
uniform requests/writeback and output mapping before its own held result.
Cancellation reaches a uniform service after the issued instruction transfers,
and retains already offered memory lines through drain.

The bounded RTL control test passes using an explicit external CU protocol
peer: 32 ordered invocations alternate between two owned CU ports; 96 real
uniform-unit root/binding/data reads reach the final word of a 16 KiB UBO at
logical block 11/native slot 23. It checks full six-input data and output links,
held mapped outputs, pool-offer cancellation, single-pulse cancellation of a
held uniform root request, wrong-stage root abort, trap propagation and healthy
reuse. This test supplies events and tagged completions; it proves neither CU
instruction execution nor native VS/clip/bin integration. Reproduce with:

```sh
sbt -J-Xmx2G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'testOnly opengpu.system.NativeGeometryPrimitivePipelineSpec'
```

A separate `NativeGeometrySharedCuSpec` connects this client to a real
`GpuSystem` with two CUs (two lanes and two warps per CU), not an event or
completion peer. Its scalar program reads input vertex 5/component 63 and UBO
block 11/native slot 23, emits a point and terminates on the physical CU. All
32 invocations and held mapped outputs pass, followed by a real uniform memory
fault, single-pulse cancellation of a held root request, and two healthy
invocations after changing the UBO value. The same GPU and caches remain live
without reset. This fixture observes CU0 ownership; it does not demonstrate
use of both CUs or product throughput. Admission retains the full six-input,
64-component, 32-invocation, 256-vertex and 1024-total-component limits.

The GUR2 provider supplies both decoder permissions captured at physical
launch. Uniform results return only to the retained CU/task tag; a cancelled
worker's actual completion permits local result drain if that CU has changed
owners. The scalar backend turns a cancelled non-writing uniform commit into
a tagged load fault. The client drains that physical trap while preserving an
earlier cancellation, and retains faults recorded before cancellation. Both
fault and cancel cases observe the actual uniform instruction PC and load-fault
cause. The peer test separately blocks an already completed instruction result
through cancellation and verifies isolation after CU reuse by a foreign tag.
Reproduce the actual execution gate with:

```sh
sbt -J-Xmx2G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'testOnly opengpu.system.NativeGeometrySharedCuSpec'
```

No product constructor enables this client. Its current scalar program profile
still excludes geometry texture/image/storage resources. The complete native
VS-to-GS-to-clip/bin route, full stage resource routing, production submission and
native/API qualification remain required.


## Native vertex-to-geometry source component

`NativeGeometryVertexPipeline` connects the actual vertex shader stage to raw
ordinary or six-vertex adjacency assembly and the native scalar GS owner. It
retains both child offers, serializes indirect argument admission before any
shader launch, preserves full raw VS varyings, and drains shared tasks and all
memory responses before its held result. Static GS capacity/link errors reject
before either shader starts. A source error cancels its live GS owner without
replacing the original input/VS error with the resulting internal cancellation.
The caller must retain the validated immutable program and all resource views;
complete-job reference preflight remains the enclosing reader's responsibility.

`NativeGeometryVertexPipelineSpec` passes with a real two-CU `GpuSystem` using
four lanes and two warps per CU. Its RVV VS copies all sixteen attributes and
sixteen varyings; the scalar GS consumes vertex 5/component 63 and stage-three
UBO block 11/native slot 23. It covers 32 invocations, ordinary and adjacency
topologies, arrays/elements indirect arguments, independent total-component
rejection with zero shader/memory launches, real tagged uniform faults, a held
physical request through single-pulse cancellation, and a later VS input fault
while its GS is executing. The latter observes the actual GS launch, physical
fault response and geometry cancellation, and preserves input error 3 through
all source summaries. It then changes the UBO and completes a healthy draw
without resetting the GPU or caches. All output components, original source
bytes and scratch boundary guards are checked. The full fixture observes eight
VS launches and thirty-nine GS launches; CU0 ownership is observed, so this is
not a demonstration of dual-CU throughput.

The original 200,000-cycle per-wait bound and full six-input, 64-component,
32-invocation, 256-vertex and 1024-total-component limits are retained. Reproduce
with a GPU-local `TMPDIR` and:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'testOnly opengpu.system.NativeGeometryVertexPipelineSpec'
```

This explicit component is not instantiated by a product constructor. Product
integration of GS clipping/binning, full stage resource domains, protected production GS
submission and native/API qualification remain open. The small physical-CU
fixture does not qualify the complete 2-CU 16-lane/8-warp product or its frame
rate.

## Native GS clipping and viewport component

`NativeGeometryVertexViewportPipeline` connects the physical VS/GS source to
the existing clipping and viewport stages. Input primitive, invocation and
layer identity remain attached to every clipping fan part. Its held completion
waits for all three owners and their offers to drain, retains the original
source error through downstream cancellation, and captures complete clip or
viewport fault records with the corresponding GS identity. Counters retain
78-bit mapped inputs, 81-bit expanded parts and 94-bit viewport arithmetic
totals; this does not change ordinary vertex interfaces or product routing.

`NativeGeometryVertexViewportPipelineSpec` passes with two physical CUs, four
lanes and one warp per CU, and the full sixteen-slot interfaces. It executes
32 invocations, and separately executes a 19-word scalar program that rewrites
four position components before each of 256 actual point emissions in one
invocation, reaching the 1024-component limit. It repeats that full emission
with single-pulse cancellation while the 256th viewport output is held, then
reuses the same GPU and caches without reset. Every point's sequence, input
primitive, invocation, layer and position is checked. The same program's
[bounded instruction validation and private RX ownership](#initial-scalar-gs-program-admission)
pass separate CPU gates. This physical-source fixture does not execute the
complete protected submission path.

The same fixture checks layer and shader primitive ID, near/far point culling,
NaN clipping and zero-W viewport fault attribution, and a six-input adjacency
draw whose later input fault arrives while a real viewport output is held.
Input error 3 remains primary through clip/viewport cancellation and all
accepted prefix counts balance. Two triangle invocations each produce two
clipped fan parts; exact rational intersections, viewport coordinates, winding,
shared diagonal and total area are checked independently. All cases retain the
original 200,000-cycle per-wait bound. The complete fixture observes thirteen
VS and forty-four GS launches and CU0 ownership. It proves neither use of both
CUs nor full product throughput, bin/image integration, full GS resource
domains, production submission or API completion. Reproduce with a short
GPU-local JVM temporary directory and:

```sh
sbt -J-Xmx2G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'testOnly opengpu.system.NativeGeometryVertexViewportPipelineSpec'
```

## Native GS bin and replay component

`NativeGeometryVertexBinPipeline` sends the physical VS/GS/clip/viewport source
into the existing general bin/replay owner. Eight source memory tags occupy a
separate range from record, directory, pool and replay traffic. The component
assigns one pass-wide record sequence across draws and retains shader primitive
ID, input primitive ID, invocation and layer in each stored/replayed record.
Per-draw expansion counters retain 81 bits and pass-wide received/dropped
counters retain 145 bits; the original record-arena and 32-bit bin sequence
limits remain enforced. Ordinary vertex interfaces and product routing do not
change.

`NativeGeometryVertexBinPipelineSpec` passes both its counter-bound check and
its real two-CU, four-lane, one-warp fixture with sixteen-slot interfaces and
two replay engines. It checks two draws sharing one pass, independent record
metadata and ordered per-tile replay, three invalid descriptors with no source
launch or source memory request, an eleven-cycle held final record-store
acknowledgement, nine-cycle held replay and seven-cycle held tile result through
single-pulse cancellation, physical source and record-store faults, and healthy
reuse of the same GPU and caches without reset. The original 200,000-cycle
per-wait bound remains unchanged. CU0 ownership is observed.

The bin fixture executes one or two invocations emitting one point each; the
[viewport fixture](#native-gs-clipping-and-viewport-component) separately executes
256 points and 1024 components. Stored layer 9 is metadata coverage, not a
layered framebuffer rendering result. This explicit component does not establish
mixed ordinary/GS product routing, fragment/image integration, layer-specific
attachments, protected production submission, full GS resources, API completion
or dual-CU/product throughput. Reproduce with a short GPU-local JVM temporary
directory and:

```sh
sbt -J-Xmx2G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'testOnly opengpu.graphics.NativeGeometryVertexBinPipelineSpec'
```

## Layered attachment admission and view selection

`TypedLayeredImageAdmission` validates an internal immutable binding for 1–256
layers, six source attachments and six resolve destinations before any image
memory access. Its new descriptor preserves the existing typed image format,
sample and allocation fields, adding a layer count and 33-bit plane pitches.
It checks complete layer extents, permissions, 64-byte allocation coverage and
the exclusive 4 GiB endpoint. Each prohibited alias pair merges its two ordered
layer-interval streams; legal interleaved layouts remain usable. The existing
single-layer load/store/resolve alias policy and memoryless clear/discard are
preserved. A held result is stable through cancellation.

`TypedLayeredImageSelection` checks the full requested layer before narrowing
and produces the existing single-layer descriptor, retaining allocation bounds
and limiting useful bytes to the selected plane. The enclosing owner must retain
the admitted descriptor and backing resources through all selected image work.
The admission block itself performs no load, clear, raster, store or resolve.

`TypedLayeredImageAdmissionSpec` passes all 256 selections for all 36 color
formats at 1/2/4 samples, four RGBA32UI attachments with depth/stencil and all six
resolve destinations, and a plane ending exactly at 4 GiB. It checks a collision
first occurring at layer 255, an independent enumerating oracle for 64 random
interval layouts, twelve mutually interleaved plane streams, source snapshot
isolation, held cancellation and healthy reuse. The complete twelve-plane merge
retains a 40,000-cycle bound derived from 66 pairs of at most 511 advances plus
their admission/control steps. These are numeric metadata tests, not large-frame
rendering. Reproduce with a short GPU-local JVM temporary directory and:

```sh
sbt -J-Xmx2G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'testOnly opengpu.graphics.TypedLayeredImageAdmissionSpec'
```

Default product profiles leave these blocks disabled. The retained scanner
and composed owner below qualify layer selection and real image work separately;
complete native dispatch, protected attachment/VM retirement and full host/API
execution remain separate qualification requirements.

The GFS reader and image-publication reader now have a separate default-off
`geometryInput` parameter. Only an enabled reader accepts internal FS version 5;
native whole-job preflight additionally requires a geometry draw for that
version. An ordinary draw cannot acquire this permission merely by sharing a
geometry-enabled reader or state cache. Opt-in replay wiring supplies the
record's retained layer to the fragment geometry input; primitive ID continues
through the existing prepared primitive and quad fields.

Both complete `TypedFragmentStateReaderSpec` and
`NativeBinnedGraphicsJobReaderSpec` pass, totaling 34 cases. They cover explicit
permission, sixteen varyings and centroid bit 63, the additional scratch prefix,
immutable cached/held results, cancellation and reuse, valid mixed ordinary/GS
jobs, and rejection of an ordinary final draw paired with FS version 5 before
any child begins. Existing legacy/advanced, full tile-count, allocation, alias,
fault and cancellation cases remain included. This metadata-only qualification
does not execute the replay fragment shader or establish layer-specific image
results. Reproduce under the same GPU-local temporary-directory setup with:

```sh
sbt -J-Xmx2G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'testOnly opengpu.graphics.TypedFragmentStateReaderSpec opengpu.graphics.NativeBinnedGraphicsJobReaderSpec'
```

## Retained layered record scanner

`LayeredWindowPrimitiveReplayPipeline` retains the original tile chain while
reading its pages and full stored primitive records separately for each admitted
layer. It selects GWP1 records only for layer zero and GWP2 records by their
full layer value. Selected records receive contiguous local ordinals while the
original references remain available for completion ownership. Every layer,
including an empty layer, requires a binding, end marker, image completion and
complete child/memory drain before the next layer starts. The final result
returns the original head, count, tail and exhausted-prefix restart tuple.
Source shaders and transform feedback are not repeated by the scanner.

`LayeredWindowPrimitiveReplaySpec` checks all 256 scans, mixed ordinary/geometry
records, sixteen varying slots with independent component patterns, empty
chains and usable exhausted prefixes. It checks held binding, record, end and
image results through single-pulse cancellation; actual page/record faults
retained ahead of a later cancel and secondary image cancel; immutable held
completion; and reuse without reset. Each wait retains its 200,000-cycle bound.
Its image peer supplies handshake/completion behavior only, so these checks do
not establish attachment bytes, fragment execution or product integration.
Reproduce with the GPU-local temporary-directory setup above and:

```sh
sbt -J-Xmx2G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'testOnly opengpu.graphics.LayeredWindowPrimitiveReplaySpec'
```

## Typed layered image owner integration

`LayeredTypedReplayTileImagePipeline` combines full attachment admission, the
retained scanner and one real geometry-enabled typed replay image owner. It
selects each layer's original attachment views and requires actual load/clear,
fragment work, store/resolve and child drain before advancing. It preserves
original primitive completion references and exposes held pixel, layer and final
tile results. Independent scanner/image memory tags retain their ownership
through faults and cancellation. The scanner's registered source error and the
image's real primitive/pixel error are captured before propagating secondary
cancellation.

The composed fixture uses a 1×1 frame, two CUs with four lanes and one warp,
sixteen attribute/varying slots, the existing 32 KiB native-sample storage with
64 sample owners, four MRTs, four samples and six source/resolve attachments.
Its stored records mix ordinary FS1 and geometry FS5, with actual primitive ID
and layer fragment inputs. This exercises one image owner and CU0 ownership;
it does not establish the full product geometry producer or dual-CU throughput.

Both composed cases pass. The positive case checks three complete 256-layer
passes: clear and render, an empty-chain load/resolve retaining the prior image,
and clear/render reuse without reset. It holds the final layer's actual store
acknowledgement for eleven cycles and independently checks every source/resolve
byte, layer/row/allocation padding, immutable input and fragment scratch guard.
Each layer retains the 200,000-cycle wait bound.

The fault/cancel case checks held original-reference, pixel and layer results,
single-pulse cancellation, actual page/record and image-store faults, an actual
illegal-instruction fragment trap, primary error retention and healthy reuse
without reset. Default product profiles leave this owner disabled. The
development native caller below requires further composed execution and
protected layered attachment lifetime qualification; full host/API execution
remains open. Reproduce with:

```sh
sbt -J-Xmx2G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'testOnly opengpu.graphics.LayeredTypedReplayTileImagePipelineSpec'
```


## Development native layered dispatch and completion ABI

An explicit `nativeGeometry` configuration connects the real mixed ordinary/GS
vertex and viewport source to the original bin producer/directory, four layered
image owners and the memory-native host entry. `LayeredTileChainScheduler`
retains two original-chain slots per engine and releases a running permit only
when its public tile result is accepted. Healthy empty chains reach the actual
image lifecycle; unusable fault-empty chains report without image writes.
This wiring has compiled; its complete four-owner rendering execution is not
yet qualified. The development route currently rejects transform-feedback
configuration explicitly; full native GS transform feedback remains required.

GNJ2/GNF3 and GNY2 are distinct versions described in
[the published layout](abi/native-layered-graphics-v2.json). GNY2 owns a separate
16 KiB writable view containing every full-width source, restart and image
field, including 134/142/145-bit expansion counters and checked 64-bit image
statistics. The legacy GNJ1/GNF1–2/GNX1 protocol is retained. No default product
profile or driver capability advertises the new route.

Three scheduler cases pass with an explicit image peer: all four engines and
eight slots, held offers/results, cancellation, fault-empty exclusion, reuse and
checked counter overflow. The complete GNY2 writer case independently checks
all 4,096 published words, signed padding and wide counters, the legal 4 GiB
endpoint, first/last request stalls, an eleven-cycle final ACK hold, precise
write-fault address/status, preflight failure with no writes and reuse. The
full-capacity reader case passes mixed ordinary/adjacency-six GS metadata,
32-invocation/256-vertex limits, all-reference rejection before any child begins,
GNY alias rejection, 256-layer metadata, held cancellation and reuse. Its source
and image peers acknowledge tuples; this is not shader or image execution.

Reproduce with the GPU-local temporary/cache setup above:

```sh
sbt -J-Xmx2G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'testOnly opengpu.graphics.LayeredTileChainSchedulerSpec opengpu.graphics.NativeLayeredCompletionWriterSpec opengpu.graphics.NativeLayeredGraphicsJobReaderSpec'
sbt -J-Xmx2G -J-XX:ActiveProcessorCount=2 'runMain opengpu.elaboration.EmitNativeLayeredGraphicsAbi --check'
python3 scripts/test_native_layered_abi.py
```

The ABI generator/check and 3,979 malformed-wire C cases pass. Protected GNJ2
production admission, complete physical source/layer/writer leases, transform
feedback, composed four-owner execution and the full host/API gates remain
required; these component results do not close H8.
