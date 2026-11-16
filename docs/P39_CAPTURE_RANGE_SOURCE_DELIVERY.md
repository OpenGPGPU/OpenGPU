# P39 original-source capture and range delivery

This stage delivers three logical source changes on the owned-parent baseline:
exact GNT7/8/9 readers; actual serial GVS1→GND1 capture; and three typed range
adapters. It preserves independent sequence/draw ordinals, original indexed and
instanced draw inputs, accepted memory debt, real error priority and attributable
partial publication. Native field metadata accepts up to one 512-bit cache line
while retaining positive-width and line-containment checks. The existing range
sequence keeps its one-argument constructor and default behavior; continuation
fault admission is optional and checked only after actual child retirement/drain.

Contracts: [GNT readers](TESSELLATION_VERTEX_SEQUENCE_READER.md),
[GVS/GND capture](TESSELLATION_VERTEX_CAPTURE.md),
[typed ranges](TESSELLATION_VERTEX_TYPED_RANGE.md).

## Final local results

All **787 production and 1123 test Scala sources** were freshly compiled with
Scala 2.13.18 and the Chisel 7.15.0 plugin. Runtime used only those new classes
and 38 third-party JARs, independently checked to contain no GPU classes; no
historical GPU class provider was used. Production/test compilation took
90.7/180.8 seconds, with sampled aggregate peaks 3,688,448/4,343,536 KiB.
Each compile group had a 600-second, 6144-MiB bound and a 4-GiB JVM heap.

All **18 original focused RTL cases** passed, with assertions enabled and
simulation caches disabled:

| Suite (`opengpu.graphics.` prefix) | Cases | Group seconds |
| --- | ---: | ---: |
| `TessellationVertexSequenceReaderSpec` | 6 | 54.2 |
| `TessellationVertexCaptureControllerSpec` | 3 | 64.6 |
| `TessellationVertexTypedRangeSpec` | 4 | 130.0 |
| `TessellationVertexPatchSequenceSpec` | 5 | 147.7 |

Each suite had an independent 600-second/1536-MiB aggregate bound, one CPU,
256-MiB heap, 192-MiB metaspace, 64-MiB code cache and one unoptimized Verilator
job. The largest sampled simulation group was 499,232 KiB. Source membership,
source/ABI/compiler/plugin/JAR inputs and compiled classes were checked around
their consumers. No hardware capacity was reduced.

Three separately compiled source-copy negative controls each ran one original
case and failed an explicit behavior expectation:

- Removing `sourceBounds` from GNT packet admission accepted a bad GVS extent
  with status 0 instead of 1 (10.4 seconds).
- Adding `&& !cancel` only to the capture controller's `sourceRun` child-error
  guard changed an already observed GVS error from status 1 to 4 (21.6 seconds).
- Removing the instance-boundary primitive reset produced PrimitiveID 20
  instead of 0 in the next original instance (33.4 seconds).

These were actual RTL assertion failures, not timeouts, OOMs or compilation
failures. Each control had a 240-second/1536-MiB bound and the same simulation
settings. Production source remained unchanged. All 163 affected-suite selector
unit checks, integration/large-PR catalog validation and `git diff --check` passed.
Owned compiler/simulation consumers exited; generated classes, models, caches,
mutants and logs were removed after their final use.

## Reproduction

Use a fresh topic checkout with the supported simulation toolchain:

```sh
sbt clean 'Test / compile'
sbt -Dopengpu.productQualification=0 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simCache=off \
  'testOnly opengpu.graphics.TessellationVertexSequenceReaderSpec'
sbt -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simCache=off \
  'testOnly opengpu.graphics.TessellationVertexCaptureControllerSpec'
sbt -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simCache=off \
  'testOnly opengpu.graphics.TessellationVertexTypedRangeSpec'
sbt -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simCache=off \
  'testOnly opengpu.graphics.TessellationVertexPatchSequenceSpec'
python3 scripts/test_changed_test_suites.py
bash scripts/ci_shard_tests.sh suites system-core ALL
bash scripts/ci_shard_tests.sh suites system-core-large-pr ALL
git diff --check
```

For the negative controls, use separate temporary source copies, apply exactly
one change above, freshly compile it ahead of the normal classes, and select
respectively `-z "capture exact new metadata and reject incompatible packets domain=0"`,
`-z "stop at malformed or stale GVS"`, and `-z "preserve domain 0"` in ScalaTest.
Require one executed test and the corresponding explicit mismatch, then remove
owned outputs after consumers exit. Scala regression remains disabled.

## Remaining scope

The GNT reader and GVS/GND controller execute actual metadata-memory protocols.
The typed range tests supply explicit captured-metadata and retirement peers;
they neither connect the complete capture chain to the image parent nor execute
shaders. No physical CU, image bank, rendering, protected submission or public
API is exercised here. Supported 16-lane/eight-warp metadata and full vertex
interfaces are retained; there is no frame throughput/FPS result.

Checked GNQ→original-draw/invocation mapping, independent input-patch language
identity, joint-frame capture, exclusive cursors across separate ranges, complete
three-domain parent execution, original shaders/resources, protected/API paths
and the 2-CU 640×480 at 60-FPS product remain unfinished. This stage does not
close P39/P40 or change the alignment acceptance count of 3/40.
