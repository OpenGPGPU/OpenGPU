# H6 exact resolve gather-tail fusion

`TypedTileImageTransfer` stores a completed raw/sample-zero record as soon as
its final gathered fragment arrives. The shortcut combines that fragment with
the registered sample before leaving the gather state, preserving the complete
128-bit payload and advancing the destination-row base once. It removes one
otherwise empty complete-record cycle per pixel. Arithmetic multisample resolve,
formats, capacities, buffer ownership and stop/cancel/drain rules are unchanged.
No register, SRAM or interface is added.

Two actual no-CU models ran the same six focused cases against control
`f58edaca` and the candidate. All twelve executions passed, each with exactly
one testcase and zero skips. The largest fixture is 16x16.

| Fixed probe | Control clocks | Candidate clocks |
| --- | ---: | ---: |
| 8x8 RGBA8, 1x resolve | 606 | 542 |
| 8x8 RGBA8, 4x resolve | 5598 | 5598 |
| 16x16 RGBA8 publication | 895 | 895 |
| 16x16 RGBA8 + D24S8 publication | 1774 | 1774 |

The 64-pixel 1x probe saves 64 clocks (10.56%), meeting the fixed 64-clock and
2% gates. At the assumed 1 GHz, this component probe takes 0.542 µs.
The 4x nonregression gate passes. Traffic stays at 32/128 private word reads
for 1x/4x, zero external reads, ten writes and 256 resolved bytes. Independent
image and allocation-guard oracles cover all 36 formats, raw integer/depth/stencil
modes, signed zero/NaN payloads, packed and 128-bit cross-word records, pitched
rows and the final 64-KiB source word. Cancellation covers both halves of a
16-byte record and the final stencil view; an exact seven-byte owned prefix
drains while its nine-byte unoffered suffix is discarded. Held requests and
acknowledgements, faults, healthy reuse, bounds, permissions and aliases pass.
Tests use a 65,536-byte slot to cover the exclusive source boundary; the product
default remains 32,768 bytes.

Complete production-default 32,768-byte synthesis passes at 1000 ps with 25%
I/O delays, ASAP7 SLVT/TC, native SV and the full resolver/encoder/admission
hierarchy. Core setup slack is +167.515 ps; virtual I/O setup slack is
+417.923 ps. All 249 ports, 12-bit local word addresses and nine modules are
retained, with zero SRAM abstractions, mapping omissions or unresolved
operators. Linked STA passes complete `check_setup` coverage. No retiming,
mocks or abstract modules are used. These are component synthesis estimates;
routed whole-GPU timing and frame/FPS gains remain unqualified.

Reproduce the focused cases with one native build job:

```sh
JDK_JAVA_OPTIONS='-Xmx2G -XX:ActiveProcessorCount=1 -XX:+ExitOnOutOfMemoryError' \
  sbt -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 -Dopengpu.simCcache=off \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "TypedColorFormatSpec.scala", "TypedColorBlendSpec.scala", "TypedDepthStencilSpec.scala", "TypedSampleResolveSpec.scala", "TypedTileImageTransferSpec.scala").contains(f.getName))' \
  'testOnly opengpu.graphics.TypedTileImageTransferSpec -- -t "should bound single-sample resolve throughput with distinct pixels and exact pitched guards" -t "should resolve a complete four-sample RGBA8 tile with distinct samples and exact pitched guards" -t "should bound publication throughput for complete RGBA8 and D24S8 tiles" -t "should preserve single-sample and raw resolve bits across formats rows and the final source word" -t "should cancel raw resolve boundary windows and drain held traffic before healthy reuse" -t "should reject resolve bounds permissions output aliases and late source aliases before memory access"'
```

For A/B, retain the same Test fixture and restore only the control Main file
from `f58edaca` in a separate topic/worktree. Require control 606/5598, candidate
at most 542 and at least 64 clocks/2% lower for 1x, no 4x regression, and exact
895/1774 store clocks. Use fresh normal content-derived caches on both sides.

The production timing target uses the existing emitter and stock qualifier:

```sh
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-typed-image-transfer .artifacts/gather-tail-rtl'
NUM_CORES=1 OMP_NUM_THREADS=1 "${CHIPAGENT_DIR:-../chipagent}/.venv/bin/python" scripts/qualify_ppa.py \
  .artifacts/gather-tail-rtl TypedTileImageTransfer --stage synthesis \
  --synthesis-engine yosys --yosys-preserve-hierarchy --clock clock \
  --period-ps 1000 --io-delay 0.25 --timeout 1200 \
  --output .artifacts/gather-tail-ppa
```

Apply CPU1, hard 6 GiB memory with equal memory-plus-swap limits, network none and
single-thread settings to every synthesis, STA and coverage container. Native
validation used CPU1/Xmx1GiB/owned2GiB; emission CPU1/Xmx2GiB/host3GiB. PPA used
120 s emit and 1200 s total limits, with zero retry. These are component cycle
and synthesis results; whole-GPU routed timing and frame/FPS gains require
separate qualification.
