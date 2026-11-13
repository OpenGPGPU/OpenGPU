# Primitive numerical setup reuse

Parallel fragment preparation retains one successful numerical setup per private
lane. Its exact key contains primitive kind, the original signed64 area and all
three raw32 vertex depths, including zero signs. It stores the extended ClipFloat
area, depth differences and depth bounds. No work identity, address, varying,
quad location, sample state or ownership reference is retained in the entry.

Every quad still performs the original edge-conservation, metadata, reciprocal,
depth, coverage, sample and offset validation before it can use an entry. Current
metadata and payload are captured independently. Points follow their original
path. Cancellation drains current work; a numerical entry has no task ownership.
Successful logical FP operation/flag accounting includes shared setup once on
both paths. A cancellation at the same clock may observe different progress.

Four raw contexts, four preparation lanes per context, sixteen varying slots,
all field widths and arithmetic precision remain available. The added logical
state is 447 bits per preparation lane, or 7,152 bits for the default four-context
stream. This is a source-level register count, not mapped area or timing.

## Measured component results

A/B uses the same hardware capacity and inputs, with only `reuseSetup` disabled
or enabled. Cold misses preserve the original latency. Successful hits reduce
ordinary triangle preparation from 188 to 156 clocks, the sampled centroid case
from 570 to 538, and line preparation from 177 to 160. Points remain 118 clocks.
The key tests distinguish adjacent large areas that round to the same float,
signed64 minimum, each raw depth, signed zeros and the unused third line depth.

The original 64-component streaming fixture, including its helper lane and
independent output/completion stalls, changes the steady four-quad period from
389 to 357 clocks. Sixteen quads finish in 1,839 instead of 1,935 clocks. The
first four cold outputs retain their original clocks.

A separate real-triangle sweep uses the same maximum 16-slot, four-context DUT.
Each row starts with the same cold reset and renders sixteen quads. Every row
retains all sixty-four output components; inactive components must be zero.

| Active slots | Samples | Flat / centroid mask | Four-quad period, control → reuse |
| --- | --- | --- | --- |
| 1 | 1 | 0 / 0 | 265 → 233 |
| 2 | 1 | 0 / 0 | 269 → 237 |
| 4 | 1 | 0 / 0 | 277 → 245 |
| 8 | 1 | 0 / 0 | 293 → 261 |
| 16 | 1 | 0 / 0 | 325 → 293 |
| 2 | 4 | 0 / 0 | 562 → 530 |
| 1 | 4 | 0 / 1 | 647 → 615 |
| 16 | 4 | 8 / 8000000000000001 | 707 → 675 |
| 2 | 4 | 84 / ff | 650 → 618 |

Masks are hexadecimal. The two smooth slots and zero flat/centroid masks match
the active fields in all six `NativeProductFrameInputs` recipes; the MSAA recipe
uses four samples. These are component stimuli with matching field masks, not
executions of the six product frames.

Each A/B compares the complete ordered quad and completion streams. The
independent arithmetic reference checks every final component and center or
centroid value; sampled rows also check every per-sample depth. The preparation
interface comparison excludes only inactive centroid scratch that its sole
consumer never reads; active centroid values and all other defined fields are
compared exactly. Complete raw offers remain stable while held. The scalar
reference implementation is unchanged, and its complete final quads and FP
completion accounting also match the composed preparation/component path.

Seventeen functional RTL cases pass: exact-key and current-validation checks,
independent instances, cold and warm cancellation at all 570/538 boundaries,
scalar/component and four-context scalar/streaming differentials, and held-head
cancellation followed by four warm-owner healthy replacements without reset.
Both modes of both throughput cases also pass. Validation uses one 2GiB JVM and
one compiler job; no full host model is built for these component checks.

These measurements establish component behavior and clock counts. They do not
qualify a full 640×480 frame, whole-host frequency, mapped PPA or 60 FPS. A final
host containing this RTL needs its own model, image and physical qualification.

## Reproduction

Use one JVM and compiler job. On macOS put `java.io.tmpdir` and `TMPDIR` in a
unique directory below `gpu/.artifacts/` whose absolute path is at most 52 bytes,
to leave room for sbt's socket suffix. Delete temporary build data after use.
For a bounded 2GiB local run, restrict test sources to the relevant fixtures:

```text
set Test / parallelExecution := false
set Test / sources := (Test / sources).value.filter(f => f.getName.matches("(GpuSim|GraphicsTestResolution|Fp32ViewportStageSpec|Fp32FragmentInterpolatorSpec|Fp32TriangleRasterStageSpec|Fp32SampleDepthSpec|PointLineRasterizerSpec|CentroidRasterReference|FragmentSetupCacheSpec|FragmentPreparationInstanceSpec|Fp32FragmentPreparationSpec|Fp32StreamingFragmentInterpolatorSpec)[.]scala"))
```

Pass `-Dopengpu.simJobs=1 -Dopengpu.simLowMem=1
-Dopengpu.simLowMemOpt=1` and the same compiler settings to both variants.
Run each of these selections with
`-Dopengpu.fragmentSetupQualification=control`, then `=cached`; compare the
temporary complete-output and completion transcripts before deleting them:

```text
testOnly opengpu.graphics.Fp32StreamingFragmentInterpolatorSpec -- -z "own four raw quads"
testOnly opengpu.graphics.Fp32StreamingFragmentInterpolatorSpec -- -z "retain full hardware capacity"
```

Functional checks:

```text
testOnly opengpu.graphics.FragmentSetupCacheSpec opengpu.graphics.FragmentPreparationInstanceSpec opengpu.graphics.Fp32FragmentPreparationSpec
testOnly opengpu.graphics.Fp32StreamingFragmentDifferentialSpec -- -z "compose all" -z "centroid masks" -z "earlier component"
testOnly opengpu.graphics.Fp32StreamingFragmentInterpolatorSpec -- -z "one-cycle cancellation" -z "offered head" -z "reset all raw"
```
