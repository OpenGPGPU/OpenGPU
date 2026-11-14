# Original vertex capture controller

`TessellationVertexCaptureController` captures an accepted owner's
`TessellationVertexSourceRequest`, then serially connects the real GVS1 source
reader and original GND1 draw reader to one tag-zero record-memory route. A
successful capture reads eight plus 32 real 64-byte responses and returns the
accepted scope, decoded root and original `VertexShaderDraw`, without rebasing
its original indexed/instanced inputs. The 16-lane, eight-warp reader retains
16 attributes, 16 bindings, 16 varyings and all 64 component maps. The formal
GVS1 layout is recorded in [the source ABI](abi/tessellation-vertex-source-v1.json),
byte-identical to the protected encoder's catalog.

The completion reports status, failing stage/address and actual source, draw
and total bytes, including an accepted faulty response. Root and draw are zero
on every failure. An offered child submission or memory read survives cancel;
the controller consumes the real child completion before waiting for that
child to become idle. Route handoff and final reporting require both readers
idle and the actual request/response debt zero. Cancellation never erases that
debt. An observed child error, including a late memory fault, takes precedence
over external cancellation; a held final report is stable until accepted.

Fresh Chisel-plugin compilation of the current draw reader plus controller,
then the Spec, passed against current GNT/graphics dependencies. All three
component RTL cases passed, each in one 128 MiB heap/metaspace, 32 MiB code
cache, single-CPU JVM with a 180-second group limit. Current source, dependency
classes, ABI catalogs and the frozen classpath were unchanged before/after
each group. These are component results, not full-parent elaboration.

Two independent source-copy controls also compiled and failed actual RTL at
the intended checks, with production source unchanged. Adding `&& !cancel`
only to the `sourceRun` nonzero child-status guard made the first malformed
GVS error/cancel report status 4 instead of expected 1. This demonstrates the
observed-error priority check; that control stopped before the later
late-fault subcase. Adding `when(io.cancel){result.status:=4.U}` only in the
`complete` state failed the first successful capture's exact `held report
changed under cancel/input mutation` snapshot assertion. Both controls ran
one real test and exited with one assertion failure, without timeout or OOM,
under the same resource limits and before/after input guards.

The Spec uses literal canonical records and the actual two readers, covering
success/reuse, malformed/stale scope, held offers, foreign tags, cancellation
at real internal handshakes and following drain boundaries, late faults, zero
failure outputs and held-report checks. Its memory peer supplies real accepted
read responses, never synthetic child completions. For the bounded direct
run, compile `TessellationVertexDrawReader.scala` and
`TessellationVertexCaptureController.scala` together, then compile
`TessellationVertexCaptureControllerSpec.scala`, using Scala 2.13.18 and the
Chisel 7.15.0 plugin with `-Ymacro-annotations -language:reflectiveCalls`.
The classpath order is new capture classes, current GNT B7, current GNT A,
current 79-source classes, then the frozen 75899 dependency export; the current
GNT `NativeTypedGraphicsDescriptors` must take precedence over its older class.
With that classpath in `CAPTURE_CP` and an owned temporary directory:

```sh
java -Xmx128m -Xms32m -XX:MaxMetaspaceSize=128m \
  -XX:ReservedCodeCacheSize=32m -XX:ActiveProcessorCount=1 -XX:+UseSerialGC \
  -Dscala.usejavacp=true -Djava.io.tmpdir="$CAPTURE_TMP" \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simCache=off -Dopengpu.simWorkspace="$CAPTURE_TMP/sim" \
  -cp "$CAPTURE_CP" org.scalatest.tools.Runner -oD \
  -s opengpu.graphics.TessellationVertexCaptureControllerSpec \
  -z 'full original forty-response'
```

Repeat the same command with the exact filters `stop at malformed or stale
GVS` and `drain both handoff boundaries`, one case per bounded process. Require
one executed test and exit zero each time. Remove owned outputs after all
consumers finish. A normal fresh-source build can also select the same Spec
with `sbt 'testOnly opengpu.graphics.TessellationVertexCaptureControllerSpec'`.

This is not range generation, VS execution, native-host admission or a new
production route. The expected sequence/draw ordinal still comes from the
outer checked plan, not from the decoded GVS1 record. Protected code/UBO and
original-resource provenance, physical aliases, owner pinning, later
VS/TCS/TES/patch retirement, full-parent elaboration and product qualification
remain outside this capture component. No GPU execution or completed P39
integration is claimed.
