# Typed original-VS tessellation image parents

`TessellationSystemIsolineVertexInputImage`,
`TessellationSystemTriangleVertexInputImage` and
`TessellationSystemQuadVertexInputImage` connect the actual
`TessellationVertexPatchSource` and `TessellationVertexPatchOwner` to their
domain's `ImagePipeline(ownedVertexSource=true)`. They do not enable a default
factory, discovery bit, native getter or public API.

Each accepted task contains the original domain bin task and an original VS
task. The shared tuple checker verifies task ID, all five patch-layout fields,
the two 2048-word capacity constraints, the original draw slice and instance,
base instance, frame/work identity and versioned uniform-scope cross-links.
GUR1 remains the VS resource version; optional TCS/TES resources use the
existing checked GUR3 contract. Dynamic TES requires zero static selection
count and array. TCS output layout and TES exported components remain separate.
The actual publisher validates its VS component map and the actual children
validate remaining descriptors. The tuple checker does not authorize a GEM,
program PC, texture or shader opcode.

The real image context opens before the original VS task can be offered.
Publisher loads and sealing connect directly to that context; source-zero
replies return to the publisher and other inspection replies remain exposed.
Cancellation never replaces a real load reply or seal acknowledgement.
Actual domain `taskCompletion.source.patch.published` supplies the Owner's
publication count, captured once with the same actual raw task packet. No
count is predicted from VS vertices, words or a CPU-generated patch.

The ordinary VS uses the image's inner VS/TCS/TES router and existing shared
CU pool. Payload cancellation reaches the producer only after the Owner has
accepted its source task. Original four-tag VS memory, external image engines,
fragment image access, sample shading, transform feedback, checked uniforms
and dynamic TES remain configurable. No extra CU or memory transaction ID is
allocated by these parents.

## Task and frame retirement

`sourceMemoryLive` holds the source-port intent through actual source ownership
and busy state. `sourceMemoryDrained` is an input from the serialized input/VS
port, independent of unrelated renderer traffic. Per-patch `taskDrained`
combines actual source busy, the inner router and that local memory drain; it
excludes a held task report. Use this predicate for a range's child-credit
barrier rather than the parent's whole-frame `busy`.

`frameMemoryDrained` is a separate, full-fabric input. A new frame cannot start
until the source, local transport and complete frame-memory ownership have
drained. A held frame report does not authorize a following frame to cross a
pending flush or VM/resource handoff.

`end` is required on every path, including invalid begin, fault and
cancellation. It certifies that the upstream range has retired and will offer
no more tuples. A task has priority over a simultaneous end. During stop,
held tuples can still be accepted and canceled or rejected locally; the frame
cannot report before their reports are consumed and end is accepted. After a
real image terminal completion, end is acknowledged locally because the
completed image cannot accept another end request.

Only actual `image.completion.valid` establishes `frameStopped`; cancellation
intent and temporary task backpressure do not. A pending image task survives
cancellation until accepted or until that actual terminal event. The final
frame report is registered after upstream end, the real image completion and
all local/full-fabric retirement barriers. Its valid and bits remain stable
through backpressure, later cancellation and a later valid-only flush.

Task reports distinguish `tupleRejected`, `rawSourceValid` and `ownerValid`.
Wrapper status 5 denotes tuple admission; it is not an Owner status. Owner
statuses 0 through 4 retain their meaning. A local rejection preserves the
original vertex task ID and does not invent an Owner or raw child completion.
The first noncancel task/image error stays primary over induced cancellation;
the complete raw image report and first noncancel task report remain available.

## Validation boundary and reproduction

The focused component test instantiates the real shared frame glue and Owner
with explicit image/source protocol peers. Its other case instantiates the
real combinational tuple checker. Neither case executes a shader or instantiates
one of the three complete image parents. Separately, the complete 79-current-
source dependency closure passed Scala 2.13.18/Chisel 7.15 plugin compilation,
including all three actual typed parents and their image, VS, uniform, patch,
FP and fragment providers. New classes preceded the read-only `75899c185`
dependency classpath; all source/classpath inputs matched before/after. See
[the bounded source-compile command and elaboration configuration](TESSELLATION_VERTEX_IMAGE_ROUTING.md#reproduce-the-bounded-checks).

The initial full-width isoline-parent construction exhausted 128 MiB heap in
the actual VS texture-binding path. Fresh current providers subsequently passed
actual CIRCT 1.158.0 lowering with 768 MiB heap: two CU interfaces, 16 lanes/eight
warps, all 16 attributes/bindings/varyings, four MRTs, a 4x4 frame, one external
engine, 65536 paged capacity and 512 KiB payload capacity/64 KiB maximum payload.
All exposed optional routing flags were enabled. The run completed in 20.04
seconds within the 600-second/1536-MiB group guard, with 1,384,624 KiB largest
sampled RSS. The real top, complete module definitions and assertion layers
were checked; input/provider guards matched before/after. See the linked
reproduction for exact configuration and flags.

Lowering found and verified the fix for an actual Owner/Publisher cancel echo
cycle. Source-origin stop immediately cancels image/new work and is latched,
while parent cancellation and actual completion faults still reach an owned
source in the same cycle. The real Owner/Publisher closed-loop RTL case, prior
Owner case and both frame-control/tuple cases passed. Reintroducing the echo
fails the exact FIRRTL cycle check. Held offers, replies, seals and reports are
retained through cancellation and drain before no-reset reuse.

Triangle/quad lowering, actual VS/TCS/TES execution and protected native
admission remain pending. External engines exclude actual CU/render-bank
instances and cannot establish sample shading, image access or native-sample/
advanced-blend behavior. New independent language/primitive-ID ABIs are not
connected through this parent. These construction/lowering and component
results do not qualify the complete VS-to-patch-to-image owner or the
640x480/60 FPS target.

Eight current source files passed Scala/Chisel-plugin compilation, followed by
both actual component RTL cases. This fresh positive run includes the Owner's
held-report fix: a later local flush keeps the task report stable while live
`taskDrained` and task admission remain blocked. The frame case covers five
frames without reset, simultaneous end/task offers, actual early terminal
receipts, stop-time range acceptance, local tuple rejection, raw-report
qualification, end after terminal, independent local/global drain and held
frame snapshots. The tuple case checks cross-link failures, uniform versions
and scope identities, empty dynamic selections, full 32x64 input capacity,
32x64+0 and 32x60+120 output shapes, and coupled overflow rejection.

Two earlier, independent RTL negative controls removed the begin drain gate
or incorrectly added global frame drain to per-patch transport. They failed
their exact idle-begin and per-patch-task assertions, respectively, without
timeouts. Those controls preceded the independent Owner held-report change;
the final fresh positive cases were rerun with that change. No claim is made
that the two mutated models were rerun afterward. All owned processes exited
and temporary classes, models and logs were removed after consumer checks.
All 155 test-selector CPU checks and integration/catalog selection checks
passed, including dependency routing to these component cases.

In an explicitly allocated resource window, compile current
`ShaderUniformResources`, `VertexInputSlice`, `VertexInputFetcher`,
`VertexShaderStage`, `TessellationVertexPatchSource`,
`TessellationVertexPatchOwner`, `TessellationVertexInputImageControl` and
`TessellationVertexInputImageSpec` with Scala 2.13.18 and its Chisel 7.15 plugin.
Put those classes before the read-only dependency classpath. Run each case
separately in an owned 180-second process group with one CPU, 128 MiB Java heap,
128 MiB metaspace, 32 MiB code cache and Serial GC:

```sh
java -Xmx128m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m \
  -XX:ActiveProcessorCount=1 -XX:+UseSerialGC \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simCache=off -Dopengpu.simWorkspace="$SIM_OUT" \
  -cp "$CURRENT_CLASSES:$FROZEN_CP" org.scalatest.tools.Runner -oD \
  -s opengpu.graphics.TessellationVertexInputImageSpec \
  -z 'retire actual child receipts'
# Repeat with -z 'check actual tuple cross links'.
```

Remove temporary classes and simulation outputs after all owned processes and
consumers have exited. Do not enable or dispatch Scala regression.
