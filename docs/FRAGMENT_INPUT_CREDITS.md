# Returned fragment input credits

A successful input-staging reply can prepare the retiring transaction ID's
next registered request in the same clock. That request is first offered on
the following clock. An existing offer keeps its ID and bits. Faults,
cancellation and readback retain their prior credit policy; both transport
FIFO boundaries remain registered. The two staging IDs, original serial
coalescer, shader capacities and SRAM organization are unchanged.

The local control uses `inputReturnCredit=false`. The default enables the
handoff only during input flush, with a successful owned response and no stop
condition. A cancellation after that clock retains and drains the new offer.

## Matched native component draws

Both variants use two real CUs, 16 lanes × 8 warps, all 64 varying components,
four color outputs and two staging IDs. Stage/System SRAM black boxes use the
pinned ASAP7 32/64-bit macro models. L2 retains its default 256 sets/four ways;
private instruction/vector caches retain 64 sets/two ways. The resident pool
retains 64 KiB. Each cold draw starts after reset and complete drain.

The shader, inputs, masks and service rules match: reordered 5–8-clock lower
memory replies, request backpressure on two of seven clocks, and resident
reply holds on four of thirteen clocks. Every completed work count, accepted
staging byte/mask/order, export, metadata value and stored byte matches.

| Payload | Quads | Original draw | Returned draw | Original phase 1 | Returned phase 1 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Global | 1 | 10,330 | 10,288 | 2,934 | 2,893 |
| Global | 35 | 38,465 | 38,129 | 12,487 | 12,153 |
| Resident | 1 | 6,402 | 6,402 | 400 | 399 |
| Resident | 35 | 19,827 | 19,823 | 5,417 | 5,411 |

Values are simulated clocks. The 35-quad draw reduction is 0.874% globally
and only 0.020% for resident payloads. Phase 1 includes allocation, input and
metadata staging, initialization and flush; it is not just SRAM preparation.
The small resident gain does not establish a materially relieved resident
transport bottleneck. These results do not qualify a whole-host clock,
720p frame improvement, FPS or completion of H6.

`FragmentReturnedCreditQualificationSpec` exercises the real full-capacity
stage with a bounded memory/shared-task peer. Its two static variants prove
next-clock credit reuse, held offers, fault/cancel no-refill, cancellation
after return, full ownership drain, healthy reuse and unchanged readback.
This boundary test uses behavioral SRAM; the four complete-CU A/B models
above separately exercise native SRAM. The new tests remain local-only;
regular CI selects the existing coalescer, ABI and metadata components.
The seven routine stage/ABI/metadata checks, 17 selected existing fault,
cancel, full-capacity, spill, readback and depth checks, and 130 suite-selector
tests also pass.

## Complete native stage timing

The complete 16-lane/eight-warp stage with four shared-CU interfaces, all varying/color
slots, early depth and native SRAM passes synthesis at 1,000 ps, TC/SLVT,
with 25% input/output delays. Core setup slack is +85.642 ps; I/O setup slack
is +71.224 ps. Mapping and constraint coverage both pass. This is component
synthesis timing, before placement/routing; it does not establish a whole-host
clock or frame rate.

Reproduce with the configured ChipAgent/ASAP7 tool environment:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -J-XX:+ExitOnOutOfMemoryError \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-native-early-depth-fragment-shader .artifacts/fragment-credit/rtl'
python3 scripts/qualify_ppa.py .artifacts/fragment-credit/rtl FragmentShaderStage \
  --stage synthesis --synthesis-engine yosys --yosys-preserve-hierarchy \
  --clock clock --period-ps 1000 --io-delay 0.25 --timeout 14400 \
  --output .artifacts/fragment-credit/synthesis
```

Run the local boundary and A/B checks serially:

```sh
git submodule update --init --recursive
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -J-XX:+ExitOnOutOfMemoryError \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.productQualification=0 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.FragmentReturnedCreditQualificationSpec' \
  'testOnly opengpu.graphics.FragmentShaderStageQualificationSpec -- -z "reuse returned input IDs through identical delayed shared-CU traffic and complete draws local=false"' \
  'testOnly opengpu.graphics.FragmentShaderStageQualificationSpec -- -z "reuse returned input IDs through identical delayed shared-CU traffic and complete draws local=true"'
```

The [input coalescing guide](FRAGMENT_INPUT_COALESCING.md) describes the
unchanged buffer and payload ABI. Raw model identities, original runner
reports and intermediate qualification records stay local under `.artifacts/`.
