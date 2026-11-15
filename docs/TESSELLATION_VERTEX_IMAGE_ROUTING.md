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

The initial full-width isoline-parent `ChiselStage.elaborate` attempt exhausted
its 128 MiB heap in the actual VS texture-binding path. A later current-source
run passed actual CIRCT 1.158.0 lowering and SystemVerilog emission at 768 MiB
heap. It refreshed all twelve changed fragment/image providers, the three
typed-parent callers, `DecodeTypes` and the Owner, ahead of source-identical
prerequisites and the verified frozen classpath. No changed-source provider
fell back to old bytecode. Source, compiler, classpath and tool guards matched
before/after.

The full configuration retains two CU interfaces, 16 lanes/eight warps,
16 attributes/bindings/varyings, four MRTs, a 4x4 frame, one external engine,
4096-byte pages, eight write credits/tile slots and 65536 paged capacity.
Payload capacity is 512 KiB with 64 KiB maximum payload, eight owners and twelve
ports. Payload, transform feedback, fragment-image publication, sample shading,
checked tessellation uniforms and dynamic TES patch access are enabled; the
32 KiB storage config enables parallel lanes/depth, native samples, eight word
ports and advanced/native draw admission. The actual lowering completed in
20.04 seconds, emitting the real top and 304 fully defined modules across
468 SV files, including 77 verification binds and 297 assertion error/fatal
pairs. No unresolved instance type, unlowered module or external blackbox
specification remained. The run used 256 MiB metaspace, 64 MiB code cache and
one processor, within a 600-second/1536-MiB aggregate-RSS guard. Largest sampled
group RSS was 1,384,624 KiB, not an absolute physical-memory peak; 34 existing
index/enum elaboration warnings remain.

Actual lowering exposed a cancel feedback cycle through the real Publisher
and Owner. The Owner now sends independent parent cancellation and observed
completion faults to an owned source immediately, but does not combinationally
echo the source's own `cancelPatch` back to it. That indication still stops the
image/new work immediately and is latched for continued drain. Four component
RTL cases passed, including a real Owner/Publisher closed loop, held offers,
loads, actual replies and seals, late faults, immutable reports after a flush,
and healthy no-reset reuse. Restoring only the echo in a source-copy negative
control fails the exact FIRRTL combinational-cycle check; production source
remains fixed.

Triangle and quad construction/lowering, actual shaders, routing/payload
execution and product qualification remain pending. `externalImages=true`
excludes actual CU/render-bank instances: flags do not prove sample/image or
native-sample/advanced-blend execution. The new language/primitive-ID ABIs are
not connected through these parents. No Verilator, ISA or native factory was
run for the parent lowering. Scala regression must remain disabled.

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

For the passing full-capacity lowering, compile a temporary main that calls
`circt.stage.ChiselStage.emitSystemVerilogFile(gen, Array("--target-dir", args(0)),
Array("--lowering-options=disallowLocalVariables,disallowPackedArrays"))`, keeping
the assertions enabled. Its `gen` is the actual
`TessellationSystemIsolineVertexInputImage` with the full configuration above,
`VertexShaderStageConfig()`, `FragmentShaderConfig(16,4)`,
`GpuConfig(lanes=16,warps=8,enableSharedVectorAtomics=true)`,
`SharedCuShaderConfig(2,taskIdWidth=8)`,
`GraphicsConfig(screenWidth=4,screenHeight=4)` and
`Fp32ViewportConfig(maxWidth=4,maxHeight=4)`.
Use `ShaderPayloadConfig(capacityBytes=512*1024,maxPayloadBytes=64*1024,
owners=8,ports=12)`, `payloadOwner=0`, `engines=1`, `pagedCapacity=65536`,
`useSramBlackBoxes=false`, and every exposed optional routing flag true.
Keep current `SharedCuGraphicsPort`, router and binding classes ahead of older
prerequisites; freshly compile every changed provider and direct caller,
including `DecodeTypes`. Verify all input hashes before/after.

```sh
CHISEL_FIRTOOL_PATH=/Users/duckdonald/Library/Caches/org.chipsalliance.llvm-firtool/1.158.0/bin \
TMPDIR="$PARENT_PRIVATE_TMP" java -Xmx768m -Xms64m \
  -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m \
  -XX:ActiveProcessorCount=1 -XX:+UseSerialGC -Xss2m \
  -Dscala.usejavacp=true -Djava.io.tmpdir="$PARENT_PRIVATE_TMP" \
  -cp "$CURRENT_CLASSES:$VERIFIED_PREREQUISITES:$FROZEN_CP" \
  FullIsolineParentEmit "$PARENT_PRIVATE_RTL"
```

Apply the owned 600-second process-group deadline and 1536-MiB aggregate-RSS
monitor. Check the top, all instance definitions and extracted verification
layers, then remove private output after process/open-file/cross-worktree
consumer checks. The prior 128-MiB failure remains a failure at its stated
budget; frontend construction alone does not establish successful lowering.

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
