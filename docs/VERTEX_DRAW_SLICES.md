# Original draw slices for the VS-to-patch source

The optional `drawSlices` constructor on `VertexInputFetcher` and
`VertexShaderStage` accepts an internal `VertexInputSlice` alongside an original
draw. It defaults to false. Existing draw bundles, serialized native records,
factories and discovery do not acquire new fields or permissions.

This is one prerequisite of P39. The optional internal
`TessellationVertexPatchSource` connects this sliced VS execution to an
acknowledged patch-input publisher. It does not provide protected slice
admission, implement patch assembly/restart selection or establish the original
Mesa VS→TCS/TES chain. P39 remains open.
The [complete execution gaps](TESSELLATION_EXECUTION_GAPS.md) still apply.
The twelve selected Scala files passed incremental compilation with Scala
2.13.18 and the Chisel 7.15.0 plugin against read-only dependency/test classes
from frozen source `75899c1856ee0ee0204cbd2e5d1893d6dad11f63`. The publisher
also passed CHIRRTL elaboration at 16 and one varying slots. These checks used
a 128MiB heap, 128MiB metaspace, 32MiB code cache and one processor. They found
and fixed an IO/constructor name-shadowing error and checked dynamic indices
before narrowing them. They are not a complete build of the current source.
The source wrapper's bounded elaboration attempt reached the heap limit;
wrapper elaboration, VS-to-patch RTL simulation and actual CU execution remain
pending. The four patch-store/context RTL cases below passed independently.

## Captured indices and instanced attributes

The internal version-1 slice contains `firstOrdinal/count` and
`firstInstance/instances`. Both descriptor and slice are captured at the
accepted draw; the shader stage independently retains its slice until the
fetcher's later descriptor acceptance. Mutating an unaccepted subsequent
descriptor or slice cannot change the current job.

The range endpoints use widened arithmetic and must fit the original draw's
count and instance count. Full original descriptor/view validation remains in
force. Empty subranges produce no input traffic. Index reads still use
`originalFirstIndex + originalOrdinal`; array vertex IDs still use
`originalFirstVertex + originalOrdinal`. Base-vertex handling and restart
tokens retain the original fetcher's rules. Exported ordinal and instance ID
remain relative to the original draw, and `baseInstance` remains independent.

For each instanced binding the initial element is
`originalBaseInstance + floor(firstInstance / divisor)`. The initial phase is
`firstInstance % divisor`, so later instances cross the same divisor boundaries
as the original draw. Arbitrary nonzero starts use 32 bounded unsigned restoring
steps across the binding slots, rather than walking earlier instances or
introducing a combinational divider. A zero start uses the existing initial
state directly. Divisor-zero bindings keep ordinary vertex indexing.

Element addition retains its 33rd bit for the existing checked-address path.
Cancellation during setup enters ordinary drain without issuing a read;
offered requests, output records and accepted responses retain the existing
drain/held-completion contract before reuse.

## Acknowledged patch publication

`TessellationVertexPatchPublisher` accepts an internal version-1 task containing
the original draw, one instance slice, an exact patch layout and 64 linked
component mappings. Each mapping selects a position word, an enabled generic
varying word or the version-2 point-size output. Inactive mappings must be zero.
The checked layout retains the 32-vertex, 64-component, 120-patch-component and
coupled 2048-word limits. One slice must contain exactly the patch's input
vertices; unexpected restart tokens, ordinals, identities or instance IDs fail
the patch instead of silently changing its source.

The parent must already own and open the exact patch, serialize its source-zero
load/reply interface and retain the patch and all acceptors through cancellation
and completion. The publisher writes input components only. It waits for each
successful write acknowledgement before advancing, then waits for the actual
VS completion and an idle child before offering seal. A held draw, write or seal
remains stable until accepted, even during cancellation. Accepted patch replies
and VS output/completion drain before source reuse. Patch failure, VS failure
and cancellation preserve the first primary error. A real VS failure or a
malformed reply can promote cancellation to failure; the patch context's
ordinary nonzero abort acknowledgement preserves cancellation or an already
latched source failure. The parent must cancel a failed patch and must not launch
a later stage because a previously held seal was accepted during cancellation.

`TessellationVertexPatchSource` instantiates the actual shared-CU
`VertexShaderStage` and this publisher, with its existing memory/payload ports.
It does not own the original GEM admission, patch context, global arbitration
or whole-owner retirement. It is not connected to any native domain wrapper,
factory or discovery record by this change. No usable public capability is
enabled.

## Owned patch initialization

`TessellationPatchStore` and `TessellationPatchContext` accept an optional
`zeroOnBegin` mode. Their no-argument constructors retain the legacy mode.
In the enabled mode, an accepted legal layout captures its input/output word
counts, initializes every address reachable under that layout and clears all
six tessellation levels. Initialization lasts the larger of the input and
packed output word counts, at most 2048 cycles. Zero-component layouts still
initialize the levels. The begin result, accesses and patch release remain
blocked until initialization finishes, and the result itself retains normal
backpressure ownership. A layout change cannot expose old words: newly
reachable words initialize again, including when a later layout grows.

Initialization shares each bank's existing write port. CHIRRTL inspection
confirmed two 2048×32 memories and one write port per bank in both modes; it
does not prove SRAM mapping or timing. The source test harness requests the
enabled mode. Native domain wrappers and a future verified profile still need
explicit initialization wiring and complete execution/retirement qualification;
the option's existence does not authorize a public capability.

Four focused RTL cases passed under Verilator with one native build job and the
bounded JVM settings above: the two legacy store/context cases and the new
`TessellationOwnedPatchStoreSpec` / `TessellationOwnedPatchContextSpec`. They
cover all 2048 old input/output words, a 32×60+120 packed output layout, shrinking
and growing layouts, all six levels, invalid/empty-component layouts, held
opening/replies, cancellation during initialization, actual context abort and
healthy reuse without reset. The strengthened owned cases independently read
back all 4096 nonzero input/output words and all six levels before ownership
changes. They also cover output-dominated and per-patch-only clearing, nonzero
per-patch readback, and a single early cancellation pulse withdrawn before
initialization finishes. Both strengthened RTL cases passed. Four separately
compiled semantic mutations were rejected by the intended behavioral checks:
dropped ordinary input writes, dropped ordinary output writes, clearing only
the shorter input range, and forgetting cancellation during opening. Each
negative run completed its suite with an assertion failure, without timeout or
infrastructure failure. These are SRAM/context component results, without
VS/TCS/TES CU execution or protected whole-owner retirement.

## Pending execution and reproduction

The new fetcher cases contain an independent arithmetic/byte oracle for
indexed/nonindexed subranges, restart records, original ordinals, independent
divisors 1/2/3/5/31/255/`0xffffffff`, high unsigned instance IDs, endpoint carry,
malformed versions, empty work, source mutation, cancellation and no-reset
reuse. The focused `VertexShaderSliceSpec` contains two actual shared-CU cases
with 16 lanes, eight warps and two CUs: local payload and global staging. Shader
exports depend on original vertex/instance IDs and instanced attribute values;
held exports and canceled requests precede healthy reuse. These are test
sources, not recorded passes, and use a native instruction fixture rather than
original ESSL/Mesa. The full P39 pipeline needs its separate original-source,
three-domain, patch ACK/seal and whole-owner execution evidence.

`TessellationVertexPatchPublisherSpec` adds protocol-peer cases for all 32×64
input words, write acknowledgements, actual source-completion gating, empty
component layouts, held offers, cancellation, late replies, first-error
preservation, invalid mappings and no-reset reuse. All three publisher RTL
cases passed under the same bounded JVM / single native build job, with a
180-second process-group limit. Its peer is not a shader, so even the full
32×64 result does not qualify original VS execution.
`TessellationVertexPatchSourceSpec` instead connects the actual source to two
physical CUs (16 lanes/eight warps), global staging and an actual patch SRAM.
Its native instruction fixture copies fetched attributes and original IDs;
the test independently reads every patch word after completion, holds replies
and retains the patch until explicit release before healthy reuse. These new
actual-source Scala cases compiled incrementally but remain unexecuted.
The full 32×64 case is a publisher peer; the actual-CU test source uses one
varying slot and patches of two or three vertices. Its seal acceptor is the
harness, not `TessellationPatchContext`. Actual context seal/cancel/drain and
full-capacity VS-to-patch execution remain separate pending validation.

The existing protected PR test selector maps all three slice/fetch/stage source
files to the fetcher and focused actual-CU cases. Publisher source edits select
the protocol-peer and actual-source cases. The full vertex and texture
matrix qualifiers retain their existing local scope. CPU suite-selection checks
passed; they prove routing of tests only. Keep `Scala regression` disabled.

```sh
python3 -B scripts/test_changed_test_suites.py
python3 -B scripts/test_test_selection.py
git diff --check

# Bounded incremental compile only. FROZEN is the unchanged dependency checkout
# above, with its existing compiled classes and fullClasspath export.
FROZEN_CP=$(cat "$FROZEN/target/streams/test/fullClasspath/_global/streams/export")
CHECK_OUT=$(mktemp -d .artifacts/p39-compile-XXXXXX)
scalac -J-Xmx128m -J-XX:MaxMetaspaceSize=128m \
  -J-XX:ReservedCodeCacheSize=32m -J-XX:ActiveProcessorCount=1 \
  -language:reflectiveCalls -Ymacro-annotations -Xplugin:"$CHISEL_PLUGIN_JAR" \
  -classpath "$FROZEN_CP" -d "$CHECK_OUT" \
  src/main/scala/opengpu/graphics/VertexInputSlice.scala \
  src/main/scala/opengpu/graphics/VertexInputFetcher.scala \
  src/main/scala/opengpu/graphics/VertexShaderStage.scala \
  src/main/scala/opengpu/graphics/tessellation/TessellationPatchStore.scala \
  src/main/scala/opengpu/graphics/tessellation/TessellationPatchContext.scala \
  src/test/scala/opengpu/graphics/VertexInputFetcherSpec.scala \
  src/test/scala/opengpu/graphics/VertexShaderStageSpec.scala \
  src/main/scala/opengpu/system/TessellationVertexPatchSource.scala \
  src/test/scala/opengpu/graphics/TessellationVertexPatchPublisherSpec.scala \
  src/test/scala/opengpu/graphics/TessellationVertexPatchSourceSpec.scala \
  src/test/scala/opengpu/graphics/tessellation/TessellationPatchStoreSpec.scala \
  src/test/scala/opengpu/graphics/tessellation/TessellationPatchContextSpec.scala
# Remove CHECK_OUT after the compiler and any elaboration consumer have exited.

# Publisher-only CHIRRTL elaboration, using CHECK_OUT before FROZEN_CP:
# circt.stage.ChiselStage.emitCHIRRTL(new opengpu.system.TessellationVertexPatchPublisher(
#   opengpu.graphics.VertexShaderStageConfig()))
# Repeat with VertexShaderStageConfig(VertexInputFetchConfig(1,1),1).

# Executed component and publisher-peer RTL cases, using those compiled classes:
SIM_OUT=$(mktemp -d .artifacts/p39-patch-rtl-XXXXXX)
java -Xmx128m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m \
  -XX:ActiveProcessorCount=1 -XX:+UseSerialGC \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simCache=off -Dopengpu.simWorkspace="$SIM_OUT" \
  -cp "$CHECK_OUT:$FROZEN_CP" org.scalatest.tools.Runner -oD \
  -s opengpu.graphics.tessellation.TessellationOwnedPatchStoreSpec \
  -s opengpu.graphics.tessellation.TessellationOwnedPatchContextSpec \
  -s opengpu.graphics.tessellation.TessellationPatchStoreSpec \
  -s opengpu.graphics.tessellation.TessellationPatchContextSpec \
  -s opengpu.graphics.TessellationVertexPatchPublisherSpec
# After the JVM and native consumers have exited:
rm -r -- "$CHECK_OUT" "$SIM_OUT"

# Pending, once the simulation resource window permits:
sbt Test/compile
sbt 'testOnly opengpu.graphics.VertexInputFetcherSpec opengpu.graphics.VertexShaderSliceSpec'
sbt 'testOnly opengpu.graphics.TessellationVertexPatchPublisherSpec opengpu.graphics.TessellationVertexPatchSourceSpec'
sbt 'testOnly opengpu.graphics.VertexShaderStageSpec'
```

Any enabled final factory needs fresh source/model qualification. This change
does not establish complete-host timing, memory use, throughput or VGA60.
