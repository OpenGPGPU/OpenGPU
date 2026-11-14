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

## Sample variables

`FragmentSampleVariableUnit` publishes `gl_SampleID`, the programmed
`gl_SamplePosition` for that sample and `gl_SampleMaskIn` (incoming coverage
under the active 1/2/4 count). A sample ID at or above the count, a
noncanonical coverage value or an unsupported count is rejected. One directed
case covers all counts, positions and masks (`SAMPLE_VARIABLES`).

## Remaining work

Fragment-owner wiring, complete `gl_SampleMask` write semantics, API exposure
and product qualification remain open.

## Versioned output transport

`FragmentSampleMaskOutputUnit` carries the shader mask in a versioned record.
Version 1 is the only admitted version; unknown versions, unsupported sample
counts and coverage above the active count are rejected. Mask bits above the
count are ignored, discard forces a zero effective mask, and an unwritten mask
passes raster coverage unchanged. One directed case covers version gating,
the written mask, discard, noncanonical coverage/counts and held results
(`SAMPLE_MASK_OUTPUT`).


The default-off native layered source also defines a real versioned shader
readback path in `FragmentShaderStage` for fragment versions6/7; see
[SAMPLE_RATE_SHADING.md](SAMPLE_RATE_SHADING.md#default-off-native-layered-source-implementation).
The complete caller rebuild, RTL tests, protected publication and original ESSL
execution remain pending. Component counts above do not qualify this route.
