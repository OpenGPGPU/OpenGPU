# Fragment input and metadata staging

`FragmentShaderStage` writes a validated quad's input fields and its metadata
through independent SRAM ports. Metadata starts with the first input field.
The quad remains owned until both input/output initialization and every
metadata row have completed; only then can the batch count advance or another
quad be accepted. The existing metadata SRAM, input coalescer, payload lease,
batch capacity and public input/output ordering are retained.

The native four-sample metadata occupies nine 128-bit rows per quad. ABI v1
with two varyings writes fourteen input fields plus two initialized output
fields, so those sixteen clocks also cover all metadata writes. Zero-varying,
depth-only inputs finish their eight fields first and retain the metadata
drain state for the last row. Cancellation and faults stop private writes,
discard unexported staging and retain the existing offered-request drain
before payload release. A subsequent quad rewrites all its metadata rows.

## Measured clocks

The matched baseline is `bc3b57d84c8e`. Six quads execute in two batches on
two real shared CUs with four lanes and four warps, four color outputs,
per-sample depth and identical deterministic memory stalls. Every output value
and metadata field matches. Enabled input and output readback traffic remain
1,344 and 1,728 bytes respectively.

| Payload path | Serial metadata draw clocks | Overlapped draw clocks | Read worker clocks, both versions |
|---|---:|---:|---:|
| Global memory | 6,430 | 6,374 | 544 |
| Resident SRAM | 4,646 | 4,593 | 434 |

Total draw clocks decrease by 0.87% and 1.14% in this bounded fixture.
The new staging worker counts are 956 and 248 clocks, with regression budgets
of 970 and 260. At this change, total draw budgets were 6,390 and 4,610 clocks;
the serial implementation exceeds both. The subsequent
[direct CU vector payload path](CU_VECTOR_PAYLOAD_ACCESS.md) tightens the
resident draw budget further. These measurements include the fixture's held
completion and drain. They do not measure a full product frame.

## Local validation

Twenty-four distinct local checks pass. The directed shared-CU checks cover
minimum inputs with per-sample depth,
complete and partial batches, cancellation at six input-staging cuts and exact
exports after every reuse. The broader selectors retain global and resident
payloads, controlled spills, all 64 varying components, four color outputs,
128 physical lanes, non-power-of-two batch sizes, EOF and attributed faults.
These larger component checks remain local. PR CI selects only the small
input-coalescer, fragment ABI and suite-coverage suites.

The complete native early-depth fragment component passes the 1,000 ps ASAP7
TC/SLVT synthesis setup gate with 25% I/O constraints: core +81.722 ps and
I/O +71.224 ps. The emission contains 269 SV files and real SRAM implementations.
No functional logic is skipped or unresolved, and `check_setup` reports
complete, clean constraint coverage. The source and emitted bytes remain
unchanged through qualification. This is component synthesis timing; routed
setup/hold and the whole-host clock remain unqualified.

Reproduce the bounded checks:

```sh
git submodule update --init depends/asap7_sram_0p0
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=2 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.FragmentShaderStageQualificationSpec -- -z "overlap metadata" -z "coalesce four input quads" -z "deliver FP32 and typed raw fragment quads" -z "carry all64 varying components" -z "transpose a non-power-of-two12lane batch" -z "drain staging, shader and malformed export faults" -z "require EOF while canceling an accepted staging offer"' \
  'testOnly opengpu.graphics.FragmentInputCoalescerSpec opengpu.graphics.FragmentShaderAbiSpec opengpu.graphics.FragmentShaderSuiteCoverageSpec opengpu.graphics.EarlyDepthFragmentShaderStageSpec'
```

The complete component uses the existing synthesis profile and constraints:

```sh
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-native-early-depth-fragment-shader .artifacts/fragment-staging-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/fragment-staging-rtl FragmentShaderStage --stage synthesis \
  $(python3 scripts/report_ppa_sram_inventory.py .artifacts/fragment-staging-rtl FragmentShaderStage) \
  --synthesis-engine yosys --yosys-preserve-hierarchy --period-ps 1000 \
  --io-delay 0.25 --output .artifacts/fragment-staging-synthesis
```

Component clocks and synthesis timing do not establish a whole-host clock,
product frame improvement, product frame rate or H6 acceptance.
