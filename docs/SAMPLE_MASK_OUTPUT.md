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

## Remaining work

Transport of the mask through a versioned fragment shader output, complete
`gl_SampleMaskIn`/`gl_SampleMask` variable semantics with per-sample shading,
alpha-to-coverage ordering, API exposure and product qualification remain open.
