# Sample-rate fragment invocation scheduling

Status: standalone integrated component with directed tests on local main. It
is not yet wired into the fragment shader owner; interpolation at the emitted
positions, shader execution and per-sample output merging remain open.

`FragmentSampleShadingScheduler` turns one covered pixel into the fragment
shader invocation sequence required by GLES 3.2 sample shading.

## Contract

- A request carries the pixel coordinate, its `maxSampleCount`-bit coverage
  mask, the sample mode (1x, 2x or 4x), a sample-shading enable and the
  32-bit minimum sample shading value.
- Sample shading is active when the enable is set and the minimum value is
  nonzero. The owner then emits one invocation per covered sample in
  ascending sample order. Each invocation carries the sample id, the
  programmed sample position for that mode (one of the four quarter-pixel
  corners), a mask containing only that sample and `perSample=true`.
- Otherwise it emits one invocation at the pixel centre with sample id zero,
  the complete request mask and `perSample=false`.
- Zero coverage, coverage bits at or above the sample count and reserved
  sample modes are rejected before any invocation. A completion record always
  reports the status and emitted count; invocations and completion are held
  under backpressure and cancellation publishes status 4 after draining.
- The minimum value is consumed only as zero versus nonzero. ES 3.2 uses a
  nonzero minimum to require at least one shading invocation per covered
  sample; fractional granularity between pixel and per-sample shading is not
  scheduled by this block.

## Results

Status is 0 success, 1 invalid coverage or mode, and 4 cancelled. Sample
positions match `Msaa.positions`: `(0,0)` for 1x, `(-1,-1)` and `(1,1)` for
2x, and the four corners in sample-id order for 4x.

## Directed evidence

`FragmentSampleShadingSchedulerSpec` passes three cases covering 58
scheduler configurations:

- every nonzero coverage mask for 1x, 2x and 4x modes, with sample shading
  disabled, enabled with a zero minimum value, and enabled with a nonzero
  minimum value (ordering, sample ids, positions, per-sample masks, emitted
  counts and completion);
- rejection of zero coverage, high coverage bits and a reserved mode without
  emitting an invocation;
- held invocation ownership and cancellation into a drained status-4
  completion.

```sh
sbt 'testOnly opengpu.graphics.FragmentSampleShadingSchedulerSpec'
```

## Remaining work

Fragment-owner integration, interpolation at sample positions, per-sample
output merging, `gl_SampleID`/`gl_SamplePosition` delivery outside the
invocation record, API/compiler exposure and product qualification remain
open.

## Per-sample colour merge

`FragmentSampleColorMergeUnit` resolves four per-sample UNORM8 colours into
one RGBA8 pixel using the covered samples under the active 1/2/4 sample count.
Each channel is summed over covered samples and divided by that count with
round-half-up; uncovered samples are ignored and an empty coverage produces
zero. Noncanonical coverage above the sample count and unsupported counts are
rejected. 300 directed cases plus invalid/empty coverage cover the merge
(`FRAGMENT_SAMPLE_MERGE`).

