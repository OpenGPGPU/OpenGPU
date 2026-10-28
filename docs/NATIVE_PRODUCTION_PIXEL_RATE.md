# Production native pixel rate

`QualifyNativeProductionPixelRate` passes two explicit local hardware cases
against the unchanged emitted H3 pixel backend. Its complete reachable closure
contains 128 SV definitions and sixteen pinned vendor SRAM models. The test
retains all 313 production ports, the eight word ports, 64 quad metadata rows,
128 packet contexts and 32 KiB of canonical attachment data. Metadata arrays,
queues and arithmetic registers are additional storage, not part of that data
SRAM count. The wrapper adds only public wiring; no DUT logic or ports change.

The rate case uses RGBA8 source-alpha blending and D24S8 with enabled depth
writes and stencil updates. An independent reference checks logical counts and
every changed canonical word after a 64-quad warm-up and 128 hot quads per mode.
All 128 hot completions have the required consecutive spacing:

| Samples per pixel | Hot sample updates | Total hot clocks | Complete-quad interval | Sustained sample updates/clock |
|---|---:|---:|---:|---:|
| 1x | 512 | 187 | 1 | 4 |
| 2x | 1,024 | 315 | 2 | 4 |
| 4x | 2,048 | 571 | 4 | 4 |

Total clocks include startup/drain. A separate case stalls public output until
admission stops at 68 quads, holds the oldest result through cancellation,
drains ordered results and exact pass totals, then checks early EOF and healthy
4x reuse. This validates the real SRAM composition separately from the existing
inferred-memory fixtures; their driver, numeric oracle and selected case bodies
are unchanged apart from the DUT adapter type.

These are resident-stream component results. Integrated synthesis, parent
integration, broader formats/state combinations, renderer and memory budgets,
whole-host clock and six actual 720p workloads need their own matching-source
evidence. The two cases alone do not close H3 or establish FPS.

Run deliberately outside PR/nightly lanes, from the repository root:

```sh
git submodule update --init depends/asap7_sram_0p0
python3 scripts/prepare_native_pixel_rate.py emit .artifacts/native-pixel-emission
python3 scripts/prepare_native_pixel_rate.py prepare \
  .artifacts/native-pixel-emission/emission.json .artifacts/native-pixel-model
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.productQualification=0 \
  -Dopengpu.simJobs=1 -Dopengpu.simHierarchy=Rgba8BlendFmaEngine \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.nativePixelRateModel="$PWD/.artifacts/native-pixel-model" \
  'Test / runMain opengpu.graphics.QualifyNativeProductionPixelRate --reports .artifacts/native-pixel-reports'
```

Use fresh output directories and freeze production/build sources, the SRAM pin
and prepared inputs until execution ends. Preparation and execution verify the
source inventory, hashes and complete copied RTL. The result gate requires both
cases with no failures, skips or missing results. Admit emission and simulation
separately; the latter needs native build headroom beyond its 4 GiB JVM heap.
Raw RTL, manifests, compiler outputs, logs and reports remain in `.artifacts/`.
