# Paired typed resolve samples

`TypedTileImageTransfer` gathers two complete four-byte samples together when
both belong to its current owned 64-bit source word. It requires the full source
tag match, a word-aligned sample start, non-integer color mode and at least two
remaining samples. Both halves are zero-extended into the existing sample
registers, then the transfer advances to the same state reached by the original
two gather/boundary sequences. The registered resolver offer sees both values.
No register, SRAM capacity, format, interface or resolve arithmetic is added.
Integer/depth sample-zero copies, 1x sampling and general packed/wide gathers
retain their existing behavior.

## Measured result

The fixed-latency 8x8 RGBA8/4x fixture uses distinct sample/channel values,
pitched output and an independent exact image/guard reference. Both variants
use the same supported 64 KiB test configuration to include the final source
word in the boundary regressions. Product defaults remain 32 KiB.

| Measurement | Word gather | Paired gather |
| --- | ---: | ---: |
| Total flush cycles | 6046 | 5662 |
| Local 64-bit reads | 128 | 128 |
| External reads | 0 | 0 |
| External writes | 10 | 10 |
| Resolved bytes | 256 | 256 |

The reduction is 384 cycles, or 6.35%, passing the fixed minimum 2% gate.
Each eligible pair replaces four gather/boundary cycles with one. Two pairs
per pixel account for the measured six-cycle saving across all 64 pixels.
The previous 6814-to-6046 minimum 5% gate remains satisfied. The unchanged
16x16 publication bounds also pass: RGBA8 895 cycles and D24S8 1774 cycles.

Eight selected cases pass for each variant, reusing two native no-CU models.
Coverage includes all 36 color formats and three sample modes, cold/clear/
discard sources, packed formats, exact stores/resolves, held requests, faults,
cancellation/drain/reuse, bounds/permissions/aliases and the physical 4 GiB limit.
Additional distinct-input cases cover all five eligible four-byte formats,
signed/unsigned raw sample-zero behavior, the final 64 KiB word, fifteen
cancellation offsets and invalid unaligned slots. Selected fixtures are at
most 16x16; this comparison runs outside routine CI/nightly qualification.

The complete production-default 32 KiB component passes native hierarchical
SLVT/TC synthesis setup at 1000ps and 25% I/O budgets. Core slack is +148.001ps
and I/O slack is +435.956ps. All 249 external ports, the full resolver/encoder/
admission subtree and the 12-bit local word addresses are retained. Mapping has
zero skipped or unresolved operators, and linked STA passes complete
`check_setup` coverage. The component contains no native SRAM macro. The flow
uses CPU1, hard 6 GiB containers, no swap/network, a 1200-second limit and no
retry. These are component synthesis estimates; routed timing, whole-GPU
frequency and frame/FPS gains remain unqualified.

## Reproduction

Use one native build job and the focused Test dependencies:

```sh
JDK_JAVA_OPTIONS='-Xmx2G -XX:ActiveProcessorCount=1 -XX:+ExitOnOutOfMemoryError' \
  sbt -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=0 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simCcache=off \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "TypedColorFormatSpec.scala", "TypedColorBlendSpec.scala", "TypedDepthStencilSpec.scala", "TypedSampleResolveSpec.scala", "TypedTileImageTransferSpec.scala").contains(f.getName))' \
  'testOnly opengpu.graphics.TypedTileImageTransferSpec -- -t "should resolve a complete four-sample RGBA8 tile with distinct samples and exact pitched guards" -t "should resolve all typed views through cold loads virtual clears and packed pitched guarded destinations independently of source stores" -t "should drain held resolve reads private requests stores and cancellation while preserving acknowledged prefixes" -t "should resolve into the highest physical bytes with a four GiB exclusive destination limit" -t "should gather complete stored and resolved lines with exact enabled bytes and allocation guards" -t "should bound publication throughput for complete RGBA8 and D24S8 tiles" -t "should preserve paired sample tails raw modes cancellation and the final 64 KiB source word" -t "should reject resolve bounds permissions output aliases and late source aliases before memory access"'
```

For the control comparison, use this same Test fixture with only
`TypedTileImageTransfer.scala` restored from `eef51bb2`; require exactly 6046
cycles and unchanged traffic, then require the candidate to save at least 2%.
Keep the normal content-derived executable cache and separate variant outputs.

The production timing target uses the existing emitter and qualifier, with
Docker resource limits applied to synthesis, STA and constraint coverage:

```sh
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-typed-image-transfer .artifacts/paired-resolve-rtl'
NUM_CORES=1 OMP_NUM_THREADS=1 "${CHIPAGENT_DIR:-../chipagent}/.venv/bin/python" scripts/qualify_ppa.py \
  .artifacts/paired-resolve-rtl TypedTileImageTransfer --stage synthesis \
  --synthesis-engine yosys --yosys-preserve-hierarchy --clock clock \
  --period-ps 1000 --io-delay 0.25 --timeout 1200 \
  --output .artifacts/paired-resolve-ppa
```

The shared qualifier does not itself bound every Docker invocation. Apply
`--cpus=1 --memory=6g --memory-swap=6g --network=none` and single-thread settings
to each actual container before starting the timing flow.
