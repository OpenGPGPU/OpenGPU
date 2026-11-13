# Vertex input coalescing

`VertexInputCoalescer` buffers at most sixteen vertices in sixteen private
synchronous SRAM banks. Each accepted scalar input word retains its vertex
and field index. Flushing transposes those words into field-major 64-byte
line writes. Partial groups enable only captured vertices; rows crossing a
line use two exact masked writes, including at the exclusive 4 GiB boundary.

The fetcher retains each record until all its fields have been captured.
`VertexShaderStage` flushes full groups and every partial group before a
batch can invalidate or launch. Restart tokens and post-transform cache hits
retain their original order. Cancellation drops private unoffered data;
the stage still drains accepted memory requests, shader work and held exports
before releasing its payload lease. The existing single staging credit,
metadata SRAM, output transpose window, payload prefix and batch capacities
are unchanged.

The new buffer has 8 KiB of logical SRAM at the full 67-field interface,
implemented with sixteen characterized 256x32 macros, or 16 KiB of physical
SRAM per coalescer. It does not consume the resident payload arena or reduce
the full 128-thread, 16-attribute, 16-varying product interface. Payloads that
exceed the resident limit still spill explicitly.

## Measured result

The baseline is `482e29f9db62`. Twenty-two vertices in two instances execute
in two batches on two real shared CUs, with four lanes, four warps, eleven
input fields and deterministic memory backpressure. Positions, varying
values, ordinals, vertex/instance IDs, enabled input bytes and the uniform
tail remain exact.

| Payload path | Scalar input requests | Coalesced requests | Enabled input bytes | Scalar draw clocks | Coalesced draw clocks |
|---|---:|---:|---:|---:|---:|
| Resident SRAM | 242 | 22 | 968 | 5,793 | 4,289 |
| Global memory | 242 | 22 | 968 | 9,031 | 5,762 |

Input requests decrease by 90.9%; draw clocks decrease by 26.0% and 36.2%.
The fixture enforces exactly 22 input requests and resident/global draw
budgets of 4,300/5,800 clocks. Both tightened cases pass again; the scalar
baseline exceeds these limits.

Twenty-seven distinct local cases pass: eight coalescer cases, the two matched
draws and seventeen shared-CU integration cases. These cover every input field,
shrinking groups and stale-bank masks, non-power-of-two strides, held split
writes, cancellation prefixes and reuse, indexed/instanced restarts,
post-transform reuse, product recipe uploads, converted inputs, texture and
memory faults, held launches/exports, healthy recovery, and full 128-thread,
16-attribute/16-varying spill. All 127 suite-selection checks also pass.

The complete native public-frame vertex component passes pre-layout synthesis
at the unchanged 1,000 ps TC/SLVT period and 25% I/O delays, with core
+68.111 ps and I/O +74.301 ps setup slack. Real 32-bit and 64-bit SRAMs are
mapped, no instances are skipped or unresolved, and constraint coverage is
complete. The run preserves hierarchy, uses standard ABC, and applies no
retiming or false paths. These component results do not establish an actual
product frame improvement, a qualified whole-host physical clock, product FPS or
H6–H8 acceptance.

## Reproduce locally

Routine PR selection uses the coalescer, clear-line and texture-lane checks.
Complete shared-CU integration remains local.

```sh
python3 scripts/test_changed_test_suites.py
git submodule update --init depends/asap7_sram_0p0
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=2 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.VertexInputCoalescerSpec' \
  'testOnly opengpu.graphics.VertexShaderStageSpec -- -z "preserve two vertex input batches" -z "execute product vertex uploads" -z "drain a newly accepted registered response" -z "cancel unoffered vertex output" -z "reuse pure indexed vertex outputs" -z "clear partial global output lines" -z "execute ordered indexed instanced vertex shaders" -z "execute converted half normalized packed and fixed vertex inputs" -z "reject invalid layouts" -z "sample vertex textures on shared CUs" -z "drain held launches, exports, input faults and shader traps" -z "drain offered global writes and staging faults" -z "execute 128 threads and explicitly spill"'

sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-public-frame-vertex-shader .artifacts/vertex-input-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/vertex-input-rtl VertexShaderStage --stage synthesis \
  $(python3 scripts/report_ppa_sram_inventory.py .artifacts/vertex-input-rtl VertexShaderStage) \
  --synthesis-engine yosys --yosys-preserve-hierarchy --period-ps 1000 \
  --io-delay 0.25 --output .artifacts/vertex-input-synthesis
```
