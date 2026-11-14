# Owned VS and tessellation shader routing

`TessellationVertexShaderRouter` connects the original VS and the TCS/TES
patch owner to the same external CU pool. Its inner `SharedShaderTaskRouter`
has two clients and one task slot per client. It retains local command tags,
launch-time native/uniform provider flags, geometry and TES bindings, texture
requests/writebacks, traps, invalidate ownership and payload bindings.

| Client | Inner pool tag | Example original local tag |
| --- | --- | --- |
| Patch/control/evaluation | 0 | `0x63` |
| Original VS | 1 | `0` from `KernelShaderStage` |

Placed inside the existing `GeometryFragmentShaderRouter` geometry branch,
both inner tags reuse its single outer geometry slot 0. Fragment tasks retain
outer slot 1. The inner router adds two reservations and its existing launch
queue; it does not create a second live outer geometry window or another CU.
Returns and traps reverse both mappings before reaching the original client.
GUR identities remain the captured draw/patch identities, not these pool tags.

The patch and pool ports have identical geometry capabilities and independently
optional TCS and TES-read interfaces. Both optional constructor flags default
to false. The VS port has no geometry, TCS, TES-read or image publication
sideband. Missing launch bindings are explicitly zero; unexpected VS geometry,
patch access, park, finish or TES-read requests stay unaccepted and assert.
This router has no image publication interface. An image-capable outer geometry
port must use the existing explicit `connectWithoutImages` adapter with matching
other optional interfaces; fragment image publication remains independent.

The optional payload interface forwards two captured active-slot pins and two
prospective-offer pins, as well as the selected pool binding. An enclosing
owner must include all four pins in its producer-pin accounting. GUR1/GUR3
roots, original views and service credits remain the responsibility of the
real VS and patch clients. They must not accept retirement while their memory,
texture, patch inspection or writeback work remains outstanding. This router's
`drained` covers its slots, launch and invalidate traffic; it is not a complete
frame-retirement signal or a native admission check.

## Bounded results

At source commit `0d189acfc`, six current source units passed Scala 2.13.18 /
Chisel 7.15 plugin incremental compilation against the read-only frozen
dependency classes from `75899c1856ee0ee0204cbd2e5d1893d6dad11f63`.
Ten actual component RTL cases passed with 16-lane, eight-warp, two-CU
interfaces. No CU was instantiated. The positive cases exercise two real
router tiers with a physical-pool peer, held launches and provider capture,
patch/VS/fragment tag and texture/writeback isolation, held traps and returns,
payload pin lifetimes, two-tier invalidate acknowledgement locking, and all
four TCS/TES optional-interface combinations. Five negative cases independently
require the specific simulator assertion exception for unsupported VS traffic,
after checking that the offending request is not ready.

Every case used a separate process group with a 180-second limit, a 128 MiB
Java heap, 128 MiB metaspace, 32 MiB code cache, one CPU/native build job,
optimization zero and simulation cache disabled. The longest case completed
in 2 minutes 11 seconds. All six compilation inputs remained unchanged through
execution. The 151 selector CPU tests and integration/catalog checks passed.
Temporary classes, simulations, helper and logs were removed after all owned
processes exited and no open consumers remained.

These results do not cover complete current-source compilation, actual shader
execution, the new three-domain parent wiring, native GVS/GND admission,
VS-to-Context-to-TCS-to-dynamic-TES retirement, the complete owner or product
qualification. No factory, getter, discovery bit or public API is enabled.

## Focused reproduction

Use an explicitly allocated resource window, JDK 21, the Scala 2.13.18 compiler
classpath and matching Chisel plugin. Compile the complete current router
dependency closure below rather than mixing new ports with old call sites.
Run each ScalaTest case separately under an owned 180-second process-group
supervisor, with `ROUTER_CASE` set to its exact name in the Spec.

```sh
FROZEN_CP=$(cat "$FROZEN/target/streams/test/fullClasspath/_global/streams/export")
CHECK_OUT=$(mktemp -d .artifacts/vs-shader-router-XXXXXX)
SIM_OUT=$(mktemp -d .artifacts/vs-shader-router-rtl-XXXXXX)
java -Xmx128m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m \
  -XX:ActiveProcessorCount=1 -cp "$SCALA_COMPILER_CP" scala.tools.nsc.Main \
  -language:reflectiveCalls -Xplugin:"$CHISEL_PLUGIN_JAR" \
  -classpath "$FROZEN_CP" -d "$CHECK_OUT" \
  src/main/scala/opengpu/graphics/tessellation/TessellationEvaluationVectorAccess.scala \
  src/main/scala/opengpu/system/SharedCuGraphicsPort.scala \
  src/main/scala/opengpu/graphics/SharedShaderTaskRouter.scala \
  src/main/scala/opengpu/system/GeometryFragmentShaderRouter.scala \
  src/main/scala/opengpu/system/TessellationVertexShaderRouter.scala \
  src/test/scala/opengpu/graphics/TessellationVertexShaderRouterSpec.scala
java -Xmx128m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m \
  -XX:ActiveProcessorCount=1 -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=0 -Dopengpu.simCache=off \
  -Dopengpu.simWorkspace="$SIM_OUT" -cp "$CHECK_OUT:$FROZEN_CP" \
  org.scalatest.tools.Runner -oD \
  -s opengpu.graphics.TessellationVertexShaderRouterSpec -z "$ROUTER_CASE"
python3 -B scripts/test_changed_test_suites.py
python3 -B scripts/test_test_selection.py
# Remove only after every compiler, JVM and native consumer has exited:
rm -r -- "$CHECK_OUT" "$SIM_OUT"
```

Do not enable or dispatch Scala regression.
