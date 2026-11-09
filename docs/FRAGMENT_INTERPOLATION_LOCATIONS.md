# Explicit fragment interpolation locations

`FragmentInterpolationLocationUnit` selects the window-space location used by
`interpolateAtCenter` (the default), `interpolateAtSample`,
`interpolateAtCentroid` and `interpolateAtOffset`.

| Mode | Location | Validity |
|---|---|---|
| Center | The supplied fragment center | Always |
| Sample | The programmed position of the selected sample | Sample index below the 1/2/4 sample count |
| Centroid | The lowest covered sample position (matching `Fp32FragmentInterpolator`) | At least one covered sample under the active count |
| Offset | `center + offset` per axis through the shared clip-float adder | Finite arithmetic result |

The unit masks coverage above the active sample count before choosing the
centroid sample, and reports an arithmetic status when the offset add produces
a malformed or out-of-range result. Varying evaluation at the returned location
and the shader/ISA entry points remain outside this block.

One directed case covers center, sample, centroid, offset, invalid sample
indices, empty coverage and held results:

```sh
sbt 'testOnly opengpu.graphics.FragmentInterpolationLocationUnitSpec'
```

`INTERP_LOCATION center=true sample=true centroid=true offsetAdd=true
invalidMasked=true`
