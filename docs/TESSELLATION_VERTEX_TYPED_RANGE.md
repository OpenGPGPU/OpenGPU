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

The native outer owner provides a checked mapping from the GNQ array index to the
original draw ordinal. Neither render tokens nor a GVS record can authorize that
mapping. The frame owner retains independently trusted draw-invocation/instance/
range boundaries for continuation and new-draw resets; a reused draw-catalog
index or raw token cannot identify a new invocation. Three-domain GNQ4/5/6
parent composition, native routing, input-patch identity and bounded protected
replay are now merged. GNQ7/8/9 geometry range/parent/protected-host integration,
actual five-stage CU/frame acceptance and complete product qualification remain
pending. Scala regression must remain disabled.

## Validation

The four typed-adapter cases instantiate the actual three adapters and range
sequence with explicit captured-metadata and retirement peers. Those peers are
not capture-reader execution, shaders or a production parent. Coverage includes
per-instance primitive bases `[17,20,0,7]`, independent ordering sequences
`[13,16,16,23]`, legal instance-reset carry, necessary sequence carry, raw report
attribution, partial publication and error/cancel priority. Five sequence cases
also retain default behavior and opt-in continuation checks.

[Fresh-source results, negative controls and reproduction](P39_CAPTURE_RANGE_SOURCE_DELIVERY.md)
record the current delivery checks. Original input-patch language identity,
exclusive cursors and GNQ4/5/6 parent composition are now merged; GNQ7/8/9
geometry joint variants, actual five-stage CU/frame acceptance and protected/
product qualification remain later stages.
