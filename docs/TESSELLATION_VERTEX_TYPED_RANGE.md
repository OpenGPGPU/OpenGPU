# Original VS typed ranges

`TessellationSystem{Isoline,Triangle,Quad}VertexTypedRange` connects captured
GNT7/8/9 metadata and the actual GVS1→GND1 capture completion to the existing
original-draw `TessellationVertexPatchSequence`. It emits the existing typed
`VertexInputImageTask` and consumes that parent's actual task report. These
adapters do not instantiate the image parent, execute shaders, publish patch
data, admit GEMs, or enable a factory or public capability.

The atomic begin contains both captures. Their complete accepted source
request, including private scope and the two independently supplied ordinals,
must match. Root task/count/layout/identity must also match the GNT template
and original draw. A local rejection emits no task and no raw child report.
An empty range retains its real zero-task semantics. Capturing an error or
finishing an empty range still requires the live local task transport to drain.

Each emitted tuple preserves the original draw, bindings, index parameters,
base instance, GUR1, component map and domain source template. Only the context
task/layout, original instance index and publication cursors vary. VS output
component mappings and TES-to-render viewport mappings remain distinct. TCS
patch output components and TES export components are independent quantities.
Per-stage GUR3 scopes are retained; sampler or memory authority is not inferred
from this metadata.

Publication accounting uses only an accepted, attributable actual
`rawSourceValid` report's `source.source.patch.published`. The top task ID and
meaningful nested IDs must identify the outstanding task. Early rejection may
have canonical zero nested fields; it cannot manufacture publication. The
complete original report and its validity flags are retained, including partial
publication on an error. VS vertex/word counts and bin admission counts are not
publication substitutes. The total is 64 bits (32-bit patch count times a
16-bit per-task count fits below 2^48).

The ordering sequence and rasterizer PrimitiveID are separate cursors. In the
absence of a GS, [OpenGL ES 3.2 §14.2.2](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf)
requires PrimitiveID to restart between original instances. The three existing
domain viewport modules add `firstPrimitiveId` to the actual primitive sequence;
their mappers, clip, viewport, raster and interpolation stages retain that ID.
`FragmentShaderStage` publishes it through the geometry-metadata input ABI.
This source trace establishes that a range counter is observable; it is not an
executed FS built-in test. The current ordinary/no-GS tessellation replay does
not enable that geometry input, and the native FS emitter accepts PrimitiveID
only under ABI7. A no-GS FS built-in ABI remains to be connected. TCS/TES also
need an independent input-patch PrimitiveID and real compiler/stage binding;
the existing generated-primitive sequence and owner task ID cannot replace it.

Next sequence and primitive cursors use 65-bit and 33-bit addition. Only healthy,
attributable actual retirements advance the within-instance patch count. At the
captured `patchesPerInstance` boundary the next primitive base becomes zero,
while the ordering sequence continues. A primitive carry cannot reject that
legal reset, but a sequence carry still prevents a necessary next task. The
initial primitive seed supports a continuation within a draw; the future frame
owner must supply zero for the start of a complete original draw.

An optional
`nextAdmissionFault` input on `TessellationVertexPatchSequence`, absent by
default, is checked only after a healthy child retires and the local child
transport drains. A necessary next-sequence or same-instance primitive overflow counts the current healthy
patch and reports status 1 with `failedPatch` identifying the next patch. A
last patch never admits a hypothetical successor. Actual current errors take
priority over cancellation, which takes priority over a continuation fault.
Zero publication advances the original slice/task/instance order without
advancing the ordering sequence. It preserves the primitive base within an
instance and still resets that base when the original instance changes.

Task reports are accepted before the independent `taskDrained` barrier. That
barrier excludes the held report and unrelated FS/render traffic. Final range
reports are snapshots: later transport flushes cannot revoke them, while the
next begin still requires live local drain. This block has no frame-end output.
The future frame composition must consume every range report and held tuple,
then deliver the existing parent's explicit end barrier on success, empty,
error and cancellation paths; whole-frame retirement separately requires the
real global memory drain.

The native outer owner must still provide a checked mapping from the GNQ array
index to the original draw ordinal. Neither render tokens nor a GVS record can
authorize that mapping. The future frame owner also needs independently trusted
draw-invocation/instance/range boundaries for continuation and new-draw resets;
a reused draw-catalog index or raw token cannot identify a new invocation.
Actual three-domain parent composition, native routing,
protected provenance, actual CU results and complete product qualification
remain pending. Scala regression must remain disabled.

## Validation

The nine-source closure passed Chisel-plugin compilation with a 128 MiB
heap, one CPU, and private output first on the classpath. The two capture-reader
sources were compiled explicitly; the existing A/B7/current-79 prerequisites
and frozen dependencies remained read-only. The shared `NativeTyped` 512-bit
field override remained ahead of the older dependency classes. Source and
upstream class/compiler inputs were checked around the runs. This is an
incremental closure check, not a fresh full-repository build. The corrected
instance-counter core and its Spec were then freshly compiled into a separate
output ahead of those nine classes; the other seven sources and all nine
prerequisite class outputs remained unchanged.

All four final typed-adapter RTL cases passed: the three domains and the
cursor/error case. They include per-instance primitive bases `[17,20,0,7]`
with independent ordering sequences `[13,16,16,23]`, a legal primitive carry at
an instance reset, and rejection of a necessary sequence carry at that same
boundary. Actual-parent-shaped early image rejection and VS-failure/cancel
reports preserve their real status, flags and attributable partial publication.
The unchanged sequence source also passed its four default cases and opt-in
continuation case.

Three independent source-copy controls failed at exact behavior assertions:
omitting sequence advancement returned 13 instead of 16; removing the next-begin
drain gate returned ready 1 instead of 0; omitting the instance primitive reset
returned 20 instead of 0. None was a timeout or infrastructure failure. Earlier
counter checks are not used as evidence for the corrected instance semantics.
Every simulation had its own 180-second process-group
limit, `simJobs=1`, low-memory `-O0` build and cache disabled; no heap increase,
SBT, actual CU model or Scala-regression dispatch was used.

`TessellationVertexTypedRangeSpec` uses the actual sequence and each actual
adapter with explicit captured-metadata and retirement interface peers. These
peers are not shader execution, capture-reader execution or a production parent.
All private simulation/class/log/control outputs were removed after their
consumers ended; the separately owned active prerequisites were preserved.

To reproduce with the corresponding read-only prerequisite classpaths, compile
the following current sources into a fresh `$OUT` using Scala 2.13.18 and the
Chisel 7.15.0 compiler plugin; put `$OUT` before the B7, A, current-79 and frozen
dependency outputs:

```sh
java -Xmx128m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m \
  -XX:ActiveProcessorCount=1 -XX:+UseSerialGC -Dscala.usejavacp=true \
  -cp "$SCALA_COMPILER_CP" scala.tools.nsc.Main \
  -classpath "$CP" -Xplugin:"$CHISEL_PLUGIN" -Ymacro-annotations \
  -language:reflectiveCalls -d "$OUT" \
  src/main/scala/opengpu/system/TessellationVertex{DrawReader,CaptureController,PatchSequence,TypedRangeControl}.scala \
  src/main/scala/opengpu/system/TessellationSystem{Isoline,Triangle,Quad}VertexTypedRange.scala \
  src/test/scala/opengpu/graphics/TessellationVertex{PatchSequence,TypedRange}Spec.scala
```

Run one selected case per bounded process, changing `$SUITE` and `$CASE` for the
two suites above. The typed adapter cases select `preserve domain 0`, `1`, `2`
or `attribute next cursor`; the sequence suite contains its four default cases
and `check opt-in continuation`.

```sh
java -Xmx128m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m \
  -XX:ActiveProcessorCount=1 -XX:+UseSerialGC \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=0 -Dopengpu.simCache=off \
  -Dopengpu.simWorkspace="$RUN/sim" -cp "$CP" \
  org.scalatest.tools.Runner -R "$OUT" -oD -s "$SUITE" -z "$CASE"
```
