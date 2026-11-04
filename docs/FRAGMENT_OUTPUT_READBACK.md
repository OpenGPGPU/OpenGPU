# Fragment output readback

`FragmentOutputReader` fills the existing sixteen-lane transpose SRAM using
staging transaction IDs 0–1. Two 64-byte buffers replace the previous field and
split-line buffers. Reads for the following field overlap the four 128-bit
SRAM writes for a completed field. The transpose SRAM capacity and read port,
shader batch capacity, payload reservations and public quad ordering remain
the same.

Each transaction retains its field and first/second-line ownership through
response consumption. Split replies may arrive in either order. Byte masks
cover exactly the active lanes. Fields publish their four SRAM rows in order;
public quad export waits for the complete window. A stalled request retains
its address, mask and ID through cancellation. Faults and cancellation discard
unexported work and drain every offered request before the parent releases its
payload lease or admits another draw.

## Measured clocks

The matched baseline is `9d4d6f6a81f0`. Both versions execute six quads in two
batches on two real shared CUs, with four lanes, four warps, four color outputs,
per-sample depth metadata, identical shaders and the same memory stalls.
Every exported value and metadata field matches. Input traffic remains 1,344
enabled bytes, and output readback remains 1,728 enabled bytes.

| Staging | Baseline read clocks | Pipelined read clocks | Baseline draw clocks | Pipelined draw clocks |
|---|---:|---:|---:|---:|
| Global memory | 818 | 544 | 6,705 | 6,430 |
| Resident payload | 618 | 434 | 4,830 | 4,646 |

Read worker clocks include cache filling, metadata preparation and quad
assembly. Total draw clocks include the fixture's held completion and drain.
The reductions are 33.5%/29.8% in read worker clocks and 4.1%/3.8% in total
draw clocks. They do not establish a 720p frame improvement, a qualified
whole-GPU clock or H6 acceptance.

The deterministic reader fixture uses the native 128-lane field stride,
eighteen output fields and twelve-clock memory responses. It fills the window
in 177 clocks, with eighteen requests, 1,152 enabled bytes and two live IDs.
The regression budget is 180 clocks. Split/partial tests cover eighteen
combinations of lane counts and offsets, reordered replies, exact masks and
ordered writes. Cancellation covers both a stalled offer and two accepted
reads; a reordered second-line fault drains the older line before healthy
reuse.

Four reader and fifteen directed shared-CU cases pass, including all 64
varying components, 128 physical lanes, local/global staging, controlled spill,
partial following batches, depth-only exports, native texture operands,
cancellation and attributed faults. Twelve small coalescer/ABI/coverage/early
depth cases and the real 8x8 early/late renderer also pass. The renderer retains
exact canonical color/depth/stencil at 1x/4x MSAA, including discard, depth
override and side-effect fallbacks. These are 32 distinct local test cases.

## Reproduce locally

These are bounded fixtures. The larger shared-CU qualification stays outside
regular PR/nightly lanes; 720p remains an explicit product qualification.

```sh
git submodule update --init depends/asap7_sram_0p0
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=2 \
  -Dopengpu.earlyDepthQualification=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.FragmentOutputReaderSpec' \
  'testOnly opengpu.graphics.FragmentShaderStageQualificationSpec -- -z "coalesce four input quads" -z "drain both fragment readback credits" -z "attribute a younger fragment readback fault" -z "transpose a non-power-of-two12lane batch" -z "carry all64 varying components" -z "drain staging, shader and malformed export faults" -z "require EOF while canceling an accepted staging offer" -z "deliver FP32 and typed raw fragment quads" -z "shade product recipe colors"' \
  'testOnly opengpu.graphics.FragmentInputCoalescerSpec opengpu.graphics.FragmentShaderAbiSpec opengpu.graphics.FragmentShaderSuiteCoverageSpec opengpu.graphics.EarlyDepthFragmentShaderStageSpec' \
  'testOnly opengpu.graphics.EarlyDepthRenderQualificationSpec'
```

The four small PR suites selected for this change are the reader, input
coalescer, fragment ABI and suite-coverage checks. Full-frame performance and
assertion-enabled whole-host qualification remain separate requirements.
