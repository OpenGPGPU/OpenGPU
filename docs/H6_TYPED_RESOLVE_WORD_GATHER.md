# Typed resolve source-word gather

`TypedTileImageTransfer` now consumes all bytes belonging to the current sample
that are available in its owned 64-bit source word in one cycle. Previously it
consumed one byte per cycle. Each step takes the smaller of the remaining sample
bytes and the bytes before the word boundary. Packed RGB, unaligned records and
samples wider than one word keep their original boundaries.

Address preparation separates the pitch product, origin and byte-offset sums.
Stores prepare their row address during existing local/resolver handshakes,
adding one 32-bit staging register and no store cycles. Cold refills reuse their
existing address register with two additional preparation cycles. A 37-bit
active sample context captures local and row-relative bases, size and raw-copy
mode at the existing pixel boundary. Subsequent samples advance both bases
during their existing transition cycle, keeping view selection and products
out of the source-word comparison and byte-gather path. The full source-word
ownership check remains in place. SRAM state, formats, product capacities,
resolve arithmetic, memory requests, completion and cancellation ownership are
preserved.

The prepared color encoder moves its unchanged binary16 conversion after its
existing preparation register. One added flag identifies half inputs; the
numeric and packing stages retain their valid timing and throughput. This
separates component selection from half rounding, exponent carry and overflow.

## Measured component result

The 8x8 RGBA8/four-sample fixture fills every source word with distinct channel
and sample values, then checks the independent resolve reference, pitched output
and allocation guards. Source storage is disabled for this resolve-only case.

| Measurement | Original | Word gather |
| --- | ---: | ---: |
| Flush cycles | 6814 | 6046 |
| Local 64-bit reads | 128 | 128 |
| External reads | 0 | 0 |
| External writes | 10 | 10 |
| Resolved bytes | 256 | 256 |

The 768-cycle reduction is 11.27%, passing the predeclared minimum 5% reduction.
Images, guards and data traffic agree. Four additional candidate native cases
pass: all typed views with cold/clear/discard sources, held requests and
cancellation/drain/reuse, the 4GiB exclusive destination limit, and combined
stored/resolved line masks and guards. The all-view case covers 36 color views,
seven depth/stencil layouts and three sample modes.

The original 16x16 publication throughput bounds also pass unchanged: RGBA8
takes 895 cycles and D24S8 takes 1774 cycles. These six candidate cases use the
same native component configuration.

The separate prepared-encoder case also passes all 36 formats and 6939 inputs,
including exact UNORM boundaries, integer saturation, sRGB transitions, component
masks, held replies and invalid types. Non-sRGB conversion retains its original
seven-cycle bound.

This is a small component result. It does not establish whole-frame throughput,
whole-GPU frequency, or a proportional speedup for a 720p frame. A 1GHz estimate
means 1 million service cycles per millisecond; it is not physical qualification.

## Native synthesis setup

The complete transfer/resolve component, with unchanged default capacities and
ports, passes native hierarchical SLVT/TC synthesis setup at 1000ps and 25% I/O
budgets. Core slack is +143.459ps and I/O slack is +407.134ps. Mapping and complete
constraint coverage pass, without retiming, operator omissions or module
abstraction. This is synthesis setup, not routed timing or whole-GPU frequency.

## Reproduction

Use the normal native backend, one build job and a bounded JVM. The focused
sources avoid compiling unrelated rendering fixtures:

```sh
JDK_JAVA_OPTIONS='-Xmx2G -XX:ActiveProcessorCount=1 -XX:+ExitOnOutOfMemoryError' \
  sbt -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=0 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simCcache=off \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "TypedColorFormatSpec.scala", "TypedColorBlendSpec.scala", "TypedDepthStencilSpec.scala", "TypedSampleResolveSpec.scala", "TypedTileImageTransferSpec.scala", "PreparedTypedColorEncoderSpec.scala").contains(f.getName))' \
  'testOnly opengpu.graphics.TypedTileImageTransferSpec -- -t "should resolve a complete four-sample RGBA8 tile with distinct samples and exact pitched guards" -t "should resolve all typed views through cold loads virtual clears and packed pitched guarded destinations independently of source stores" -t "should drain held resolve reads private requests stores and cancellation while preserving acknowledged prefixes" -t "should resolve into the highest physical bytes with a four GiB exclusive destination limit" -t "should gather complete stored and resolved lines with exact enabled bytes and allocation guards" -t "should bound publication throughput for complete RGBA8 and D24S8 tiles"' \
  'testOnly opengpu.graphics.PreparedTypedColorEncoderSpec'
```

The original comparison uses the same compiled test fixture and dependencies,
with only the original `TypedTileImageTransfer` Main classes prepended to the
runtime classpath. The original source is from `65dfd2cb`; the candidate is the
word-gather change. Both models use the normal content-derived native cache.
The performance comparison stays outside routine CI and nightly lanes.

Use the configured ChipAgent Python environment for the same component target:

```sh
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-typed-image-transfer .artifacts/typed-transfer-rtl'
NUM_CORES=1 OMP_NUM_THREADS=1 "${CHIPAGENT_DIR:-../chipagent}/.venv/bin/python" scripts/qualify_ppa.py \
  .artifacts/typed-transfer-rtl TypedTileImageTransfer --stage synthesis \
  --synthesis-engine yosys --yosys-preserve-hierarchy --clock clock \
  --period-ps 1000 --io-delay 0.25 --timeout 7200 \
  --output .artifacts/typed-transfer-ppa
```
