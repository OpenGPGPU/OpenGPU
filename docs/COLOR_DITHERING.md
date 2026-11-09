# Color dithering

`ColorDitherUnit` is the ordered-dither owner for framebuffer color writes
(GLES 3.0 framebuffer operation, capability row F05). Each input component
is fixed-point scaled by 256, so 0..65280 maps to 0..255 and larger values
saturate. When enabled, the unit adds `(bayer+0.5)/16` of one output LSB
from the 4x4 Bayer matrix before truncating to 8 bits:

| | | | |
|---|---|---|---|
| 0 | 8 | 2 | 10 |
| 12 | 4 | 14 | 6 |
| 3 | 11 | 1 | 9 |
| 15 | 7 | 13 | 5 |

The threshold is selected by the low two bits of the window coordinates,
so the pattern repeats every four pixels in each axis. When disabled, the
conversion is plain truncation with no offset. The result carries a
per-component saturated mask for components whose shifted value exceeded
255. Blend/store hookup, the API enable state, and native clear/visibility
interactions remain outside this block.

One directed case covers all sixteen Bayer positions, four-pixel
periodicity, the disabled bypass, saturation at the top of the range and
held results:

```sh
sbt 'testOnly opengpu.graphics.ColorDitherUnitSpec'
```

`COLOR_DITHER bayer=true bypass=true saturate=true held=true`
