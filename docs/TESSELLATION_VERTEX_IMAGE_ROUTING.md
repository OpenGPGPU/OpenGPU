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

A 79-source Scala 2.13.18/Chisel 7.15 plugin compile passed for the complete
current dependency closure, including all three actual typed parents, their
image pipelines, actual VS source and frame control, P15 uniform services,
P40 patch providers, FP/fragment dependencies and known stale-constructor
callers. The production sources are those at `f4875ad9e`; they are unchanged
at `e06a58f62`. New classes preceded the read-only dependency classpath from
`75899c1856ee0ee0204cbd2e5d1893d6dad11f63`. Every group verified all 79 inputs,
the dependency classpath and prior output classes before/after. Only audited,
byte-identical declarations seeded the staged builds; changed providers and
callers were compiled from current source. This establishes source/plugin type
qualification, including the changed `payloadPins` signatures, rather than a
complete current-engine or host build.

An actual isoline-parent `ChiselStage.elaborate` with two CU interfaces,
16 lanes/eight warps, 16 attributes/bindings/varyings and four MRTs exhausted
its 128 MiB heap in the actual VS texture-binding path
(`TextureImageBindingUnit` / `TextureImageFormat` / Chisel `Vec`). It did not
complete construction or establish connection correctness. Triangle and quad
elaboration were not run. All three full elaborations, routing/payload RTL,
actual shader execution and product qualification remain pending. The disabled
Scala regression workflow must not be enabled or triggered.

The elaboration configuration was a 4x4 frame, one external image-engine
interface, 4096-byte pages, eight write credits/tile slots, 65536 paged capacity,
512 KiB payload capacity/64 KiB maximum payload, eight owners/twelve ports,
and 32 KiB native-sample parallel color/depth storage. Payload, transform
feedback, fragment-image publication, sample shading, tessellation uniforms
and dynamic TES patch access were enabled. `externalImages=true` avoids
instantiating a renderer bank or CU; it cannot validate sample raster/shading,
native-sample storage, advanced blend or image execution. No CIRCT lowering,
Verilator, ISA execution or native factory was run.

### Reproduce the bounded checks

Use JDK 21, Scala 2.13.18 compiler/library/reflect jars in `SCALA_CP`, and the
Chisel 7.15.0 plugin in `PLUGIN`. Compile the current dependency closure in
small provider-first groups, with an empty private `.artifacts/` output in
`CURRENT_CLASSES` preceding the read-only `FROZEN_CP`. Run each invocation
serially in an owned 180-second process group; stop on any failure. Verify
source/dependency hashes before/after and clean outputs after consumers exit.

```sh
java -Xmx128m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m \
  -XX:ActiveProcessorCount=1 -XX:+UseSerialGC -Dscala.usejavacp=true \
  -cp "$SCALA_CP" scala.tools.nsc.Main \
  -classpath "$CURRENT_CLASSES:$FROZEN_CP" -Xplugin:"$PLUGIN" \
  -Ymacro-annotations -language:reflectiveCalls -d "$CURRENT_CLASSES" "${CURRENT_GROUP[@]}"
```

For the elaboration limit, compile a temporary main calling
`circt.stage.ChiselStage.elaborate(new TessellationSystemIsolineVertexInputImage(...))`
with the configuration above, `VertexShaderStageConfig()`,
`FragmentShaderConfig(16,4)`,
`GpuConfig(lanes=16,warps=8,enableSharedVectorAtomics=true)` and
`SharedCuShaderConfig(2,taskIdWidth=8)`. Run that main with the same heap,
metaspace, code-cache and CPU flags and `-cp "$CURRENT_CLASSES:$FROZEN_CP"`.
This is a resource-limited attempt, not a passing elaboration test. A later run
with a different resource budget must report that scope explicitly; reduced
interfaces cannot qualify the full profile.

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
