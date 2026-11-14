# Original GND1 vertex draw reader

`TessellationVertexDrawReader` captures all 32 cache lines of the original
2048-byte GND1 and returns only its `VertexShaderDraw`. The enclosing controller
supplies a captured [GVS1 root](TESSELLATION_VERTEX_SOURCE_READER.md), the exact
512-byte source view and the accepted owner's private bounds. The reader uses
`NativeBinnedGraphicsLayout.drawFields` and
`NativeTypedGraphicsLayout.check/receive`; it has no second hand-written field
map, GND2 alternate, whole-frame reader or FS preflight.

The original count, first index/vertex, signed base vertex, base instance,
instance count, all 16 attributes/bindings, per-binding divisor/stride and
indexed view remain unchanged. The GPU range sequencer supplies original
draw-relative slices separately. Full-width slice/count products and instance
endpoints must fit those original counts. Identity, VS ABI and the exact code
PC must match GVS; its GND/RX references remain inside the accepted private
scope. This does not read instructions or establish RX permissions.

All reserved/high bits and narrow signed encodings use the original canonical
GND decoder. Header capacities remain 16 attributes, 16 bindings, 16 varyings,
four colors and four engine views. The reader requires 16 lanes/eight warps
and validates the complete physical 128-item VS1/2 scratch prefix, even for
zero patch work. Scratch cannot overlap the GVS view, GND, RX view or enabled
GUR1 root. Used input/index alias and descriptor checks use the existing VS
and input validators. The enclosing CPU BUILD still owns full physical
64-byte alias exclusion, unrelated frame/TCS/TES roles and source immutability.

All 64 linked maps are checked against the actual varying slots/mask; point
size requires VS ABI2 and inactive maps must be zero. VS uniforms remain
GUR1 with a private root. Legacy sampler fields, texture resources, output
reuse, indirect metadata and restart must be zero in this source mode.
Indirect/texture production paths remain complete targets, not completed
features. The preparer separately implements the selected query-false PATCHES
restart policy; this reader does not rewrite any input metadata.

One tag-zero read is outstanding at a time. Offered requests and accepted
responses survive cancellation; a real late memory fault takes precedence.
Error results expose a zero draw. Captured success/error completions remain
stable under backpressure, and the reader can be reused without reset. The
native owner must retain all records/resources through complete execution and
translation retirement.

## Focused results

Ten current source compilation units passed direct Scala 2.13.18 / Chisel
7.15 plugin compilation against unchanged frozen dependency classes from
`75899c1856ee0ee0204cbd2e5d1893d6dad11f63`. An independent Chisel layout
elaboration matched all 320 production GND fields' names, words, widths and
signedness against `abi/native-binned-graphics-v1.json`.

Three actual RTL cases passed in 62 seconds. Literal little-endian fixtures
check 32 reads, full original indexed/instanced/array inputs, both VS ABIs,
point size and 33-bit GND/RX ends at 2^32. Rejections cover malformed
encodings, scope/PC/ABI/count/mask failures, each legacy sampler field, short
scratch capacity, each known immutable metadata role, invalid request extents
before reads, and missing scratch with zero original work. Held cancellation,
the last-line late fault, zero error payloads and healthy reuse also pass.

Two isolated source-copy RTL negative controls fail the status assertion:
calculating the prefix for 64 items instead of 128, and removing the immutable
metadata alias guard. Production source was unchanged. The 150 selector CPU
tests passed. Compiler/Runner process groups each had a 180-second ceiling,
128 MiB heap/128 MiB metaspace/32 MiB code cache, one CPU and one native job.
All owned processes exited; temporary classes, probes, mutants and RTL outputs
were removed. These are incremental component checks, not a complete
current-source build, original shader execution or full-profile qualification.

## Reproduction and remaining connection

With JDK21 and matching existing read-only dependency/compiler/plugin paths,
compile the ten-unit closure below into private output. Run each compiler and
Runner in an owned process group with the same 180-second ceiling.

```sh
FROZEN_CP=$(cat "$FROZEN/target/streams/test/fullClasspath/_global/streams/export")
CHECK_OUT=$(mktemp -d .artifacts/gnd-reader-XXXXXX)
SIM_OUT=$(mktemp -d .artifacts/gnd-reader-rtl-XXXXXX)
java -Xmx128m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m \
  -XX:ActiveProcessorCount=1 -XX:+UseSerialGC -Dscala.usejavacp=true \
  -cp "$SCALA_COMPILER_CP" scala.tools.nsc.Main \
  -language:reflectiveCalls -Ymacro-annotations -Xplugin:"$CHISEL_PLUGIN_JAR" \
  -classpath "$FROZEN_CP" -d "$CHECK_OUT" \
  src/main/scala/opengpu/graphics/VertexInputSlice.scala \
  src/main/scala/opengpu/graphics/VertexInputFetcher.scala \
  src/main/scala/opengpu/graphics/VertexShaderStage.scala \
  src/main/scala/opengpu/graphics/NativeTypedGraphicsDescriptors.scala \
  src/main/scala/opengpu/graphics/NativeBinnedGraphicsDescriptors.scala \
  src/main/scala/opengpu/graphics/ShaderUniformResources.scala \
  src/main/scala/opengpu/system/TessellationVertexPatchSource.scala \
  src/main/scala/opengpu/system/TessellationVertexSourceReader.scala \
  src/main/scala/opengpu/system/TessellationVertexDrawReader.scala \
  src/test/scala/opengpu/graphics/TessellationVertexDrawReaderSpec.scala
java -Xmx128m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m \
  -XX:ActiveProcessorCount=1 -XX:+UseSerialGC \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simCache=off -Dopengpu.simWorkspace="$SIM_OUT" \
  -cp "$CHECK_OUT:$FROZEN_CP" org.scalatest.tools.Runner -oD \
  -s opengpu.graphics.TessellationVertexDrawReaderSpec
python3 -B scripts/test_changed_test_suites.py
# Only after compiler, JVM and native consumers have exited:
rm -r -- "$CHECK_OUT" "$SIM_OUT"
```

The outer protected BUILD, production GNT7/8/9/GVS/GND reader-to-parent wiring,
original VS execution, range sequencing, acknowledged patch stores/seal,
existing-memory-client routing and complete TCS/TES retirement remain pending
as an integrated path. No discovery bit, available getter, ioctl or launch
route is enabled by this reader. All three domains, the full two-CU product
profile and VGA/timing/physical qualifications remain unqualified. Keep Scala
regression disabled; no broad workflow was enabled or dispatched.
