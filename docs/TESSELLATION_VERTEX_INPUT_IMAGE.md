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

## Staged validation and limits

All 777 production and 1120 test Scala sources freshly compiled with
Scala 2.13.18/Chisel 7.15.0 in 91.649/177.016 seconds, without an old GPU
class provider. Compilation used separate 600-second/6144-MiB sampled group
bounds; observed maxima were 3561168/4299232 KiB. The P40 launch-contract
fix and existing CI registrations remain intact.

All fourteen distinct protocol cases passed: ten router cases, two Owner/
Publisher cases and two frame/tuple cases. They cover remap/provider/payload
isolation, denied VS capabilities, held offers and acknowledgements, late
faults/cancellation, raw child receipts, upstream end, independent local/frame
drain, held reports after flush and healthy no-reset reuse. Image/source/CU
execution interfaces are explicit peers; no original shader executed.

The initial cache-off batch completed five cases, then reached its 600-second
limit during another native build. Its cleanup wrapper raised EPERM; current
PID/group, source-prefixed process and open-file checks independently confirmed
consumer exit before continuation. The nine remaining original cases each
passed under a separate 240-second/1536-MiB group bound, with a private
executable cache enabled and object caching disabled. The initial batch is
not counted as a whole-batch PASS. Assertions and the original cases/resource
capacities were preserved. Later selected runs and parent emissions passed
source/class/tool/HEAD/index guards. The initial partial batch's sampled
maximum was 1047296 KiB; the remaining cases stayed below 805440 KiB.

Two independently compiled source-copy controls fail the intended oracle:
restoring cancel echo creates the actual Owner/Publisher combinational cycle;
requiring live transport drain in an already-held report triggers
`vertex patch owner report escaped child drain`. They finish in 1.242/33.183
seconds with sampled group maxima 200848/442208 KiB, within separate
240-second/1536-MiB bounds. Both compile successfully and fail one selected
case; neither failure is a process timeout. Production source stays unchanged.

The explicit `QualifyTessellationVertexParents` entry then passes actual CIRCT
1.158.0 lowering for all three complete typed parents:

| Domain | Seconds | Maximum sampled group KiB | SystemVerilog files |
| --- | ---: | ---: | ---: |
| Isoline | 18.574 | 1414464 | 469 |
| Triangle | 18.859 | 1412736 | 480 |
| Quad | 18.381 | 1442144 | 480 |

Each uses a separate 600-second/1536-MiB group bound, 768-MiB JVM heap,
256-MiB metaspace, one processor and SerialGC. The configuration uses
`GraphicsTestResolution` at 640x480, two CU interfaces, 16 lanes/eight warps,
16 attributes/bindings/varyings, four MRTs, one external image engine,
65536 paged capacity, 4096-byte pages, eight write credits/tile slots and a
512-KiB payload interface (five owners/twelve ports/max69632 bytes). The
32-KiB typed storage exposes eight streaming word ports and native sample/
advanced-blend policy. Payload, TF, fragment-image, sample shading, checked
uniform and dynamic-TES interfaces are enabled. Assertion layers remain in
the emitted hierarchy. There are 34 existing elaboration warnings per domain;
sampled RSS is not an absolute peak.

`externalImages=true` and the external CU pool exclude actual CU/image-bank
instances. This is structural lowering, without a rendered frame, shader,
full factory, native/protected submission, mapped timing or FPS result. The
language-ID service, original GNT/GVS/GND capture/range connection and actual
three-domain original-VS→TCS/TES execution remain later stages. P39/P40 and
the product target remain open. All 160 affected-suite selector checks and the
integration/large-PR catalog checks pass. Ended models, classes, caches,
mutants and logs are removed after consumer checks.

## Reproduction

With the repository JDK21/Scala/Chisel/Verilator toolchain, run the complete
suites with an appropriate finite build window. A single original case can
also be selected with ScalaTest `-- -z "unique source case text"`.

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=1 compile 'Test / compile'
sbt -J-Xmx768m -J-XX:ActiveProcessorCount=1 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.productQualification=0 \
  'testOnly opengpu.graphics.TessellationVertexShaderRouterSpec opengpu.graphics.TessellationVertexPatchOwnerSpec opengpu.graphics.TessellationVertexInputImageSpec'
sbt 'export Test / fullClasspath'
PARENT_CP=$(cat target/streams/test/fullClasspath/_global/streams/export)
mkdir -p .artifacts
PARENT_OUT=$(mktemp -d "$PWD/.artifacts/owned-parent.XXXXXX")
for domain in isoline triangle quad; do
  java -Xmx768m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m \
    -XX:ActiveProcessorCount=1 -XX:+UseSerialGC \
    -Dopengpu.productQualification=0 -cp "$PARENT_CP" \
    opengpu.system.QualifyTessellationVertexParents "$domain" "$PARENT_OUT/$domain" || exit $?
done
python3 -B scripts/test_changed_test_suites.py
python3 -B scripts/test_test_selection.py
git diff --check
# After all consumers have exited, remove only this owned emission output:
rm -r -- "$PARENT_OUT"
```

The emitter requires a fresh output under `.artifacts/`; it does not replace
existing files or run a shader. Do not enable or dispatch Scala regression.
