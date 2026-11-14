# GVS1 original VS source reader

`TessellationVertexSourceReader` captures the eight cache lines of the immutable
GVS1 source root over the existing tag-zero record interface. It checks the
exact header, all reserved/high bits, 64 packed linked-component maps, coupled
patch capacity and full-width slice/count/task-ID endpoints. Context, pass,
generation, sequence/draw index, task-ID base, patch count and layout must match
the expected values captured from the accepted GNT7/8/9 sequence.

The logical root view is exactly 512 bytes; its allocation envelope may be
larger. Its references preserve a 2048-byte original GND view, the original
bounded RX code view and its nonzero entry offset. Root/GND/RX endpoints must
fit the enclosing accepted owner's private window. That window comes from the
native controller, not the root being parsed. A 33-bit end at 2^32 retains its
carry. Original draw counts, VS resources, varying-mask provenance and the
code entry must still be checked against the actual original GND before launch.

An offered memory read survives cancellation and retains its response credit.
The reader waits for the actual reply before reporting cancellation; a memory
fault remains a fault even if cancellation also occurred. Error completions
expose no decoded root. Completion backpressure retains the captured result.
No native route, discovery bit, getter or launch permission is enabled here.

## Bounded results

The six current compilation units below passed incremental Scala 2.13.18 /
Chisel 7.15 plugin compilation against the unchanged frozen dependency classes
from `75899c1856ee0ee0204cbd2e5d1893d6dad11f63`. An actual Chisel layout
elaboration independently matched all 151 hardware fields, 11 header constants
and 128 word masks to the committed GVS1 schema used by the C generator in
P16 source `f893eed7b`. No layout fields changed in the subsequent exact-view
correction.

Three final RTL cases passed in 35.2 seconds. They check all 64 component maps,
eight actual reads, a 33-bit end at 2^32, malformed headers/padding/maps, stale
scope, coupled/range/task-ID overflow, exact logical view versus allocation
envelope, empty ranges, held offers, late canceled replies, fault priority and
healthy reuse without reset. The records and expected words are independent
test fixtures. These tests do not execute the C encoder or a shader CU.
Independent review found the initial oversized-logical-view acceptance; the
final implementation rejects it before any read.

Compilation and simulation used a 128 MiB JVM heap, one CPU/native build job
and an owned 180-second process-group limit. The 147 selector CPU tests and
integration/catalog checks passed. Temporary compiler classes, layout helper,
simulations and logs were removed after their processes exited. This remains
an incremental check, not a complete current-source build or full factory
qualification.

## Reproduction and remaining connection

Use JDK 21 and the existing frozen dependency classpath, with the matching
Scala compiler and Chisel plugin. This compiles only the reader dependency
closure into private output; it does not rebuild or modify the frozen tree.

```sh
FROZEN_CP=$(cat "$FROZEN/target/streams/test/fullClasspath/_global/streams/export")
CHECK_OUT=$(mktemp -d .artifacts/gvs-reader-XXXXXX)
SIM_OUT=$(mktemp -d .artifacts/gvs-reader-rtl-XXXXXX)
scalac -J-Xmx128m -J-XX:MaxMetaspaceSize=128m \
  -J-XX:ReservedCodeCacheSize=32m -J-XX:ActiveProcessorCount=1 \
  -language:reflectiveCalls -Ymacro-annotations -Xplugin:"$CHISEL_PLUGIN_JAR" \
  -classpath "$FROZEN_CP" -d "$CHECK_OUT" \
  src/main/scala/opengpu/graphics/VertexInputSlice.scala \
  src/main/scala/opengpu/graphics/VertexInputFetcher.scala \
  src/main/scala/opengpu/graphics/VertexShaderStage.scala \
  src/main/scala/opengpu/system/TessellationVertexPatchSource.scala \
  src/main/scala/opengpu/system/TessellationVertexSourceReader.scala \
  src/test/scala/opengpu/graphics/TessellationVertexSourceReaderSpec.scala
java -Xmx128m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m \
  -XX:ActiveProcessorCount=1 -XX:+UseSerialGC \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simCache=off -Dopengpu.simWorkspace="$SIM_OUT" \
  -cp "$CHECK_OUT:$FROZEN_CP" org.scalatest.tools.Runner -oD \
  -s opengpu.graphics.TessellationVertexSourceReaderSpec
python3 -B scripts/test_changed_test_suites.py
python3 -B scripts/test_test_selection.py
# Remove only after the compiler, JVM and native consumers have exited:
rm -r -- "$CHECK_OUT" "$SIM_OUT"
```

The outer protected BUILD, production GNT7/8/9 reader wiring, actual original
GND read/match and memory routing remain pending. They must connect this root
to the GPU patch/instance range sequencer and real VS source, then retain all
resources through actual TCS/TES execution and whole-patch retirement. Root
decoding, CPU owner tests and publisher peers do not close that joint gate.
P39/P40, original ESSL execution in all three domains, indirect/texture paths,
the full-profile DUT and product qualification remain open. Keep Scala
regression disabled.
