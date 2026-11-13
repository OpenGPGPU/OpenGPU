# Fragment metadata prefetch

`FragmentMetadataReader` uses the existing private synchronous metadata SRAM
port and one quad register buffer. The first quad is fetched alongside the
output transpose window. When `FragmentShaderStage` copies that metadata into
its held output, the reader starts the next quad while the current quad's
color, depth and control fields are assembled. The final synchronous reply
must arrive before metadata can be consumed.

The reader retains the buffered quad and its identity under backpressure.
The parent checks its quad index before assembling an export. The SRAM bank
selector follows each issued address, including a quad whose rows cross a
physical bank boundary. Cancellation discards private prefetched data and its
pending reply. An already offered public export stays stable, and the parent
still drains owned staging, shader and texture work before releasing its
payload lease. Metadata SRAM, transpose SRAM, batch capacity, field strides,
payload reservations and public quad order retain their existing sizes and
contracts.

## Measured clocks

The matched baseline is `9ea41f1aa1a0`. Six quads execute in two batches on
two real shared CUs with four lanes, four warps, four color outputs,
per-sample depth metadata, identical shaders and deterministic memory stalls.
Every exported value and metadata field matches. Enabled input and output
readback traffic remain 1,344 and 1,728 bytes.

| Payload path | Serial metadata read clocks | Prefetched read clocks | Serial draw clocks | Prefetched draw clocks |
|---|---:|---:|---:|---:|
| Global memory | 544 | 484 | 6,374 | 6,319 |
| Resident SRAM | 434 | 374 | 3,619 | 3,559 |

Read worker clocks include window filling, metadata preparation and quad
assembly. Total draw clocks include the fixture's held completion and drain.
Read worker clocks decrease by 11.0% and 13.8%; total draw clocks decrease by
0.86% and 1.66%. The global input-staging count changes from 956 to 957
because the fixture's memory backpressure depends on the cycle; enabled
traffic remains identical.

For quads whose transpose window is already available, the four-color export
interval decreases from 32 to 22 clocks. Both resident and controlled-spill
128-lane cases retain exact exports for 35 quads in two batches, all 64 varying
components and per-sample depths. Each checks 26 prepared export intervals at
the 22-clock limit. Both 12-lane partial-capacity cases also meet that limit.

The small reader checks cover same-clock consume/start, held results, every
cancellation prefix including the final reply, immediate healthy reuse,
single-row metadata and non-power-of-two capacities. Full shared-CU checks
exercise actual exports, all 64 varying components, 128 physical lanes,
four colors, per-sample depths, partial batches, global/resident payloads and
controlled spill. Forty-two distinct local cases pass, including the real
8x8 early/late renderer at 1x/4x MSAA and held-export/held-launch cancellation.
The test-selection checks also pass all 127 cases. Component correctness and
clocks do not establish a 720p frame improvement, a qualified whole-host clock,
product FPS or H6 acceptance.

The complete native early-depth fragment component passes synthesis at the
existing 1,000 ps TC/SLVT period with 25% I/O delays. Reported core and I/O
setup slacks are +82.519 ps and +71.224 ps. Real SRAM implementations remain
mapped, no instances are skipped or unresolved, and constraint coverage is
complete. The run uses preserved hierarchy, standard ABC, no retiming and no
false paths. This is pre-layout component timing; whole-host physical timing
and the six actual 720p workloads remain required.

The matched resident/global regressions now enforce 380/490 read worker
clocks and 3,580/6,340 total draw clocks. Both pass with the measured results
above; the serial baseline exceeds all four new limits.

## Reproduce locally

The complete CU cases remain local. Routine PR selection uses the small
metadata reader, input coalescer, fragment ABI and suite-coverage checks.

```sh
python3 scripts/test_changed_test_suites.py
git submodule update --init depends/asap7_sram_0p0
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=2 \
  -Dopengpu.earlyDepthQualification=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.FragmentMetadataReaderSpec opengpu.graphics.FragmentOutputReaderSpec opengpu.graphics.FragmentInputCoalescerSpec opengpu.graphics.FragmentShaderAbiSpec opengpu.graphics.FragmentShaderSuiteCoverageSpec opengpu.graphics.EarlyDepthFragmentShaderStageSpec' \
  'testOnly opengpu.graphics.FragmentShaderStageQualificationSpec -- -z "overlap metadata" -z "coalesce four input quads" -z "deliver FP32 and typed raw fragment quads" -z "carry all64 varying components" -z "transpose a non-power-of-two12lane batch" -z "drain staging, shader and malformed export faults" -z "require EOF while canceling an accepted staging offer" -z "drain both fragment readback credits" -z "attribute a younger fragment readback fault" -z "deliver captured geometry metadata" -z "retain protected per-sample raster depths" -z "carry coherent covered sample locations" -z "spill under pool pressure"' \
  'testOnly opengpu.graphics.EarlyDepthRenderQualificationSpec'
```

The complete native early-depth fragment component uses real SRAM
implementations and the existing 1,000 ps period with 25% I/O constraints:

```sh
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-native-early-depth-fragment-shader .artifacts/fragment-metadata-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/fragment-metadata-rtl FragmentShaderStage --stage synthesis \
  $(python3 scripts/report_ppa_sram_inventory.py .artifacts/fragment-metadata-rtl FragmentShaderStage) \
  --synthesis-engine yosys --yosys-preserve-hierarchy --period-ps 1000 \
  --io-delay 0.25 --output .artifacts/fragment-metadata-synthesis
```
