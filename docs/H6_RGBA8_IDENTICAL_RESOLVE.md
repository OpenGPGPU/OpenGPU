# H6 identical four-sample RGBA8 resolve

`TypedSampleResolve` returns the common low 32 bits directly when all four
RGBA8_UNORM samples match, with the upper 96 result bits cleared. The check
follows invalid-mode and raw/depth handling. Other formats and unequal samples
retain the existing decode, exact FP32 mean and encode path. Gather, memory
traffic, interfaces, capacities and cancellation ownership are unchanged.

For each byte `k`, four identical decoded values average to the same FP32
value; its UNORM8 re-encoding returns `k`. This does not permit general integer
averaging: samples `[0,0,0,2]` produce byte 1 through the existing codec, whereas
rounding their integer mean to even produces 0. The shortcut does not apply to
sRGB, floating-point NaNs/signed zero, depth or other packed formats.

Three actual component models passed 30 tests with zero skips: the same Test
fixture against the control and candidate transfer, plus the candidate resolver.
The unchanged decode/mean/encode reference checks 256 byte values per channel,
dirty upper input bits, fourth-alpha-only and paired differences, the rounding
counterexample and format fallbacks. Short-result cancellation, poisoned live
inputs, held results, reset, faults and healthy reuse also pass. Distinct RGBA8
retains its 70-clock resolver latency; the identical case takes one clock.

| Fixed 8×8 RGBA8 4x probe | Control clocks | Candidate clocks | Clocks saved |
| --- | ---: | ---: | ---: |
| Four equal samples at each pixel | 5598 | 1182 | 4416 (78.89%) |
| 32 equal pixels, 32 unequal pixels | 5598 | 3390 | 2208 (39.44%) |
| Four distinct samples at every pixel | 5598 | 5598 | 0 |

The fixed gates required at least 4096/2048 clocks and 2% savings for the first
two probes, with no distinct-sample regression. Each probe retains 128 private
word reads, zero external reads, ten writes, 256 resolved bytes and exact image
and allocation guards. Existing 1x resolve, RGBA8 publication and RGBA8+D24S8
publication retain their 542/895/1774-clock limits. All 36 formats, the final
64-KiB source word, partial masks, held offers/acknowledgements and cancel/fault
drain checks remain covered. Functional tests use a 65,536-byte slot; the product
default remains 32,768 bytes. At the assumed 1 GHz, 1182/3390/5598 clocks
correspond to 1.182/3.390/5.598 µs of component service time, without a frame-rate
or host simulation speed claim.

Complete production-default synthesis passes at 1000 ps and 25% I/O delay,
ASAP7 SLVT/TC. Core and virtual-I/O setup slack are +169.512/+396.131 ps.
All 249 ports, 12-bit local word addresses and nine functional modules remain
present, including the complete resolver/encoder/admission hierarchy. There
are zero abstract SRAMs, omitted operators or unresolved drivers. Linked STA
passes complete `check_setup` coverage. No retiming or false paths are used.
These are synthesis/setup estimates; routed hold, whole-GPU timing and product
frame rates require separate qualification.

Reproduce the new 8×8 probes and short-path checks, then the five-case resolver
suite. These focused commands do not repeat all 30 A/B qualification executions:

```sh
JDK_JAVA_OPTIONS='-Xmx1G -XX:ActiveProcessorCount=1 -XX:+ExitOnOutOfMemoryError' \
  sbt -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 -Dopengpu.simCcache=off \
  -Dopengpu.rgba8UniformResolveQualification=candidate \
  'set Test / parallelExecution := false' \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "TypedColorFormatSpec.scala", "TypedColorBlendSpec.scala", "TypedDepthStencilSpec.scala", "TypedSampleResolveSpec.scala", "TypedTileImageTransferSpec.scala").contains(f.getName))' \
  'testOnly opengpu.graphics.TypedTileImageTransferSpec -- -z "qualify identical RGBA8 four-sample" -t "should preserve identical RGBA8 tails and drain only owned cancelled resolve writes before reuse"' \
  'testOnly opengpu.graphics.TypedSampleResolveSpec'
```

For A/B, keep these Test sources and restore only `TypedSampleResolve.scala`
from control `9e884ce4` in a separate topic/worktree. Use the `control` property
there and fresh normal content-derived caches on both sides. The three manual
performance probes are absent when this property is unset.

```sh
JDK_JAVA_OPTIONS='-Xmx1G -XX:ActiveProcessorCount=1 -XX:+ExitOnOutOfMemoryError' \
  sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-typed-image-transfer .artifacts/identical-rgba8-rtl'
NUM_CORES=1 OMP_NUM_THREADS=1 "${CHIPAGENT_DIR:-../chipagent}/.venv/bin/python" scripts/qualify_ppa.py \
  .artifacts/identical-rgba8-rtl TypedTileImageTransfer --stage synthesis \
  --synthesis-engine yosys --yosys-preserve-hierarchy --clock clock \
  --period-ps 1000 --io-delay 0.25 --timeout 1200 \
  --output .artifacts/identical-rgba8-ppa
```

Apply CPU1, hard 6 GiB memory with equal memory-plus-swap limits, network none
and single-thread settings to every synthesis, STA and coverage container.
Qualification used CPU1/Xmx768MiB/owned3GiB for the three sequential native
models; CPU1/Xmx1GiB/owned2GiB/180s for emission; 1200s for synthesis, STA and
coverage together, with zero retries. No frame-scale, guest or nightly run is
needed for these checks.
