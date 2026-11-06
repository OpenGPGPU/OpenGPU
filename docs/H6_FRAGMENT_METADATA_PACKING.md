# Fragment metadata word packing

`FragmentShaderStage` selects 256-bit metadata words only when this needs no
more native SRAM macros than 128 bits. Other configurations retain 128 bits;
the standalone reader also defaults to 128 bits. All fields, capacity, SRAM
ports and cancellation/held-reply behavior are unchanged.

At 128 items/four samples, nine 128-bit rows become five 256-bit rows per quad,
still using four native 256×64 macros. The reader's declared buffer grows
1152→1280 bits (+128); this is not a measured mapped-flop delta. The layout
proof covers all 189 legal capacity/sample combinations (4–252 items in steps
of four, samples 1/2/4): 78 select 256 bits and 111 retain 128 bits.

## Measured result

Eight cold draws used unchanged 42/67-word shaders, two actual native-SRAM CUs
with 16 lanes/eight warps, 128-item capacity, full 16-varying/four-color
interfaces, FPU, default caches, grouped textures, a 262,144-byte payload pool,
69,632-byte maximum payload and instruction quota 1,048,576. Programs use two
active varyings/one color. Thirty-five quads cover 128 items plus a partial
batch; input, latency and backpressure are identical on both variants.

| Program / quads | Total cycles, control → packed | Input phase, control → packed |
| --- | ---: | ---: |
| 42 / 1 | 826 → 822 | 93 → 87 |
| 42 / 35 | 3420 → 3212 (−6.08%) | 1015 → 861 (−15.17%) |
| 67 / 1 | 2230 → 2226 | 93 → 87 |
| 67 / 35 | 6168 → 5971 (−3.19%) | 1010 → 863 (−14.55%) |

All fixed gates passed: 42/35 ≥5% total/10% input improvement, 67/35 ≥2%/10%,
and neither one-quad case regresses total/input cycles. Shader words,
completion, metadata, exports, staging bytes/order, initial/returned memory,
full task publications, per-ID ownership/drain and external traffic match.
There are no external writes. Five small reader/layout tests also passed,
covering all metadata bits, padding, partial batches, held replies,
cancellation, same-cycle consume/start and healthy reuse.

The 67/35 waitKernel interval grows five clocks. Four extra public blocked
clocks are observed; one clock between a code reply and first descriptor
offer remains internally unattributed. Non-target phases were retained;
there is no timing-alignment waiver. These Stage shader-export measurements
do not establish complete-frame performance or FPS.

## Native component setup

Fresh complete `mobile-public-frame-fragment-shader` synthesis passed native
mapping, setup and constraint coverage at 1000 ps/25% virtual IO delays:
**core +65.697 ps; virtual IO +62.773 ps**, ASAP7 SLVT/TC. There are no skipped
instances or unresolved diagnostics. This Stage has four shared-CU interfaces;
it does not instantiate four CUs. Placement, routing, hold and whole-host
timing are excluded. Planning assumes 1 GHz without qualifying whole-host
frequency or product frame rate.

## Reproduction

Use fresh focused Test compilation for the small reader suite:

```sh
sbt -J-Xmx2G -J-XX:ActiveProcessorCount=1 -J-XX:+ExitOnOutOfMemoryError \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  'set Test / sources := Seq((Test / scalaSource).value / "opengpu/testutil/GpuSim.scala", (Test / scalaSource).value / "opengpu/graphics/FragmentMetadataReaderSpec.scala")' \
  'testOnly opengpu.graphics.FragmentMetadataReaderSpec'
```

For Stage A/B, `H6_TEST_CP` contains normally compiled Main, runtime JARs and
focused Test classes: `FragmentShaderStageSpec`, `GpuSim`, `GraphicsTestResolution`,
`CompressedImageReference`, `Fp32ViewportStageSpec`, `NativeProductShaderPrograms`,
`NativeProductWorkloads`, `NativeProjectiveShaderTestData`, `NativeTextureFetchTestData`,
`NativeTexturePolicyReference`, `NativeUniformTestData`, `TextureCompressedBlockDecoderSpec`,
`TextureImageFormatSpec`, `TypedColorBlendSpec` and `TypedColorFormatSpec`.
Keep control's original reader/stage files. Run control, then candidate with
separate output/cache paths, preserving control as baseline:

```sh
java -Xmx3G -XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simCcache=off -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simCache=/tmp/meta-control-model -Dopengpu.fragmentPhaseAudit=1 -Dopengpu.fragmentPhaseAuditDir=/tmp/meta-control-audit \
  -Dopengpu.fragmentMetadataPackingQualification=1 -Dopengpu.fragmentMetadataPackingMode=control \
  -Dopengpu.fragmentMetadataPackingOutput=/tmp/meta-control -Dopengpu.fragmentMetadataPackingBaseline=/tmp/meta-control \
  -cp "$H6_TEST_CP" org.scalatest.tools.Runner -s opengpu.graphics.FragmentShaderStageQualificationSpec \
  -t 'should measure unchanged product shaders through native metadata word packing'
```

For PPA, emit `opengpu.elaboration.EmitPpaRtl mobile-public-frame-fragment-shader <rtl-dir>`
using candidate Main CP, then run `scripts/qualify_ppa.py <rtl-dir> FragmentShaderStage
--stage synthesis --synthesis-engine yosys --yosys-preserve-hierarchy --clock clock
--period-ps 1000 --io-delay 0.25 --timeout 14400 --output <result-dir>`.
Use image `sha256:a61e1f55785dcd34306e833181fc3c9c1699eef30bfd518352dc5478a31ea1d2`,
CPU1/hard 6 GiB/no swap/network disabled. Require actual mapping and complete
constraint coverage. Stage/PPA qualification stays outside routine CI/nightly.
