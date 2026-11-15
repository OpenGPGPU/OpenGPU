# Protected joint job and directory capture

`TessellationJointJobCaptureReader` reads the exact 512-byte GNQ4/5/6 version-2
record. `TessellationJointDirectoryCaptureReader` reads its attached GVM2 header
and streams the original invocation/range entries. Both constructors default to
`enabled=false`, returning unavailable without a memory request. They introduce
no public capability, factory, discovery, getter, queue or ioctl route. GNQ1/2/3
and their existing readers are unchanged.

The external layout is
[`abi/protected-tessellation-joint-v2.json`](abi/protected-tessellation-joint-v2.json).
Bundle packing is not the memory ABI. New job magics pair exclusively with
GNT7/8/9. The request independently supplies the expected frame identity and
VA32 owner base/LIMIT33 exclusive end. The exact logical job view and its entire
allocation envelope must fit that owner scope. Job, frame, sequence array and
directory views are exact and numerically disjoint. All extent products and
sums are widened to at least 65 bits before checking and narrowing; an exclusive
end of 2^32 is valid, an address at 2^32 is not.

These numeric checks do not prove ownership of holes inside an envelope. The
future caller must retain the immutable authorized BUILD references, actual
MMU mappings and original GEM leases through complete retirement. Directory
capture cannot replace physical alias validation or grant code/resource access.

## Directory and source identities

The GVM2 header must match GNQ identity, counts and extents exactly. Entries
preserve the original draw index, arbitrary 64-bit invocation ID (including
zero), range ordinal, instance/patch slice and task range as separate fields.
FIRST/CONTINUATION/LAST, sequence indices, same-invocation draw/ID and ordinal
continuity are checked. Capturing another metadata entry is not permission to
advance an executing range. Global invocation-ID uniqueness remains a check on
the complete immutable BUILD table; the streaming reader does not claim to
deduplicate an unbounded set or impose a new ID ordering.

The directory supplies the expected draw index to a future GVS request. A GVS
root cannot authorize its own draw index. `TessellationJointSourceCrosslink`
checks observations supplied by the actual GNT/GVS/GND captures: paired dialect,
GNT509..511 and directory pointing to the same exact 512-byte GVS view, original
identity/layout/task/count, and the independent GND view. It derives the vertex
ordinal from `firstInputPatch * actualInputVertices` without wrapping. Original
GND vertex/instance counts and the previously validated cursor establish row
extent, range coverage and the exact LAST boundary. TES exports are not used
as the TCS patch layout.

The directory can reject locally nonadjacent slices without reading GND. A
possible row transition still needs the crosslink checker and real GND extent;
directory metadata alone cannot prove that a previous row ended at its actual
boundary. Likewise the checker's literal observations in component fixtures do
not establish actual GVS/GND capture, parent integration or shader execution.

## Cancellation and retirement

Each reader owns one tag-zero read at a time. A held memory offer survives
cancellation, must actually fire, and must consume its real reply before
completion. A late memory fault takes precedence over cancellation. A held
directory entry also survives cancellation unchanged; the caller must accept
and discard it under `stop`. Completion is an immutable snapshot after it is
offered. New admission requires its acceptance and the absence of owned offers
or memory credit. No reader uses the absence of a valid signal for one cycle
as evidence that an outer producer has retired.

The future parent must wait for actual task completion and task-specific drain
before launching another range, and for end acceptance, actual image completion
and the complete memory fabric before retiring the frame/owner. Those signals
are not manufactured by these metadata readers.

## Empty jobs and validation boundary

With no invocation, GNQ has zero sequence and directory fields and returns
`clearOnly` metadata without reading a directory. Its real frame view and
expected identity remain. A real invocation with zero work instead retains a
FIRST|LAST canonical zero-slice directory entry and its original source graph.
The future frame capture must verify the no-state GNF form; actual no-shader
clear/store/resolve execution still requires an explicit parent/render route.
No dummy FS or CPU-generated patch is supplied here.

The new specs use full 16-lane/8-warp configuration and the exact 16 attribute,
16 binding, 16 varying, four-MRT/two-CU metadata contract. They contain literal
little-endian responses, malformed/high-bit/cross-domain/view/overflow cases,
held memory/metadata offers, delayed replies, cancel/fault priority, immutable
completion and reset-free reuse.

The four current production sources, five test sources and current
`GpuConfig.scala` passed a fresh plugin compile. Four explicitly selected suites
passed: one ABI test and five actual component RTL tests (job reader two,
directory reader two, crosslink one). On the exact delivery based on merged
#827, compile took 5.87 seconds;
the ABI suite took 0.47 seconds and component suites took 20.69, 18.27 and
9.25 seconds. All six tests passed without skips. Maximum sampled aggregate
owned-group RSS was 418624 KiB. Each group was limited to 180 seconds and
512 MiB sampled
aggregate RSS; JVM heap/metaspace/code limits were 128/128/32 MiB with SerialGC
and `ActiveProcessorCount=1`. Make and `--build-jobs` were one; actual Verilator
arguments retained upstream `-j 0`, so this is not a claim of a strict one-CPU
limit throughout native generation.

Source, class membership/content, jars, tools and HEAD were unchanged across
the run (7237 guarded inputs). The actual direct provider classfiles'
`SourceFile` attributes were checked. Frozen memory-port, identity, cache-policy
and simulation helper sources matched the current sources byte for byte.
`GpuConfig` differed and was rebuilt from the current source into the private
classes preceding the frozen classpath. All owned groups were proved terminal
before their temporary outputs were removed. No actual CU, full parent,
Mesa-to-DUT, six VGA frames or physical timing result is established here.

For a bounded reproduction, obtain the frozen host test classpath from
`alignment-host-integration/target/streams/test/fullClasspath/_global/streams/export`,
check the borrowed provider sources and tools before and after, and compile
these sources into a fresh private directory (do not write frozen classes):

```
java -Xmx128m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m \
  -XX:ActiveProcessorCount=1 -XX:+UseSerialGC -Dscala.usejavacp=true \
  -cp "$SCALA_21318_COMPILER_LIBRARY_REFLECT" scala.tools.nsc.Main \
  -classpath "$FROZEN_CP" -Xplugin:"$CHISEL_7150_PLUGIN" \
  -language:reflectiveCalls -Ymacro-annotations -d "$PRIVATE_CLASSES" \
  src/main/scala/opengpu/config/GpuConfig.scala \
  src/main/scala/opengpu/system/TessellationJoint*.scala \
  src/test/scala/opengpu/system/TessellationJoint*.scala
```

Use JDK21, Scala 2.13.18, Chisel/plugin 7.15.0, firtool 1.158.0 and the existing
Verilator toolchain. Run each named suite in a separate bounded owned group,
with `PRIVATE_CLASSES:FROZEN_CP` in that order and these JVM options in addition
to the memory/CPU options above:

```
-Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simCache=off -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simWorkspace="$PRIVATE_WORKSPACE" \
  -cp "$PRIVATE_CLASSES:$FROZEN_CP" \
org.scalatest.tools.Runner -oD \
  -s opengpu.system.TessellationJointJobAbiSpec \
  -s opengpu.system.TessellationJointJobCaptureReaderSpec \
  -s opengpu.system.TessellationJointDirectoryCaptureReaderSpec \
  -s opengpu.system.TessellationJointSourceCrosslinkSpec
```

The selector list above is explicit; it does not run a regression lane. The
disabled Scala regression workflow must not be enabled or dispatched. Reproduce
the reported budget; any later different-budget run must report its actual
limits and preserve the prior bounded result in the final result summary.
