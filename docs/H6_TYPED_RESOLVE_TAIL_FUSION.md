# H6 resolve store-tail fusion

`TypedTileImageTransfer` advances to the next resolved pixel when its final
store chunk enters the existing write buffer. This removes one otherwise empty
`resolveStore` cycle per pixel. A partial 16-byte record still returns through
the original upper-word preparation. Row bases, full 128-bit values, formats,
capacities, buffer ownership and acknowledged-byte/pixel credits are unchanged;
the original stop/cancel/drain rules remain in force. No register or SRAM is added.

Two actual no-CU models ran the same frozen Test fixture against control
`3708a35e` and the candidate. Six focused cases per model passed, each with one
testcase and zero skips. The largest fixture is 16x16.

| Fixed probe | Control clocks | Candidate clocks |
| --- | ---: | ---: |
| 8x8 RGBA8, 1x resolve | 670 | 606 |
| 8x8 RGBA8, 4x resolve | 5662 | 5598 |
| 16x16 RGBA8 publication | 895 | 895 |
| 16x16 RGBA8 + D24S8 publication | 1774 | 1774 |

The 1x probe saves 64 clocks (9.55%), passing both fixed gates: at least 64
clocks and 2%. At the assumed 1 GHz, the 1x probe takes 0.670 to 0.606 µs for
this component only. The 4x nonregression gate also passes. Traffic remains 32/128
private word reads for 1x/4x, zero external reads, ten writes and 256 resolved
bytes. Exact image and allocation-guard references cover all 36 formats, raw
integer/depth/stencil modes, nonzero origins, pitched rows, two-chunk 128-bit
records and the final 64-KiB source word. Cancellation sweeps include the final
stencil view and buffered tails; a held write verifies an exact seven-byte owned
prefix while its nine-byte unoffered suffix is discarded before healthy reuse.
Bounds, permissions, aliases, held traffic and faults also pass.

Tests share a 65,536-byte configuration for the source boundary. Complete
production-default 32,768-byte synthesis passes at 1000ps with 25% I/O delays,
ASAP7 SLVT/TC, native SV and the full resolver/encoder/admission hierarchy.
Core setup slack is +135.641ps; I/O setup slack is +400.676ps. All 249 ports,
12-bit local word addresses and nine modules are retained, with zero SRAM
macros, mapping omissions or unresolved operators. Linked STA passes complete
`check_setup` coverage. No retiming, mocks or abstract modules are used. These
are component synthesis estimates; routed whole-GPU timing and frame/FPS gains
remain unqualified.

An initial private wrapper rejected an incorrect host-mount expectation before
container creation, with zero synthesis attempts. The corrected wrapper reused
all 18 unchanged RTL files. One actual synthesis qualification then passed in
three sequential containers, with no retry or re-emission.

Reproduce the focused cases with one native build job:

```sh
JDK_JAVA_OPTIONS='-Xmx2G -XX:ActiveProcessorCount=1 -XX:+ExitOnOutOfMemoryError' \
  sbt -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 -Dopengpu.simCcache=off \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "TypedColorFormatSpec.scala", "TypedColorBlendSpec.scala", "TypedDepthStencilSpec.scala", "TypedSampleResolveSpec.scala", "TypedTileImageTransferSpec.scala").contains(f.getName))' \
  'testOnly opengpu.graphics.TypedTileImageTransferSpec -- -z "single-sample resolve throughput" -z "four-sample RGBA8 tile" -z "publication throughput" -z "single-sample and raw resolve bits" -z "cancel raw resolve boundary windows" -z "reject resolve bounds"'
```

For the comparison, keep this Test fixture and restore only the control Main
file from `3708a35e`. Require control 670/5662 clocks, candidate savings of at
least 64 clocks and 2% for 1x, no 4x regression, and Store limits 895/1774.
The complete production timing target is:

```sh
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-typed-image-transfer .artifacts/tail-resolve-rtl'
NUM_CORES=1 OMP_NUM_THREADS=1 "${CHIPAGENT_DIR:-../chipagent}/.venv/bin/python" scripts/qualify_ppa.py \
  .artifacts/tail-resolve-rtl TypedTileImageTransfer --stage synthesis \
  --synthesis-engine yosys --yosys-preserve-hierarchy --clock clock \
  --period-ps 1000 --io-delay 0.25 --timeout 1200 --output .artifacts/tail-resolve-ppa
```

Apply CPU1, hard 6 GiB memory with equal memory-plus-swap limits, network none,
and single-thread settings to each synthesis, STA and coverage container.
Validation used CPU1/Xmx1G/owned2GiB for native tests, CPU1/Xmx2G/host3GiB for
emission, emit180s/qual1200s/overall1380s limits and zero retries.
