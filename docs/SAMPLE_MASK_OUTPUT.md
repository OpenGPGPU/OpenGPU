# Fragment sample mask combination

Status: standalone integrated component with directed tests on local main. It
defines the effective per-sample mask for one fragment invocation; shader
output transport, sample variables and API exposure remain open.

`FragmentSampleMaskUnit` combines the raster coverage (`gl_SampleMaskIn`) with a
shader-written `gl_SampleMask` value.

## Contract

- The sample count must be 1, 2 or 4, and the incoming coverage must have no
  bits at or above that count; otherwise the request is rejected with status 1
  and an empty effective mask.
- The unwritten mask leaves the coverage unchanged. A written mask can only
  clear covered samples: the effective mask is `coverage & shaderMask &
  lowBits`, where bits at or above the sample count are ignored.
- A discarded invocation produces an empty mask.
- `inputMask` reports the canonical coverage (`coverage & lowBits`) and
  `written` reports whether the shader supplied a mask. Results are held under
  backpressure.

## Directed evidence

`FragmentSampleMaskUnitSpec` passes one case covering 4x masks with high bits
ignored, unwritten masks, discard, 2x and 1x masks, and rejection of an invalid
sample count or noncanonical coverage.

```sh
sbt 'testOnly opengpu.graphics.FragmentSampleMaskUnitSpec'
```

## Alpha-to-coverage ordering

`FragmentAlphaToCoverageUnit` converts clamped alpha to the active 1/2/4 sample
count before the sample-mask combine. Thresholds at `(2k-1)/(2N)` round alpha
to `k` samples, which are enabled in ascending order and intersected with the
incoming coverage. NaN, negative and zero alpha enable nothing; alpha at or
above one enables every sample. Disabled conversion passes coverage. 288
directed cases cover all counts and threshold edges (`ALPHA_TO_COVERAGE`).

## Remaining work

Complete `gl_SampleMaskIn`/`gl_SampleMask` variable semantics with per-sample
shading, fragment-owner wiring, API exposure and product qualification remain open.

## Versioned output transport

`FragmentSampleMaskOutputUnit` carries the shader mask in a versioned record.
Version 1 is the only admitted version; unknown versions, unsupported sample
counts and coverage above the active count are rejected. Mask bits above the
count are ignored, discard forces a zero effective mask, and an unwritten mask
passes raster coverage unchanged. One directed case covers version gating,
the written mask, discard, noncanonical coverage/counts and held results
(`SAMPLE_MASK_OUTPUT`).

