# Owned VS routing within tessellation image pipelines

The isoline, triangle and quad `TessellationSystem*ImagePipeline` constructors
accept a final `ownedVertexSource: Boolean = false` parameter. The default
retains the original shared port, payload pin layout and source-to-geometry
connection. It does not instantiate the additional router or expose its ports.

When enabled, `vertexShared` exposes an ordinary VS client interface and
`TessellationVertexShaderRouter` arbitrates it with the existing TCS/TES source.
The patch side retains tessellation and the configured evaluation-patch
capability; the VS side receives neither capability. Its output enters the
existing geometry/fragment router. Fragment image publication remains on the
original fragment branch, including the existing image-capable interface.

`vertexRouterDrained` reports only this inner router's queued and active task,
invalidation and slot lifetime. It does not prove that a patch context, checked
uniform or evaluation-read service, memory client, payload producer, fragment
task or frame has drained. The image pipeline's existing whole-frame drain also
requires this inner router to drain. A parent must combine the appropriate
actual child lifetimes before acknowledging a patch retirement; frame `busy`
must not be used as the per-patch retirement condition.

With payloads enabled, `vertexPayload` accepts the actual VS producer's
`FragmentPayloadPort`. Its control, data, responses and task binding use the
existing external owner-zero store port. The producer receives the frame's
captured `contexts(0)` and the original owner-zero cancellation signal. The
parent must gate producer cancellation until the VS task has been accepted;
cancelling an unaccepted held offer can otherwise prevent its acceptance.
Fragment engine `e` continues to use external port `e + 1`.

The inner patch client has no payload binding. The inner VS client takes the
actual producer's task binding, and the outer geometry client takes the inner
router's selected binding. `payloadPins(engines, ownedVertexSource = false)`
retains the original `4 + 2 * engines + 4 * engines` pins and order. Enabling the
option appends all four inner-router pins, preserving active and prospective
leases through the existing routers. No memory transaction ID is added.

## Validation and remaining integration

Source checks cover the identical three-domain routing branches, retained
default-path statements, unchanged original pin indices, appended pin range,
captured context, original cancellation and producer-derived task binding.
`git diff --check` also passes. These checks do not compile or elaborate Scala.
Scala compilation, focused routing/payload RTL tests and actual CU execution
remain pending for this connection. The disabled Scala regression workflow must
not be enabled or triggered. The defaulted second argument of `payloadPins`
changes its Scala method signature; all callers must be recompiled rather than
using previously compiled one-argument callers as runtime validation.

The optional ports do not implement the owned VS parent, GVS/GND admission,
original draw range iteration, patch opening/load/reply/seal ordering, protected
outer BUILD or whole-owner retirement. Those integrations must retain the
original resources and use actual VS execution and patch acknowledgements;
this route does not authorize native records or synthesize patch data.

For source review, compare the three `TessellationSystem*ImagePipeline.scala`
files and `TessellationVertexShaderRouter.scala`; `git show --check 7f2e500df`
repeats the committed change's whitespace check.
Future focused component validation must exercise the enabled and disabled
paths, payload active/prospective pins, cancellation, queued VS drain and
fragment image capability without changing the supported capacities.
