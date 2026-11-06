# H6 exact sample-zero resolve bypass

`TypedTileImageTransfer` now sends a complete single-sample, integer or
depth/stencil record directly to its existing store path. These modes already
returned sample zero unchanged; skipping the resolver offer/finish/response
handshakes saves three clocks per pixel. The complete 128-bit record is already
registered, and the destination-row add still advances exactly once. The
four-sample gather and averaging paths, formats, capacities, ownership and
cancellation/drain rules remain intact. No register, SRAM or channel is added.

## Results

The same frozen Test fixture ran against control `1734c580` and the candidate.
Two actual no-CU component models each served six focused cases through the
normal content-derived executable cache. All twelve executions passed, with
one testcase and zero skips per execution; the largest fixture was 16x16.

| Fixed probe | Control clocks | Candidate clocks |
| --- | ---: | ---: |
| 8x8 RGBA8, 1x resolve | 862 | 670 |
| 8x8 RGBA8, 4x resolve | 5662 | 5662 |
| 16x16 RGBA8 publication | 895 | 895 |
| 16x16 RGBA8 + D24S8 publication | 1774 | 1774 |

The 64-pixel 1x probe saves exactly 192 clocks, or 22.27%, passing the predeclared
192-clock **and** 2% gates. Its traffic stays at 32 private word reads, zero
external reads, ten physical writes and 256 resolved bytes. Independent image
and guard-byte oracles cover all 36 formats at 1x, integer/depth/stencil sample
zero at 2x/4x, signed zero and NaN payloads, packed/wide records, cold reads,
nonzero origins, multiple pitched rows, source publication and the final 64-KiB
source word. Cancellation windows, held requests/acknowledgements, faults,
healthy reuse, descriptor bounds and aliases also pass.

All tests share a 65,536-byte test configuration for the exclusive source-slot
boundary. The product default remains 32,768 bytes. An externally timed cancel
can observe an earlier irrevocable offer; unoffered writes are discarded and
acknowledged prefixes retain exact payload and guard bytes.

Complete production-default synthesis passes at 1000ps with 25% I/O delays,
ASAP7 SLVT/TC, native SV and the full resolver/encoder/admission hierarchy.
Core setup slack is +158.932ps and I/O setup slack is +411.745ps. All 249 ports,
12-bit local word addresses and nine modules remain present; the component has
zero SRAM macros. Mapping reports zero omissions or unresolved operators, and
linked STA passes complete `check_setup` coverage. No retiming, mocks or abstract
modules are used. These are component cycle and synthesis results; whole-GPU
routed timing and frame/FPS performance require separate qualification.

## Reproduction

Use the focused Test dependencies and one native build job:

```sh
JDK_JAVA_OPTIONS='-Xmx2G -XX:ActiveProcessorCount=1 -XX:+ExitOnOutOfMemoryError' \
  sbt -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simCcache=off \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "TypedColorFormatSpec.scala", "TypedColorBlendSpec.scala", "TypedDepthStencilSpec.scala", "TypedSampleResolveSpec.scala", "TypedTileImageTransferSpec.scala").contains(f.getName))' \
  'testOnly opengpu.graphics.TypedTileImageTransferSpec -- -t "should bound single-sample resolve throughput with distinct pixels and exact pitched guards" -t "should resolve a complete four-sample RGBA8 tile with distinct samples and exact pitched guards" -t "should bound publication throughput for complete RGBA8 and D24S8 tiles" -t "should preserve single-sample and raw resolve bits across formats rows and the final source word" -t "should cancel raw resolve boundary windows and drain held traffic before healthy reuse" -t "should reject resolve bounds permissions output aliases and late source aliases before memory access"'
```

For the comparison, retain this same Test fixture and restore only
`TypedTileImageTransfer.scala` from `1734c580`. Require control 862 clocks and
unchanged traffic, then require candidate savings of at least 192 clocks and
2%; retain the 5662/895/1774 nonregression limits.

The complete production timing command uses the existing emitter and qualifier:

```sh
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-typed-image-transfer .artifacts/identity-resolve-rtl'
NUM_CORES=1 OMP_NUM_THREADS=1 "${CHIPAGENT_DIR:-../chipagent}/.venv/bin/python" scripts/qualify_ppa.py \
  .artifacts/identity-resolve-rtl TypedTileImageTransfer --stage synthesis \
  --synthesis-engine yosys --yosys-preserve-hierarchy --clock clock \
  --period-ps 1000 --io-delay 0.25 --timeout 1200 \
  --output .artifacts/identity-resolve-ppa
```

Apply `--cpus=1 --memory=6g --memory-swap=6g --network=none` to every synthesis,
STA and coverage container. Qualification used CPU1/Xmx2G/host3GiB for emission,
CPU1/Xmx1G/owned2GiB for the native cases, emit180s/qual1200s limits and zero retry.
