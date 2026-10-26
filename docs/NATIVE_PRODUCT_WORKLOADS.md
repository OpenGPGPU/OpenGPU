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

## GPU vertex uploads

`NativeProductVertexInputs` expands every ordered triangle into a non-indexed
GPU triangle list. Its 40-byte interleaved records contain FP32 clip position,
two UV components and four draw-modulation components. The returned native
vertex-input fields select three attributes on one per-vertex binding. Logical
view bounds exclude padding; allocation bounds and uploaded padding cover full
64-byte cache lines. Address checks reject misalignment and uint32 overflow
while permitting an allocation ending exactly at 4 GiB.

The descriptor-driven CPU checks cover every draw of all six 32x32 recipes.
The focused `VertexInputFetcherSpec` case feeds these uploads through the real
RTL fetcher with delayed memory and request/output stalls, comparing emitted
attributes directly with recipe values. All thirteen CPU checks and the one
focused RTL case pass with zero failed, canceled, ignored or pending tests.
This checks vertex transport only;
it does not execute vertex/fragment programs or render the product scenes.

```sh
sbt 'testOnly opengpu.graphics.NativeProductVertexInputsSpec opengpu.graphics.NativeProductWorkloadsSpec opengpu.graphics.NativeProductFrameReferenceSpec'
sbt 'testOnly opengpu.graphics.VertexInputFetcherSpec -- -z "all six product recipes"'
```

## Native vertex program

`NativeProductShaderPrograms.vertex` supplies an executable CU program for the
three-attribute upload ABI: clip position is exported unchanged, UV retains
the fetcher's default Z/W components, and the second varying carries draw RGBA.
The focused `VertexShaderStageSpec` cases submit all six 64x48 recipes through
two shared 16-lane/eight-warp CUs, using both local and spilled payloads with
delayed/reordered memory replies. They compare every exported component with
the recipe and require the small-triangle draw to span multiple CU batches.
This is a vertex-stage fixture, not a full four-CU product qualification.

Both focused checks pass, with zero failed, canceled, ignored or pending tests.
Scratch is disjoint from uploads and reserved shared memory, with explicit
fixture assertions. Full-capacity staging uses a 200,000-cycle correctness
guard; this is not a throughput budget. Elaboration requires `espresso` on
`PATH` to avoid the expensive decoder-minimizer fallback. Raw reports remain
local under `.artifacts/native-product-vertex/`. Reproduce with:

```sh
PATH="$PWD/.artifacts/tools:$PATH" sbt -J-Xmx6G \
  'testOnly opengpu.graphics.VertexShaderStageSpec -- -z "execute product vertex uploads"'
```

## Native fragment program

`NativeProductShaderPrograms.fragment` consumes two varying slots (UV and draw
RGBA) and exports one FP32 color attachment. Solid draws copy modulation;
textured draws capture floating coordinates, sample all four components from
unit zero and multiply by modulation on the CU. Resource descriptors select
the actual 8x8 nearest/repeat RGBA8 checkerboard. Control enables color zero,
without shader discard or depth override; helper suppression remains owned by
the fragment stage. Blending and depth tests belong to the pixel backend.

The focused `FragmentShaderStageSpec` cases use two shared 16-lane/eight-warp
CUs with the FPU enabled, covering local and spilled payloads. They check all
25 recipe draws with independent exact FP32 expectations, including negative
and positive repeat coordinates, helpers, 1x/4x coverage and preserved depth.
Additional submissions check an actual resource-read fault with no exports and
exact healthy reuse. A held export stays stable, mutated submitted descriptor
inputs cannot replace the captured resources, and held completions drain
payload/memory ownership. These are stage-level checks with synthetic quads;
they do not execute workload geometry or validate framebuffer images.
Both focused checks pass: 54 native submissions comprise 52 exact healthy
quads and two expected faults, with zero failed, canceled, ignored or pending
tests. Raw logs remain local under `.artifacts/native-product-fragment/`.
Both existing local/spilled FP32/raw transport regressions also pass, preserving
four-color exports, discard, depth override and held-output behavior.

```sh
PATH="$PWD/.artifacts/tools:$PATH" sbt -J-Xmx6G \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "shade product recipe colors"'
PATH="$PWD/.artifacts/tools:$PATH" sbt -J-Xmx6G \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "deliver FP32 and typed raw fragment quads"'
```

## Complete frame bindings and explicit runner

`NativeProductFrameInputs` builds GPU job/frame/draw records for every recipe,
with the tested vertex/fragment programs, interleaved vertex uploads, one GFS1
state per ordered draw, four separate engine scratch ranges, actual texture
resources, primitive/bin backing and pitched RGBA8/D24S8 images. All records
retain the full published capacities. Regions are disjoint from each other
and reserved shared memory. Only the 4x scene binds a color resolve; depth and
unused resolve storage retain their guards. A fresh frame image contains no
CPU-shaded output. The streamed verifier checks every stored sample, required
resolve and allocation guard against `NativeProductFrameReference`.

Three CPU checks pass, including deliberate sample/depth/resolve/guard
corruption. All fourteen native job-reader checks pass across the regression
and unchanged retry of a Verilator internal build failure. The new reader
case accepts all six jobs and 25 ordered draws through delayed/reordered
descriptor reads and held draw offers. These checks do not render frames.

```sh
sbt 'testOnly opengpu.graphics.NativeProductFrameInputsSpec'
sbt 'testOnly opengpu.graphics.NativeBinnedGraphicsJobReaderSpec'
```

`QualifyNativeProductRecipes` is an opt-in full-host runner, excluded from
routine discovery. It retains four 16-lane/eight-warp CUs, enabled FPU, default
caches, 256-bit AXI, full native storage and both pinned SRAM models. Its single
case must execute all six recipes. Smoke frames use 32x32; product frames use
1280x720 and require the product flag. Each frame resets caches before binding
a new physical input image, so this does not qualify concurrent publication or
Sv32 translation. Completion and accepted AXI drain precede image verification;
per-scene traffic/phase/cycle reports do not imply a qualified clock or FPS.
Full-host execution is pending; compilation and descriptor checks do not prove
frame correctness. Raw logs/reports remain local under `.artifacts/native-product-frame/`.

```sh
git submodule update --init depends/asap7_sram_0p0
PATH="$PWD/.artifacts/tools:$PATH" sbt -J-Xmx6G -J-XX:+ExitOnOutOfMemoryError \
  -Dopengpu.fullHostQualification=1 -Dopengpu.productQualification=0 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit,Fp32PrimitiveRasterEngine,Rgba8BlendFmaEngine \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'Test / runMain opengpu.system.QualifyNativeProductRecipes --smoke --reports .artifacts/native-product-frame/full-host'
```

For product execution, change the product flag to `1` and mode to `--product`.
Require all six per-scene verified-image reports, a passing one-case XML report
and `NATIVE_PRODUCT_RECIPES PASS`; a process exit alone is insufficient.

Remaining qualification work: complete native frame execution and image comparison,
exercise held/reordered memory and faults, run the full product
profile, and measure every frame phase and accepted byte count. Apply the
720p30 deadline separately to all six scenes at a qualified whole-host clock;
720p60 remains the subsequent target. A passing recipe check closes none of
those gates and does not close H6 or the capability matrix.
