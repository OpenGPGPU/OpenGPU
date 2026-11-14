# Original draw slices for the VS-to-patch source

The optional `drawSlices` constructor on `VertexInputFetcher` and
`VertexShaderStage` accepts an internal `VertexInputSlice` alongside an original
draw. It defaults to false. Existing draw bundles, serialized native records,
factories and discovery do not acquire new fields or permissions.

This is one prerequisite of P39. It does not launch or populate a tessellation
patch, provide protected slice admission, implement patch assembly/restart
selection or establish the original Mesa VS→TCS/TES chain. P39 remains open.
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

The existing protected PR test selector maps all three slice/fetch/stage source
files to the fetcher and focused actual-CU cases. The full vertex and texture
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
  src/test/scala/opengpu/graphics/VertexShaderStageSpec.scala

# Pending, once the simulation resource window permits:
sbt Test/compile
sbt 'testOnly opengpu.graphics.VertexInputFetcherSpec opengpu.graphics.VertexShaderSliceSpec'
sbt 'testOnly opengpu.graphics.VertexShaderStageSpec'
```

Any enabled final factory needs fresh source/model qualification. This change
does not establish complete-host timing, memory use, throughput or VGA60.
