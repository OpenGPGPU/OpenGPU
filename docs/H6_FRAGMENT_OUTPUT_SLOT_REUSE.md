# Fragment output slot reuse

`FragmentOutputReader` can allocate the next field into an existing slot on
the clock that publishes that slot's final row. The old row reaches the Stage
transpose SRAM before the edge; the newly allocated field retains the slot's
valid bit after the edge. The two 512-bit buffers, transaction IDs, credit
rules, public interface and production capacity are unchanged.

## Component result

Two independent native models used 16 physical lanes/eight warps (128-item
capacity), stride 512, maximum 18 fields and identical focused Test source.
The fixed probe reads 18 fields with always-ready requests and replies two
clocks after acceptance. Timing starts before `start()` and ends at the first
observed `done.valid`; completion consumption and its held-completion check
are outside the timed interval.

| Active lanes | Control cycles | Reuse cycles | Saved |
| --- | ---: | ---: | ---: |
| 4 | 58 | 50 | 8 |
| 8 | 67 | 59 | 8 |
| 12 | 77 | 69 | 8 |
| 16 | 87 | 79 | 8 |

The fixed four-lane gate required at least eight saved clocks; the other
widths required no regression. All passed. At the assumed 1 GHz, eight clocks
are 0.008 microseconds for this component probe.

All 45 successful probes match ordered request addresses/masks, request and
reply counts, publication order and every active byte. Coverage includes
36 combinations of offsets 0/48/61, widths 4/8/12/16 and field counts 1/2/18;
last-row publication with another slot's reply; cancellation or fault on the
retirement edge; held requests; same-beat replies; reordered split faults;
owned-request drain and healthy reuse without reset. Both variants observe
nine last-row/other-field-reply coincidences in the directed overlap probe.

The existing manual Stage consumer also passed windows of 16/4/8/12/16 lanes
without reset, preserving 128 physical threads, 16 varyings, four colors,
18 output fields and the full behavioral transpose SRAM. Its 1328-port Stage
used a bounded shared-task peer with no CU/ISA execution. All five windows
passed exact exports, 64-byte guards, nine held-output clocks, enabled cache
read-bound assertions and complete request drain. This is an integration
check, not a measured Stage speedup.

Arbitrary response latency and backpressure can hide the saved allocation
clocks or change their alignment. No whole-frame speedup or FPS is inferred.

## Production component timing

Fresh emission and complete stock hierarchical synthesis/setup also pass for
`mobile-native-fragment-shader`: 128 physical threads, 16 varyings, four render
targets, 16 KiB maximum payload with owner 1, all 1376 ports and 122 reachable
modules. All 14 native SRAM macros and 13 Queue register arrays (7418 bits)
are retained and mapped. At 1000 ps, SLVT/TC and 25% I/O delay, core setup
slack is +22.387 ps and virtual-I/O setup slack is +72.591 ps. Mapping has no
skipped instances, and linked `check_setup` coverage is complete.

This uses no memory abstraction, retiming or false-path exception. The larger
`mobile-public-frame-fragment-shader` profile, place/route, hold, whole-GPU
frequency and whole-frame performance are outside this qualification.

## Reproduction

Use the parent revision for control Main and this revision for candidate Main.
Compile the same Test sources from this revision against each Main:
`opengpu/testutil/GpuSim.scala` and
`opengpu/graphics/FragmentOutputReaderSpec.scala`. `H6_READER_CP` contains the
selected Main, those focused Test classes and normal runtime JARs. Run each
variant with its own fresh model directory:

```sh
MODE=candidate # control for the parent Main
java -Xmx1G -XX:ActiveProcessorCount=1 -XX:+ExitOnOutOfMemoryError \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simCcache=off -Dopengpu.simCache=/tmp/h6-reader-slot-$MODE \
  -Dopengpu.fragmentOutputSlotReuseQualification=$MODE \
  -cp "$H6_READER_CP" org.scalatest.tools.Runner \
  -s opengpu.graphics.FragmentOutputReaderSpec \
  -t 'should qualify same-slot fragment readback refill with exact bytes concurrent replies and cancellation'
```

The measured run used one native compiler, a 1 GiB JVM heap, one CPU and a
2 GiB owned-RSS limit with a 180-second limit per variant and no retries.
The opt-in probe adds no PR/nightly lane or rendering workload.

For the Stage integration check, compile the existing manual source
`scripts/qualification/FragmentOutputRowsConsumerQualificationSpec.scala`
with `GpuSim.scala` against candidate Main. Set `H6_STAGE_CP` to those classes
and the runtime JARs, then use a fresh model directory:

```sh
java -Xmx1G -XX:ActiveProcessorCount=1 -XX:+ExitOnOutOfMemoryError \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simCache=/tmp/h6-reader-slot-stage \
  -cp "$H6_STAGE_CP" org.scalatest.tools.Runner \
  -s opengpu.graphics.FragmentOutputRowsConsumerQualificationSpec \
  -z 'consume only initialized quad rows at all partial default-capacity output widths'
```

This single native model passed under CPU1/1 GiB heap/2 GiB owned RSS and a
300-second limit, with no retries.

For production synthesis, use the stock flow with fresh output directories:

```sh
sbt -J-Xmx1G -J-XX:ActiveProcessorCount=1 \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-native-fragment-shader generated/ppa-reader-slot'
python scripts/qualify_ppa.py generated/ppa-reader-slot FragmentShaderStage \
  --stage synthesis --clock clock --period-ps 1000 --io-delay 0.25 \
  --utilization 25 --density 0.6 --synthesis-engine yosys \
  --yosys-preserve-hierarchy --timeout 3420 --output .artifacts/ppa-reader-slot
```

The measured emission and synthesis/setup used a 3600-second total bound,
one CPU, a 1 GiB JVM heap, 2 GiB host owned RSS, and three sequential containers
each limited to 6 GiB memory with equal memory-swap and networking disabled.
All completed normally without retries. Source-based reproduction commands
above are provided for replay; they were not rerun as extra qualifications.
