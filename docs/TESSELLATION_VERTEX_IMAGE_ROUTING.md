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

## Staged results and remaining integration

The complete staged 777-production/1120-test source union freshly compiles.
The actual isoline, triangle and quad typed parents pass CIRCT lowering at VGA
with full-width interfaces, enabled payload/TF/uniform/dynamic-TES paths and
assertion layers. The exact profile, bounded results, external-peer limits and
public qualification entry are in the
[typed-parent validation](TESSELLATION_VERTEX_INPUT_IMAGE.md#staged-validation-and-limits).
No frozen GPU provider or reduced interface is used for those results.

The actual Owner/Publisher cancellation loop is broken by retaining the
child's stop indication instead of combinationally echoing it back. Restoring
only that echo fails CIRCT's real loop check. Protocol tests also retain task
and frame reports through later valid-only flushes and actual child drain.

These optional ports and typed parents do not establish original native
GNT/GVS/GND capture, complete range iteration or protected ownership. Actual
original VS/shared-CU shader execution, complete resources, final host routing
and product/timing qualification remain required. A lowered external image
interface does not prove sample, image or advanced-blend execution. No default
factory, available getter, public API or discovery bit is enabled.
