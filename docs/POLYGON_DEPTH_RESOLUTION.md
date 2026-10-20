# Candidate polygon depth-resolution policy

This selector supplies a prerequisite for polygon-offset attachment binding.
It is not yet a qualified device-wide minimum resolvable difference: full raster
interpolation precision and the bound render path still require validation.
The floating representation follows GLES 3.0.6 section 3.6.2's dependence on the
primitive's maximum depth exponent, with binary32 subnormal spacing preserved.
[Official specification](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf)

| Bound depth format | Candidate FP32 resolution |
|---|---|
| D16 | `0x37804001` |
| D24, D24S8 | `0x33c00000` |
| D32F, D32FS8 | One binary32 ULP at the largest vertex depth; at least `0x00000001` |
| No depth, stencil only | Valid format, resolution unavailable |
| Reserved format or invalid active vertex depth | Invalid, resolution unavailable |

The fixed constants account for both the rounded FP32 addition and the existing
normalized fixed-depth encoder. A nominal depth-code increment can disappear
after these two rounding steps. At source depth 0.5, the immediately preceding
FP32 resolution loses the positive offset for both D16 and D24. The selected
constants produce a distinct stored code through the actual scalar service and
encoder. This establishes the post-interpolation add/encode envelope, not the
precision of the complete raster pipeline.

The [exhaustive host verifier](../scripts/verify_polygon_depth_resolution.cpp)
checks all 65,536 D16 and 16,777,216 D24 quantization cells. It finds the first and
last FP32 source in each cell and checks positive/negative offsets whose
unrounded sum remains in [0,1]. Monotonic addition and encoding make those the
limiting sources for each sign. The selected constants pass 131,070 and
33,554,429 sign checks respectively, and their immediate predecessors each have
a counterexample. The executable selects nearest/even and uses exact binary64
products of binary32 sources and at most 24-bit integer maxima.

```sh
clang++ -O2 -std=c++17 -ffp-contract=off -frounding-math \
  scripts/verify_polygon_depth_resolution.cpp -o /tmp/verify-polygon-depth-resolution
/tmp/verify-polygon-depth-resolution
```

The [four hardware checks](performance/mobile-polygon-depth-resolution-2026-10-20.json)
pass on 632 unchanged source/ABI files. They cover fixed/stencil format pairs,
3,054 floating cases across all 128 usable exponent fields, mantissa boundaries,
all vertex locations, subnormal and negative-zero handling, 90 malformed active
depth cases, and six actual scalar-add/encode tie cases. Standalone emission
contains one active RTL file, with no arithmetic lane, CU or SRAM.

The attachment owner must snapshot the format and derive resolution from the
post-viewport primitive. Invalid and unavailable bindings are distinct. Further
work must qualify interpolation precision, sample/default/overridden depths,
native descriptor transport, cancellation/reuse and integrated timing/rates.
No polygon-offset API advertisement or H0–H8 gate is closed by this selector.
