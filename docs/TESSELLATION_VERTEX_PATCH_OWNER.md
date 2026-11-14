# Original VS and patch task ownership

`TessellationVertexPatchOwner` retains one accepted original VS descriptor and
one typed image task. The enclosing domain adapter must validate matching
task identity and layout, connect the real source publisher to the image
context's load/reply/seal ports, and supply actual child completions. This
controller does not execute shaders, manufacture a seal, or replace either
child's completion.

The image task transfers first. A successful `opened` event permits the VS
offer; an admission rejection can complete without opening a context or
launching VS. All original draw bindings, component maps and slice fields
remain captured until retirement. The reported task ID always comes from the
accepted original VS descriptor, including when a locally rejected image has
no meaningful nested task ID.

Cancellation does not withdraw a held image or VS offer. Only the image's
actual terminal frame completion permits abandoning an unaccepted image
offer. A held VS offer must transfer before the controller cancels its
accepted producer. `sourceOwned` also lets the enclosing adapter gate payload
cancellation until that transfer. A previously offered parent range task can
be accepted and canceled locally without starting either child.

The controller accepts the actual VS completion while `sourceBusy` remains
asserted, then waits for that busy signal and all local transport credits to
drain. Image success requires a successful matching VS completion, allowing
both actual completions to transfer in the same cycle. A launch acceptance
alone cannot satisfy this requirement. The first noncancel failure remains
primary, and a real fault takes priority over cancellation. A foreign VS task
ID or premature image success reports a protocol failure.

| Report status | Meaning |
| --- | --- |
| 0 | Successful image and original VS retirement |
| 1 | Image failure or terminal frame before image acceptance |
| 2 | Original VS failure |
| 3 | Protocol failure |
| 4 | Cancellation without a noncancel failure |

`transportDrained` covers local router and original-VS memory/payload credits.
It must not depend on unrelated fragment work or whole-frame completion.
The controller's `drained` excludes its held parent report: including that
report in the range's child-busy input would make report acceptance circular.
Whole-frame retirement still requires the enclosing image's full drain.

## Bounded results

Six current source units passed Scala 2.13.18 / Chisel 7.15 plugin compilation
against the read-only dependency classes from
`75899c1856ee0ee0204cbd2e5d1893d6dad11f63`. One actual controller RTL test
passed sixteen protocol scenarios with the default 16-lane, eight-warp VS
descriptor. Its image peer carries a 32-bit typed task; its VS peer supplies
protocol responses. No image pipeline, shader or CU was instantiated.

The scenarios cover complete descriptor snapshots, early admission rejection,
held image and VS offers during cancellation, local cancellation of a held
range task, completion acceptance while the source is busy, residual local
credits, late faults, foreign task IDs, premature image success before VS
acceptance and before VS completion, terminal-frame abandonment, nonzero
opened status, simultaneous completions, report backpressure and reuse
without reset. Actual negative controls independently failed the expected
status assertion with the old premature-success controller and the expected
ready assertion after gating VS completion acceptance on `!sourceBusy`.

Compilation and each RTL run used an owned 180-second process group, 128 MiB
heap, 128 MiB metaspace, 32 MiB code cache, one CPU/native build job,
optimization zero and simulation cache disabled. The final positive RTL run
completed in about 35 seconds. All six source inputs remained unchanged
through that run. The 152 selector CPU tests passed. Temporary classes and
models were removed after owned processes exited and open consumers and
cross-worktree symlinks were checked.

These results do not establish three-domain image integration, actual original
VS execution, native admission, complete current-source compilation, full
VS-to-Context-to-TCS-to-dynamic-TES retirement, or product qualification. The
controller does not enable a factory, public API, getter or discovery bit.

## Focused reproduction

Use JDK 21, Scala 2.13.18 and its matching Chisel plugin in an allocated
resource window. Compile the complete current closure below and place its
classes before the frozen dependency classpath. Run the JVM in an owned
180-second process group; terminate only that group if it exceeds the limit.

```sh
FROZEN_CP=$(cat "$FROZEN/target/streams/test/fullClasspath/_global/streams/export")
CHECK_OUT=$(mktemp -d .artifacts/vertex-patch-owner-XXXXXX)
SIM_OUT=$(mktemp -d .artifacts/vertex-patch-owner-rtl-XXXXXX)
java -Xmx128m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m \
  -XX:ActiveProcessorCount=1 -XX:+UseSerialGC \
  -cp "$SCALA_COMPILER_CP" scala.tools.nsc.Main \
  -language:reflectiveCalls -Xplugin:"$CHISEL_PLUGIN_JAR" \
  -classpath "$FROZEN_CP" -d "$CHECK_OUT" \
  src/main/scala/opengpu/graphics/VertexInputSlice.scala \
  src/main/scala/opengpu/graphics/VertexInputFetcher.scala \
  src/main/scala/opengpu/graphics/VertexShaderStage.scala \
  src/main/scala/opengpu/system/TessellationVertexPatchSource.scala \
  src/main/scala/opengpu/system/TessellationVertexPatchOwner.scala \
  src/test/scala/opengpu/graphics/TessellationVertexPatchOwnerSpec.scala
java -Xmx128m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m \
  -XX:ActiveProcessorCount=1 -XX:+UseSerialGC \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simCache=off -Dopengpu.simWorkspace="$SIM_OUT" \
  -cp "$CHECK_OUT:$FROZEN_CP" org.scalatest.tools.Runner -oD \
  -s opengpu.graphics.TessellationVertexPatchOwnerSpec
python3 -B scripts/test_changed_test_suites.py
# Remove only after all compiler, JVM and native consumers have exited:
rm -r -- "$CHECK_OUT" "$SIM_OUT"
```

Do not enable or dispatch Scala regression.
