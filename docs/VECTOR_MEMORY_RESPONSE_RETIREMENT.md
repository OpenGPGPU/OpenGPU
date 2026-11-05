# Vector memory reply retirement

`VectorMemoryUnit` formats a memory reply directly into its held architectural
result. It no longer stores the reply in a separate register and waits another
clock to form that result. A healthy result can retire on the same edge that
accepts the next instruction.

The unit still owns one request or one held result, with at most one external
memory transaction outstanding. Backpressure closes input admission. A result
with an active lane fault or a page fault also closes admission on its retirement
edge, preserving the fault handoff before another instruction is accepted.
Request addresses, warp/PC attribution, inactive destination lanes and helper
permissions remain captured. A fully suppressed helper store completes without
an external request. Held requests and results retain their complete fields.

## Measured result

The matched baseline is `c6667b46a9de`. Eight ordered four-lane word loads,
alternating between two warps, receive replies two clocks after memory request
acceptance. They take 48 clocks before this change and 33 clocks afterward,
including final result retirement: 31.25% fewer clocks. Every address, destination,
warp, PC and loaded value is checked, with one external transaction outstanding.
The 33-clock regression budget rejects the previous implementation.

The shared-CU fragment fixture retains six quads, two batches, four color outputs,
per-sample depths and deterministic memory stalls. Stored exports and enabled
traffic remain exact.

| Payload path | Baseline draw clocks | Reply retirement | Reduction |
|---|---:|---:|---:|
| Global memory | 6,319 | 5,962 | 5.65% |
| Resident SRAM | 3,559 | 3,293 | 7.47% |

Read worker clocks are 484/485 before/after for global memory and 374 in both
resident runs. Staging worker clocks remain 957/global and 248/resident. Total
draw budgets tighten to 6,000/global and 3,320/resident; both reject the baseline.

## Validation and limits

Seventeen focused unit checks pass. They cover unit-stride, strided and indexed
addresses; byte/halfword/word results; inactive lanes; held offers and results;
healthy turnover; fault attribution; helper policy snapshots; suppressed stores;
and response assembly under stalls and reset. Lane faults, page faults and their
combination are checked independently. A sixteen-lane/eight-warp case checks
high lane masks, captured helper permissions, suppressed faults and byte loads.
The helper suite includes twelve vector permission scenarios and the full
128-invocation policy split. Nine backend, three actual-CU payload and four trap
retirement checks also pass.
Nine shared-CU fragment checks pass, including the tightened draw budgets,
staging/shader/export faults, metadata overlap, cancellation, ordered readback
fault drain and healthy reuse.

The complete helper-enabled unit, with sixteen lanes and eight warps, passes
synthesis at the existing 1,000 ps TC/SLVT period with 25% I/O delays. Core and
I/O setup slacks are +162.517 ps and +56.230 ps. Mapping and constraint coverage
are complete, with zero skipped or unresolved instances. The run preserves
hierarchy and uses standard ABC, without retiming or false paths. This unit
contains no SRAM macros.

These are component results. They establish neither a whole-host clock nor
720p frame rate. The six actual product scenes, physical qualification, full
feature/resource/API coverage and H6–H8 acceptance remain required.

## Reproduce locally

```sh
git submodule update --init depends/asap7_sram_0p0
sbt -J-Xmx5G -J-XX:ActiveProcessorCount=2 \
  -Dopengpu.productQualification=0 -Dopengpu.fullHostQualification=1 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.core.memory.VectorMemoryUnitSpec opengpu.core.memory.VectorMemoryRetirementSpec opengpu.core.memory.HelperInvocationPolicySpec opengpu.core.memory.VectorMemoryResponseAssemblySpec' \
  'testOnly opengpu.core.backend.VectorBackendSpec opengpu.core.GpuComputeUnitPayloadSpec opengpu.core.ShaderTrapRetirementSpec' \
  'testOnly opengpu.graphics.FragmentShaderStageQualificationSpec -- -z "coalesce four input quads" -z "drain staging, shader and malformed export faults" -z "overlap metadata" -z "drain both fragment readback credits" -z "attribute a younger fragment readback fault" -z "require EOF while canceling an accepted staging offer"'
```

Emit and measure the complete native unit:

```sh
sbt 'Test/runMain opengpu.core.memory.EmitVectorMemoryUnitTiming .artifacts/vector-memory-retirement-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/vector-memory-retirement-rtl VectorMemoryUnit --stage synthesis \
  --synthesis-engine yosys --yosys-preserve-hierarchy --period-ps 1000 \
  --io-delay 0.25 --output .artifacts/vector-memory-retirement-synthesis
```
