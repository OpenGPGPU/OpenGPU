# Native product workload inputs

`NativeProductWorkloads` defines six deterministic input recipes for H6. They
provide clip-space vertices, UVs, ordered draws, color modulation, depth/blend
state, an 8x8 nearest/repeat RGBA8 checkerboard, and sample count. They do not
execute hardware or qualify frame rate. The existing mixed-MRT/D32F full-host
smoke remains a separate regression with its original execution/storage profile
and one-million-cycle guard.

All recipes use one RGBA8 color attachment and D24S8, a full-frame viewport,
no face culling, and one clear-start pass. Clear color is transparent black;
depth/stencil clear to 1/0. Enable all color components. Store final color and
depth/stencil; the 4x case also stores a linear RGBA8 resolve. Texture words are
packed with R in bits 7:0, G in 15:8, B in 23:16, A in 31:24. Textured shaders
multiply the sampled RGBA by draw modulation. Depth comparison is LESS, with
writes only on the depth-enabled opaque draws. Blending uses nonpremultiplied
source-over: SRC_ALPHA/ONE_MINUS_SRC_ALPHA for RGB and
ONE/ONE_MINUS_SRC_ALPHA for alpha, ADD equations.

| Recipe | Input workload |
|---|---|
| `desktop-composition` | Textured full-frame background followed by three overlapping textured translucent windows; eight triangles total |
| `textured-3d` | Two overlapping slanted boxes, 24 triangles, depth testing/writes and textured perspective interpolation with W=3/5/7/9 |
| `opaque-overdraw` | Eight full-frame opaque layers in back-to-front order, progressively nearer depth; all layers pass, retaining the cost of useful sample work |
| `transparent-layers` | Eight ordered full-frame layers with alpha 1/4, depth writes off, and distinct source colors |
| `small-triangles` | One two-pixel right triangle per 8x8 region; count is ceil(width/8)*ceil(height/8), including clipped frame-edge regions |
| `msaa-4x` | The same geometry, texture and depth state as `textured-3d`, with four stored samples and a final color resolve |

The workload data is defined at the requested dimensions. Use small sizes for
correctness and at most 640x480 for routine development. The eventual native
runner must require `opengpu.productQualification=1` for 1280x720 execution,
retain all four full 16-lane/eight-warp CUs, FPU and pinned SRAMs, and include
geometry, binning, rendering, transfer and drain. Geometry construction alone
is neither rendered output nor a performance measurement.

## CPU image reference

`NativeProductFrameReference` clips and projects the input triangles, indexes
primitive/clip-part order by tile, and computes stored RGBA8/D24S8 samples plus
the final color resolve. It uses the existing exact rational/FP32 references for
numeric operations and its own sample traversal and ordered pixel storage.
Texture filtering is nearest/repeat, depth is LESS, and enabled blending is
source-over. Stencil stays zero. Reference values never come from DUT output.

The reference keeps geometry and tile indices, which grow with scene size,
and one cached pixel. It allocates no full-frame sample/color/depth array.
Execution is bounded by `GraphicsTestResolution`, including the explicit
product flag for dimensions beyond the development limit.

Eleven CPU checks cover all six 32x32 images, analytical layer colors/depth,
strict equal-depth ordering, a perspective-vs-affine texture probe, negative
repeat coordinates, sloped per-sample depth, partial 4x coverage/resolve, and
odd frame-edge clipping. Pitched source/resolve image checks also reject
injected sample, resolve and padding corruption. The paired box geometry has
diagonal visible edges and varying W, so the small 4x scene actually exercises
mixed coverage rather than aliasing every edge to a pixel boundary.

Reproduce the CPU-only input and image checks:

```sh
sbt -J-Xmx3G -Dopengpu.productQualification=0 \
  'testOnly opengpu.graphics.NativeProductWorkloadsSpec opengpu.graphics.NativeProductFrameReferenceSpec'
```

Remaining qualification work: connect these inputs to GPU-memory descriptors
and actual shader programs, compare native outputs against the image reference,
exercise held/reordered memory and faults, run the full product
profile, and measure every frame phase and accepted byte count. Apply the
720p30 deadline separately to all six scenes at a qualified whole-host clock;
720p60 remains the subsequent target. A passing recipe check closes none of
those gates and does not close H6 or the capability matrix.
