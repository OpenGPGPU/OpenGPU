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
No Scala type check, elaboration, RTL simulation or actual CU execution has been
performed for this source revision. The Scala 2.13.18 syntax parser passed with
a 128 MiB heap and one processor; it does not resolve types or Chisel macros.

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
and cancellation preserve the first primary error; cancellation can be promoted
to an actual failure. The parent must cancel a failed patch and must not launch
a later stage because a previously held seal was accepted during cancellation.

`TessellationVertexPatchSource` instantiates the actual shared-CU
`VertexShaderStage` and this publisher, with its existing memory/payload ports.
It does not own the original GEM admission, patch context, global arbitration
or whole-owner retirement. It is not connected to any native domain wrapper,
factory or discovery record by this change. No usable public capability is
enabled.

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
preservation, invalid mappings and no-reset reuse. Its peer is not a shader.
`TessellationVertexPatchSourceSpec` instead connects the actual source to two
physical CUs (16 lanes/eight warps), global staging and an actual patch SRAM.
Its native instruction fixture copies fetched attributes and original IDs;
the test independently reads every patch word after completion, holds replies
and retains the patch until explicit release before healthy reuse. These new
Scala cases have passed syntax parsing only and remain unexecuted.

The existing protected PR test selector maps all three slice/fetch/stage source
files to the fetcher and focused actual-CU cases. Publisher source edits select
the protocol-peer and actual-source cases. The full vertex and texture
matrix qualifiers retain their existing local scope. CPU suite-selection checks
passed; they prove routing of tests only. Keep `Scala regression` disabled.

```sh
python3 -B scripts/test_changed_test_suites.py
python3 -B scripts/test_test_selection.py
git diff --check

# Scala 2.13.18 syntax only:
scalac -J-Xmx128m -J-XX:ActiveProcessorCount=1 -Ystop-after:parser \
  src/main/scala/opengpu/graphics/VertexInputSlice.scala \
  src/main/scala/opengpu/graphics/VertexInputFetcher.scala \
  src/main/scala/opengpu/graphics/VertexShaderStage.scala \
  src/test/scala/opengpu/graphics/VertexInputFetcherSpec.scala \
  src/test/scala/opengpu/graphics/VertexShaderStageSpec.scala \
  src/main/scala/opengpu/system/TessellationVertexPatchSource.scala \
  src/test/scala/opengpu/graphics/TessellationVertexPatchPublisherSpec.scala \
  src/test/scala/opengpu/graphics/TessellationVertexPatchSourceSpec.scala

# Pending, once the simulation resource window permits:
sbt Test/compile
sbt 'testOnly opengpu.graphics.VertexInputFetcherSpec opengpu.graphics.VertexShaderSliceSpec'
sbt 'testOnly opengpu.graphics.TessellationVertexPatchPublisherSpec opengpu.graphics.TessellationVertexPatchSourceSpec'
sbt 'testOnly opengpu.graphics.VertexShaderStageSpec'
```

Any enabled final factory needs fresh source/model qualification. This change
does not establish complete-host timing, memory use, throughput or VGA60.
