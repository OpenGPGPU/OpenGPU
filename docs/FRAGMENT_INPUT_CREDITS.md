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

## Optional phase and public-port observations

The same two matched-CU tests can log existing signals without changing the
hardware harness, shader, reset, service rules or workload. Add these options
to the serial A/B command below, using a new output directory for each run:

```sh
-Dopengpu.fragmentPhaseAudit=1 \
-Dopengpu.fragmentPhaseAuditDir=.artifacts/fragment-credit-phases
```

Each draw writes a phase-transition CSV, a public-port event CSV and a summary
named `<global|resident>-<original|returned>-quads<1|35>`. The observer checks
one-worker phase conservation, per-ID request/reply ownership, held external
offers and complete endpoint drain. All eight original total/phase-1 pairs
must still match exactly. These options add no test cases or regular CI lane.

For this stage, phase 0 is the complement of phases 1–5, including collection,
depth, drain and reporting. Phase 1 is staging/initialization/flush; phase 2
is invalidate/launch; phase 3 is `waitKernel`; phase 4 is readback/metadata/output
preparation; phase 5 is emit/release. `waitKernel` includes launch, execution
and completion waiting. Its clocks do not identify a particular CU, private
cache, resident service or instruction stall. This component has one observed
worker, so each row sums to its draw clocks.

An observer-only replay of the four original native models produced:

| Returned-credit payload | Quads | Phase clocks 0, 1, 2, 3, 4, 5 | Draw clocks |
| --- | ---: | --- | ---: |
| Global | 1 | 20, 2893, 6, 7147, 220, 2 | 10,288 |
| Global | 35 | 97, 12153, 12, 23277, 2553, 37 | 38,129 |
| Resident | 1 | 20, 399, 6, 5821, 149, 7 | 6,402 |
| Resident | 35 | 97, 5411, 12, 12397, 1859, 47 | 19,823 |

For 35 quads, phase 3 occupies 61.05% globally and 62.54% with resident payloads.
Resident phase 3 is unchanged by returned credits: its two intervals remain
6,938 and 5,459 clocks. The first interval accepts 26 lower-memory reads
(25 program-region lines and one uniform line), with an external transaction
owned at cycle entry on 169 clocks. The second interval has no external
requests, replies or outstanding ownership. This establishes the absence of
lower external service during that interval; it does not locate the remaining
internal cause. The observed staging endpoint belongs to the stage's host or
resident management port and does not expose CU resident-payload accesses.

Port events distinguish offers from accepted requests, retain per-ID FIFO
order and record accepted-clock-to-reply latency including response holds.
External program-region reads are lower-memory requests, not all private
instruction-cache accesses or retired instructions. Ownership occupancy is
not a stall count. Reconstruct phase intervals from the transition CSV before
joining them to public-port events; treat each interval as `[start, next)`.

This replay used four natural cache hits, unchanged binary hashes, exact known
original RTL/arguments/classes/JARs and complete new emitted-input captures.
An external compiler-rejecting guard prevented fresh builds. The observer
options themselves do not enforce that cache-only policy. Original support
C++ and `sourceFiles.F` bytes were not retained; the new complete capture does
not reconstruct those missing historical hashes. No new hardware models or
frame-scale runs were used, and these component timings do not establish a
720p or FPS improvement.

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
