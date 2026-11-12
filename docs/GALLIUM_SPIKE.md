# Gallium / Mesa spike (scoped)

Evaluate a thin `pipe_opengpu` against the stable DRM ABI already used by
`userspace/opengpu.h`. This is **not** a full Mesa tree yet: land the mapping
and a representative draw path first, then wire a Mesa submodule only if that
path stays green under `scripts/qualify_functional.sh`.

Pixels come from the OpenGPU RTL under ARTI. Clear, blit, raster, the vertex
shader, and the fragment shader are submitted jobs. The CPU packs the vertex
buffer into the fixed Q16.16 layout and selects a validator-admitted shader
binary. It does not run that shader or rasterize the triangle. llvmpipe,
softpipe, and the Gallium `draw` module stay out of this path. A GL draw
whose shader does not lower onto the shader cores fails.

## First code drop (landed)

Out-of-tree winsys in-tree under `userspace/`:

| Piece | Role |
|---|---|
| `pipe_opengpu.h` / `pipe_opengpu.c` | Gallium-shaped `screen` / `context` / `resource` / `fence`; links only `opengpu.c` |
| `opengpu_fill` | Clear maps to `DRM_IOCTL_OPENGPU_FILL` |
| `examples/pipe_clear_draw` | `clear` + `draw_vbo` smoke (fixed or fragment-tint) |
| `examples/pipe_compute` | `bind_cs` + `launch_grid` smoke (`round_modes`) |
| `examples/pipe_blit` | `clear` + `blit` smoke (1024-byte GEM copy) |
| `examples/pipe_strided_blit` | `invalidate` + 2D strided blit (64×4) |
| `examples/pipe_depth_pass` | persistent depth clear + `DEPTH_LOAD` continuation (FF or tint) |
| `examples/pipe_msaa_draw` | 2x MSAA clear/draw + `resolve` (FF or tint) |
| `examples/pipe_resolve` | CPU-fill 2x MSAA GEM + `resolve` average check |
| `examples/pipe_texture_draw` | `bind_texture` + textured `draw_vbo` (FF or `vtex.sample` FS) |
| `examples/pipe_vertex_draw` | VS/VB bind + `draw_vertex` (vertex+fragment cores) |
| `examples/pipe_present` | mode-sized clear + draw + `pipe_opengpu_present` |
| `examples/pipe_desktop` | GPU-drawn desktop frame (fill, blit, triangle, pointer) + present |

Fixed-function guest apps (shader cores off):

```sh
GPU_FRAG_CORE=0 GPU_USERSPACE_EXAMPLES=1 GPU_USERSPACE_EXAMPLES_ONLY=1 \
  scripts/run_arti_gpu.sh
```

Fragment-core guest apps (tint + pipe clear/draw — preferred “use the GPU”
path under ARTI):

```sh
GPU_USERSPACE_EXAMPLES=1 GPU_USERSPACE_EXAMPLES_ONLY=1 \
  scripts/run_arti_gpu.sh
```

`GPU_PIPE_SPIKE=1` is a compatibility alias for the fragment-core userspace
path. ARTI bring-up commands live in [GRAPHICS_ROADMAP.md](GRAPHICS_ROADMAP.md)
(platform integration).

The Mesa guest build stages the depth/stencil, primitive-restart and framebuffer
blit regressions as `opengpu_gl_*.bin` alongside the DRI libraries. With the
corresponding ARTI modules-ISO staging update and the driver loaded, they can be
run in the guest with:

```sh
export LD_LIBRARY_PATH=/root LIBGL_DRIVERS_PATH=/root
/root/opengpu_gl_depth_stencil.bin both
/root/opengpu_gl_primitive_restart.bin
/root/opengpu_gl_framebuffer_blit.bin
```

The staging path has been checked by cross-compiling these three binaries,
building a modules ISO with a fixture kernel module, and byte-comparing each
binary extracted from the ISO. That packaging check does not boot a guest or
qualify GPU rendering; the separate validation scopes below still apply.
After building the guest output and modules ISO, set `DRIVER_OUTPUT`,
`MODULES_ISO` and an empty `CHECK_DIR` to reproduce the byte comparison:

```sh
for name in depth_stencil primitive_restart framebuffer_blit; do
  xorriso -osirrox on -indev "$MODULES_ISO" \
    -extract "/opengpu_gl_$name.bin" "$CHECK_DIR/opengpu_gl_$name.bin"
  cmp "$DRIVER_OUTPUT/opengpu_gl_$name.bin" "$CHECK_DIR/opengpu_gl_$name.bin"
done
```

Emit a single corpus binary without a full corpus validate:

```sh
python3 scripts/validate_shader_corpus.py --emit fragment_tint /tmp/tint.bin
```

## Gallium driver

`userspace/gallium/` is the out-of-tree pipe. `scripts/stage_mesa_opengpu.sh`
copies it into the pinned Mesa 22.3 checkout at `depends/mesa` and registers
an `opengpu` DRI driver. `draw_vbo` lowers a TGSI colour copy onto `vertex_passthrough.S` and
`fragment_color.S`, a `mat4 * vec4` position onto `vertex_mvp.S`, and a 2D `TEX` onto `fragment_sample.S` (`vtex.sample`
of the interpolated Q16.16 UV, base mip). It packs the fixed vertex,
binds the texture GEM, and submits `OPENGPU_SUBMIT_VERTEX_CORE`. Enabled
blend maps onto draw-record word 35 (GL factors 0–10, one equation per
hardware submission). Different RGB and alpha state uses two ordered
submissions per triangle when both channel groups are writable, with
colour-channel preservation. Source alpha comes from the lowered fragment
output. Constant-colour factors and unsupported
shaders still fail. An enabled scissor is the half-open scanout rectangle in draw-record
words 38 and 39; the rasterizer drops fragments outside it. The screen
reports one viewport, which is what lets Mesa's scissor enable stick. The driver does not call llvmpipe, softpipe, or the Gallium `draw`
module.

Gallium draws require the private legacy vertex renderer's ABI v5
pixel-center capability and select half-integer centers for coverage and
interpolation. Older hardware retains its integer-anchor draw ABI but cannot
run these Gallium draws.

ABI v7 of that renderer also accepts a viewport with signed 16-bit integer
origins, unsigned 16-bit integer dimensions and either Y orientation. An empty
viewport is a no-op. Non-integral or out-of-range transforms are rejected.
On v5/v6 hosts only a
full-target viewport is admitted under the legacy transform; other nonempty
viewports are rejected instead of silently drawing to the full target. The
framebuffer and scissor still bound rasterization independently of the viewport.

## Goals

1. One `pipe_screen` / `pipe_context` that can clear and draw one triangle.
2. Prefer fragment-core builds with the existing tint/corpus shaders; keep
   fixed-function `examples/triangle` as the Bare smoke path.
3. Reuse private-VM isolation and validated shader admission — do not bypass
   the driver validator.

## Non-goals (first spike)

- NIR lowering or texture multi-sampling in the first GL draw. The first draw
  binds a shader the cores already run.
- Shipping inside upstream Mesa; treat this as an out-of-tree winsys + pipe.
- New ISA work driven by Mesa (add ops only when a spike shader needs them).
- Any CPU shader or CPU raster path, including llvmpipe, softpipe, and the
  Gallium `draw` module. See "Debian graphical desktop" in
  [GRAPHICS_ROADMAP.md](GRAPHICS_ROADMAP.md).

KMS present is in-tree via `pipe_opengpu_present` / `examples/pipe_present`
(still no Mesa winsys display integration).

## ABI map

| Gallium / Mesa surface | OpenGPU DRM / userspace |
|---|---|
| `pipe_screen` device open | `opengpu_open` → DRM fd; `opengpu_capabilities` |
| context create/destroy | `opengpu_context_create` / `destroy` |
| resource create/map | `opengpu_buffer_create` (GEM) |
| sampler view / texture | `opengpu_bind` `OPENGPU_RESOURCE_TEXTURE` |
| FS / VS / CS bind | `OPENGPU_RESOURCE_{SHADER,VERTEX_SHADER,COMPUTE_SHADER}` + kernarg slots |
| `pipe_context::draw_vbo` (fixed) | fill `drm_opengpu_draw`, `opengpu_render` |
| `draw_vbo` (vertex core) | `drm_opengpu_vertex_draw` + VB/VS slots |
| private `launch_grid` smoke | `opengpu_compute` |
| Gallium `launch_grid` / four shader buffers | TGSI uint32 compiler → `opengpu_compute_storage` |
| fence | syncobj via `opengpu_sync_*` |
| clear / blit | `DRM_IOCTL_OPENGPU_FILL` / `BLIT` / `STRIDED_BLIT` |
| MSAA resolve | `DRM_IOCTL_OPENGPU_RESOLVE` |
| present / scanout | `pipe_opengpu_present` → `ADDFB2` + `SETCRTC` |
| flush CPU→GPU visibility | line invalidate or uncached resource flags |

Draw-record and kernarg layouts: [HOST_INTERFACE.md](HOST_INTERFACE.md).
Scheduler / GEM ownership: [DRIVER_ARCHITECTURE.md](DRIVER_ARCHITECTURE.md).
Capability bits: `driver/gpu_abi.h` (`GpuAbiLayoutSpec` guards drift).

The bounded production compute callbacks and their CPU-only validation are
documented in [GALLIUM_COMPUTE.md](GALLIUM_COMPUTE.md). Compute API advertising
remains disabled until the complete profile is available.

Gallium buffer copies, buffer clears and single-sample render-target clears use
Mesa's CPU-mapped transfer helpers. Scissored framebuffer clears and rectangular
Z24S8/Z24X8 clears use the same path; a depth-only or stencil-only Z24S8 clear
preserves the other component. Whole-attachment clears can still use GPU fills.
CPU transfers honor resource pitch and publish writes through the existing GPU
cache invalidation path. Each transfer retains its resource until unmap finishes
publication, even if the caller releases its reference first. The Mesa-backed
transfer fixture exercises buffer and 2D texture lifetimes with overlapping maps
and publication failures using mocked GEMs. With this change, the existing
29-marker GLES matrix also passes on the assertion-enabled 64×64 Linux guest;
the early-release lifetime boundary itself is covered by the CPU fixture.
For imported images whose allocation ends within a cache line, write mappings
publish the complete lines covering their mapped rectangle; a write that would
require a line beyond the allocation is rejected before returning a pointer.
Reads of the partial tail remain available. The Mesa-backed transfer fixture
checks both sides of this boundary with mocked storage.
The winsys rejects overflowing or out-of-range fill, blit, strided-blit and
invalidate spans before creating a fence or submitting an ioctl. The kernel
also checks these ranges. Run `python3 scripts/test_pipe_transfer_ranges.py`
to exercise real userspace admission with mocked submissions; it does not run
the GPU. `OPENGPU_DRM_INCLUDE=/path/to/include` selects real DRM UAPI headers
instead of the fixture's compile-only placeholders for unrelated ioctls.
The Gallium blit callback stages single-sample RGBA8/BGRA8/RGBX8/BGRX8
copies through CPU mappings, with nearest or linear color filtering. It also
supports nearest-filtered Z24S8/Z24X8 depth/stencil copies with separate aspect
masks. It handles scaling, clipped image bounds and scissor, independent
horizontal/vertical source flips, color masks, format order and overlapping
resources by snapshotting the source before writing the destination. The
original rectangle determines the pixel-centre mapping even after clipping.
Writes use the same publication path as other mapped transfers. Multisample
copies, window rectangles and alpha blending remain unsupported; the driver
reports such requests, and the void Gallium callback cannot return an API
error. These copies are not a GPU-rate claim and general cross-context
coherence remains open.
Bound depth attachments are loaded on subsequent draws.
Depth-enabled draws require the clip-depth driver feature and calculate D24
from post-vertex-shader, post-clipping Z/W. ABI v8 also accepts programmable
near and far endpoints, including reversed and equal ranges; the driver
quantizes the clamped endpoints to D24 before interpolation. Older hosts
reject non-default ranges rather than rendering an unrelated depth. The
legacy explicit-depth command is unchanged.
The legacy render descriptor has one colour/depth row stride; if the attachment
strides differ, each synchronous draw copies the depth rectangle into a temporary
plane with the colour stride and copies it back after completion. The fallback
uses CPU mappings and invalidation fences and may be slow; a failed submission
or wait does not copy the temporary back, while a failed invalidation after a
CPU copy reports an error after the destination bytes were changed. General
cross-context CPU/GPU coherence remains open. The single-sample attachment layout
does not change with the rasterizer multisample enable. These paths cover only
the buffer and 2D resources that this driver can allocate; they do not qualify
native image transfers, compressed formats or multisample copies. The Linux query
ioctl returns `EOPNOTSUPP` and Gallium rejects query creation until hardware
commands and result ownership are connected. Component query tests do not
establish API query support.

The legacy output merger writes all colour channels, so Gallium preserves
disabled colour-mask channels in CPU-mapped storage around synchronous draws.
When blending is enabled, overlapping triangles are submitted separately so
each sees the preserved destination channels. This fallback covers RGBA8,
BGRA8, RGBX8 and BGRX8 surfaces whose view and resource formats and extents
match. It scans the framebuffer and can be slow, especially for blended draws;
it does not establish cross-context atomicity. An invalidation failure after
the CPU restoration reports an error after the destination bytes changed.

For separate RGB/alpha blend state, the RGB pass preserves destination alpha
and suppresses depth/stencil writes; the alpha pass uses the original tests and
performs their writes once. The next triangle starts only after both passes.
This also supplies the required alpha multiplier of one for
`SRC_ALPHA_SATURATE` on older mergers, following
[OpenGL ES 2.0 §4.1.6, table 4.2](https://registry.khronos.org/OpenGL/specs/es/2.0/es_full_spec_2.0.pdf).
The fallback inherits the colour-mask layout and coherence limits above; a
failure in its second pass can leave the first pass's RGB changes visible.
Constant blend factors, broader shader behavior and full API conformance remain
open.

`python3 scripts/test_query_unsupported.py` tests the actual ioctl's rejection.
With Mesa 22.3.6 and DRM UAPI headers available, run
`OPENGPU_MESA_DIR=/path/to/mesa OPENGPU_DRM_INCLUDE=/path/to/include python3 scripts/test_gallium_transfers.py`.
The latter checks scissored colour/depth/stencil clears, aspect preservation,
depth binding and staged copies for mismatched pitches with the actual Mesa
helpers and OpenGPU callbacks, mocked GEMs, GPU submission, cache invalidation
and small format-pack stubs. It also checks colour and Z24S8/Z24X8 blits,
including nearest/linear colour filtering, separate depth/stencil masks, flips,
scaling, clipping, pitches, large signed coordinates, overlapping backing and
rejected mappings,
and checks that the real winsys constructs the
depth-load submission flag; it does not execute a GPU or validate a Linux guest.
The fixture also passes with
`CC='clang -fsanitize=address,undefined -fno-omit-frame-pointer
-ftrivial-auto-var-init=pattern'`.
It additionally checks 2,196,150 encoded blend factor/equation/mask
combinations against an independent component reference, alongside ordered
passes, overlap, depth/stencil write suppression and failure handling. The
`OPENGPU_GL_SEPARATE_BLEND=1` depth/stencil exercise passed with both texture
and renderbuffer 8×8 targets in a 64×64, one-CU, assertion-enabled FlashSim
Linux guest (507 RTL assertions), and against llvmpipe. It covers separate
factors/equations, masked channels, overlapping triangles, depth/stencil
updates and alpha saturation; it is not a full API or rate claim. Run it in
the staged guest with:

```sh
LD_LIBRARY_PATH=/root LIBGL_DRIVERS_PATH=/root OPENGPU_GL_SEPARATE_BLEND=1 \
  /root/opengpu_gl_depth_stencil.bin both
```

The 8x8 `opengpu_gl_framebuffer_blit` exercise covers eight scaled, flipped,
clipped and pixel-boundary color copies from a GPU-cleared and partially
CPU-updated source. Five more cases check linear enlargement, reduction,
flipping, scissoring and source/destination boundaries against a bilinear
reference; filtered RGB allows a difference of three 8-bit values while
alpha and untouched guard pixels match exactly. Its depth/stencil cases
separately copy depth, stencil,
and both aspects between Z24S8 renderbuffers with a scissored destination; full
color readback after depth and stencil tests checks both copied and preserved
values. These cases pass on a one-CU Linux guest with 507 RTL assertions and
on Mesa llvmpipe. The driver uses CPU-mapped, single-sample copies with
nearest or linear colour filtering and nearest depth/stencil filtering. Linear
taps clamp to the clipped source rectangle, one of the choices allowed by the
[OpenGL ES blit rules](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf).
This does not establish general format, multisample,
cross-context or H6 conformance.
The OpenGPU guest test uses `MESA_GLES_VERSION_OVERRIDE=3.0` solely to reach
these GLES calls; the driver still advertises GLES 2 and this is not GLES 3
conformance or a throughput result. After `scripts/build_mesa_opengpu.sh`,
the bounded guest invocation is:

```sh
MESA_GLES_VERSION_OVERRIDE=3.0 opengpu_gl_framebuffer_blit
```

Set `OPENGPU_GL_DEPTH_BLIT_ONLY=1` or `OPENGPU_GL_LINEAR_BLIT_ONLY=1` to run
only the depth/stencil or linear-colour cases respectively.

For a software reference on Linux with EGL, GLES and GBM development headers:

```sh
cc -std=c11 -O2 -Wall -Wextra -Werror userspace/examples/gl_framebuffer_blit.c \
  -o /tmp/opengpu_gl_framebuffer_blit -lEGL -lGLESv2 -lgbm -lm
LIBGL_ALWAYS_SOFTWARE=1 OPENGPU_GL_SURFACELESS=1 OPENGPU_GL_ALLOW_SOFTWARE=1 \
  /tmp/opengpu_gl_framebuffer_blit
```

Gallium depth and stencil comparisons now translate all eight Mesa functions to
the hardware comparator order; stencil operations translate Mesa's invert and
wrap values separately. Both Linux draw validators admit the complete 3-bit
depth-function range. The formerly incorrect `GPU_DEPTH_FUNC_ALWAYS` C constant
now equals the existing hardware ALWAYS encoding (6); raw command values retain
their hardware meaning. The Mesa-backed transfer fixture checks the mappings,
including disabled stencil state. `python3 scripts/test_render_compare_validation.py`
checks both actual draw validators with modeled bindings. These CPU fixtures do
not execute the RTL or a guest.

The transfer fixture also checks every colour mask and supported byte order,
staged depth together with colour preservation, destination-alpha-dependent
overlap using a mocked GPU, and injected publication failures. These are CPU
and orchestration checks, not a GPU render.

Legacy position, colour and texture-coordinate attributes use the same Mesa
plain-format unpacking path. It supports 90 scalar/vector and packed formats,
including normalized, scaled, fixed, half and float data; pure integer values
are converted to the legacy floating-point shader inputs. Missing components
use Mesa's defaults and signed-normalized minimum values clamp to -1. Resource
fetches use the actual element byte size and check both the logical buffer and
GEM size; user pointers remain the caller's responsibility. Integer shader
inputs, instanced attributes and complete API coverage remain open.

With a matching native Mesa 22.3.6 build and DRM UAPI headers, the real Mesa
format-library fixture checks all 90 admitted formats, exact buffer boundaries,
unaligned/short elements and representative vertex packing:

```sh
OPENGPU_MESA_DIR=/path/to/mesa-22.3.6 \
OPENGPU_MESA_BUILD=/path/to/native-mesa-build \
OPENGPU_DRM_INCLUDE=/path/to/include \
python3 scripts/test_gallium_vertex_formats.py
```

The fixture passes with mocked GEMs and with ASan/UBSan; it does not execute a
GPU or establish GL conformance.

Vertex-buffer rebinding acquires incoming resource references before releasing
old slots, including cross-slot aliases. A NULL input unbinds the requested
slots, ownership transfer is retained, and out-of-range bindings are rejected
without unsigned wraparound. The Mesa-header CPU fixture exercises these cases
with reference-counted fake resources:

```sh
OPENGPU_MESA_DIR=/path/to/mesa-22.3.6 OPENGPU_DRM_INCLUDE=/path/to/include \
python3 scripts/test_gallium_vertex_bindings.py
```

`userspace/examples/gl_depth_stencil.c` is an 8x8 GLES2 integration test for
both texture and renderbuffer colour attachments. It checks depth/stencil
preservation across clears and draws, scissored depth/stencil/colour clears,
and pixel readback. `scripts/build_mesa_opengpu.sh` builds it as
`$PREFIX/bin/opengpu_gl_depth_stencil`; on an OpenGPU guest run
`opengpu_gl_depth_stencil both` (or `texture` or `renderbuffer`). It requires
an OpenGPU renderer by default. The base test and all the optional matrices
below passed both attachment types in a Linux guest on an assertion-enabled
FlashSim model of the 64x64, one-CU, four-lane/two-warp legacy host. This is
an 8x8 API/resource check, not physical hardware or H6 qualification.
Set `OPENGPU_GL_COMPARE_MATRIX=1` to additionally test every depth and stencil
comparison against less/equal/greater inputs and all eight stencil operations.
Set `OPENGPU_GL_CLIP_DEPTH=1` to check vertex-shader Z transforms, varying W,
and a triangle crossing the near plane against depth comparisons.
Set `OPENGPU_GL_COLOR_MASK_MATRIX=1` to also check all sixteen colour write
masks and two overlapping triangles blended with the destination alpha.
Set `OPENGPU_GL_VERTEX_FORMAT_MATRIX=1` to check normalized, scaled and
fixed-point position/colour attributes through GLES.
Set `OPENGPU_GL_TRIANGLE_TAILS=1` to check arrays and indexed triangle draws
with zero through five vertices: incomplete tails must not discard completed
triangles.
Set `OPENGPU_GL_TRIANGLE_TOPOLOGIES=1` to check triangle strips and fans,
including odd array starts, nonzero index offsets, 8/16-bit indices, short
draws, winding and culling. A longer strip crosses the small guest's upload
batch boundary. The driver expands strips and fans into triangle-list uploads
and compensates for Mesa's framebuffer orientation when selecting cull mode;
it does not support general instancing in this path.
These cases pass on both 8x8 FBO attachment types and the default window in
the assertion-enabled 64x64 OpenGPU guest and on the llvmpipe software oracle.
They do not establish general primitive or H6 conformance. Run them with
`OPENGPU_GL_TRIANGLE_TOPOLOGIES=1 opengpu_gl_depth_stencil both`.
`opengpu_gl_primitive_restart` exercises desktop GL's arbitrary restart index
on indexed triangle lists, strips and fans. It uses 8/16/32-bit indices,
nonzero index offsets, repeated restart markers and back-face culling on both
8x8 texture and renderbuffer attachments. The cases passed on a software GL
oracle and an assertion-enabled Linux/FlashSim guest (64x64, one CU, 507 RTL
assertions). Mesa's restart-mode capability is limited to the supported
triangle modes; the driver splits at the raw restart value before applying
index bias, then expands each run independently. This does not establish full
desktop GL or H6 conformance. Build with `scripts/build_mesa_opengpu.sh` and
run `opengpu_gl_primitive_restart` on the OpenGPU guest.

`OPENGPU_GL_RESTART_COMPOSED=1 opengpu_gl_primitive_restart` also combines a
restarted indexed strip, back-face culling, an offset viewport, scissor and
three non-default depth ranges. It checks every pixel after a passing draw, a
depth-rejected draw and a closer passing draw, on both texture and renderbuffer
attachments with D24S8. The combined cases passed on llvmpipe and on the
64x64 one-CU ABI v8 Linux/FlashSim guest with 507 RTL assertions. Set
`OPENGPU_GL_RESTART_COMPOSED_ONLY=1` to run only these combined cases. They
cover 8x8 API behavior, not full H6 or 720p qualification. The default restart
fixture also passed on an ABI v7 Linux/FlashSim guest.

Set `OPENGPU_GL_VIEWPORT_MATRIX=1` to check offset, clipped, empty and
larger-than-target viewports, scissor intersections, Y orientation, culling
and `gl_FragCoord`. The pixel checks passed on both 8x8 FBO attachment types and
the 32x32 window in an assertion-enabled ABI v7 guest (64x64, one CU, 507 RTL
assertions) and on the llvmpipe oracle. They cover this bounded path, not the
full H6 API or 720p qualification. The existing full-target graphics matrix
also passed on an ABI v6 guest with the new driver. Run the guest cases with
`OPENGPU_GL_VIEWPORT_MATRIX=1 opengpu_gl_depth_stencil both`. To exercise
viewport admission and command packing without a GPU, run:

```sh
OPENGPU_MESA_DIR=/path/to/mesa-22.3.6 OPENGPU_DRM_INCLUDE=/path/to/include \
  python3 scripts/test_gallium_transfers.py
python3 scripts/test_render_compare_validation.py
python3 scripts/test_compute_feature_query.py
sbt -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'testOnly opengpu.graphics.PreparedGeometryStageSpec opengpu.graphics.CommandBufferStageSpec'
```
Set `OPENGPU_GL_DEPTH_RANGE_MATRIX=1` to check forward, reversed, equal and
clamped ranges at an offset viewport using depth comparisons away from D24
rounding boundaries. Nine cases passed on each 8x8 FBO attachment type in an
assertion-enabled ABI v8 guest (64x64, one CU, 507 RTL assertions) and on the
llvmpipe oracle; the existing graphics matrices also passed in that guest.
These checks do not establish full H6 conformance or physical timing. Run with
`OPENGPU_GL_DEPTH_RANGE_MATRIX=1 opengpu_gl_depth_stencil both`. The depth
converter and draw-record admission also have focused tests:

```sh
OPENGPU_MESA_DIR=/path/to/mesa-22.3.6 OPENGPU_DRM_INCLUDE=/path/to/include \
  python3 scripts/test_gallium_transfers.py
python3 scripts/test_render_compare_validation.py
python3 scripts/test_compute_feature_query.py
sbt -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'testOnly opengpu.graphics.ClipDepthStageSpec opengpu.graphics.CommandBufferStageSpec'
```

Set `OPENGPU_GL_SAMPLE_FORMATS=1` to sample GLES2 luminance, alpha,
luminance-alpha, RGB and RGBA textures into each attachment type.
Set `OPENGPU_GL_TEXTURE_RESOURCES=1` to check 2x2 RGBA texels and a subimage
update, linear interpolation, RGB uploads with padded rows, and clearing then
sampling both rows of a 2x2 texture. The interpolation checks allow two UNORM8
levels of precision. Set `OPENGPU_GL_TEXTURE_NEAREST=1` as well to switch
between nearest and linear filtering, check a nearest-texel boundary, and
check perspective interpolation where the GLES pixel center and integer
pixel anchor select different texels.
Set `OPENGPU_GL_TEXTURE_COPIES=1` alongside `OPENGPU_GL_TEXTURE_RESOURCES=1`
to copy a drawn and partially cleared framebuffer into a texture, verify a
subrectangle and its untouched border, and read back padded rows. Both texture
and renderbuffer attachments pass in the assertion-enabled 64x64 OpenGPU guest
and on the llvmpipe oracle. Adding `OPENGPU_GL_TEXTURE_COPY_WINDOW=1` also passes
on the default EGL surface (32x32 in that guest), including its reversed source
rows. This is a bounded resource check, not complete blit or H6 API conformance.
These cases passed both attachment types in an assertion-enabled 64x64
OpenGPU guest (507 bound RTL assertions) and on the llvmpipe software oracle.
The private legacy host advertises nearest filtering with ABI v4; older hosts
reject nearest requests. Gallium requires matching minification and
magnification filters and uses base-level sampling. General mip semantics
remain unqualified.
The guest invocation for all of these cases is:

```sh
OPENGPU_GL_COMPARE_MATRIX=1 OPENGPU_GL_CLIP_DEPTH=1 OPENGPU_GL_COLOR_MASK_MATRIX=1 \
  OPENGPU_GL_VERTEX_FORMAT_MATRIX=1 OPENGPU_GL_TRIANGLE_TAILS=1 OPENGPU_GL_SAMPLE_FORMATS=1 \
  OPENGPU_GL_TEXTURE_RESOURCES=1 OPENGPU_GL_TEXTURE_NEAREST=1 \
  opengpu_gl_depth_stencil both
```

The test oracle and clip-depth matrix passed both attachment cases on Mesa
llvmpipe, as did the comparison/operation, colour-mask, vertex-format and
triangle-tail matrices. To reproduce the software-only check on Linux with EGL,
GLES2 and GBM development libraries:

```sh
cc -std=c11 -O2 -Wall -Wextra -Werror userspace/examples/gl_depth_stencil.c \
  -o /tmp/opengpu_gl_depth_stencil -lEGL -lGLESv2 -lgbm
LIBGL_ALWAYS_SOFTWARE=1 OPENGPU_GL_SURFACELESS=1 OPENGPU_GL_ALLOW_SOFTWARE=1 \
  OPENGPU_GL_COMPARE_MATRIX=1 OPENGPU_GL_CLIP_DEPTH=1 OPENGPU_GL_COLOR_MASK_MATRIX=1 \
  OPENGPU_GL_VERTEX_FORMAT_MATRIX=1 OPENGPU_GL_TRIANGLE_TAILS=1 OPENGPU_GL_SAMPLE_FORMATS=1 \
  OPENGPU_GL_TEXTURE_RESOURCES=1 OPENGPU_GL_TEXTURE_NEAREST=1 \
  /tmp/opengpu_gl_depth_stencil both
```

`sbt 'testOnly opengpu.graphics.ClipDepthStageSpec opengpu.graphics.CommandBufferStageSpec opengpu.graphics.RenderPipelineSpec'`
checks D24 conversion, vertex-format decoding and the legacy raster pipeline.
`python3 scripts/test_render_compare_validation.py` checks format admission and
the capability matrix. These component tests do not establish H6 conformance.
`sbt 'testOnly opengpu.graphics.RasterizerPixelCenterSpec opengpu.graphics.CommandBufferStageSpec opengpu.graphics.KernelVertIndexWidthSpec opengpu.graphics.RasterFp32InterpolationSpec'`
checks pixel-center coverage and edge values in single-pixel and quad modes,
per-draw ownership, command decoding, and the existing FP32 path. Both
attachment types also passed the perspective center case in the bounded guest
and llvmpipe runs above.
`sbt 'testOnly opengpu.graphics.TextureUnitSpec opengpu.graphics.TextureCoordinatePipelineSpec opengpu.graphics.TexSampleNearestSpec'`
checks nearest coordinates, boundaries and mode ownership across vector lanes.
With Mesa and DRM headers, `python3 scripts/test_gallium_transfers.py` checks
sampler-state binding and old-hardware rejection.

For normalized 2D coordinates, the legacy sampler selects repeat or
clamp-to-edge independently for S and T when the host advertises ABI v6's
sampler-axis capability. Older hosts accept these modes only when both axes
match. On each repeat axis, triangles translate all three vertices by the same
whole number of periods before interpolation, so negative coordinates are not
wrapped independently at each vertex. Mirror/border modes, unnormalized
coordinates and negative clamp-to-edge coordinates are rejected. Coordinates
must fit signed Q16.16; this does not qualify general sampler or mipmap
behavior.

The repeat matrix passes on llvmpipe and an assertion-enabled 64×64 OpenGPU
guest with one CU, four lanes, two warps and 507 checks. Both texture and
renderbuffer attachments exercise positive and negative repeat, interpolation
across zero with equal and differing clip W, the linear-filter seam and
switching back to clamp. The same run also passes the existing depth, stencil,
color-mask and texture-resource
matrices. Reproduce the GLES cases with
`OPENGPU_GL_TEXTURE_RESOURCES=1 OPENGPU_GL_TEXTURE_NEAREST=1 OPENGPU_GL_TEXTURE_REPEAT=1 opengpu_gl_depth_stencil both`
and the callback tests with `python3 scripts/test_gallium_transfers.py` using
the Mesa and DRM headers described below. This bounded guest run is not broad
API conformance or a performance result.

`OPENGPU_GL_TEXTURE_AXIS_WRAP=1` adds mixed repeat/clamp cases for both axis
orders with nearest and linear filtering, including a negative repeat
coordinate. Both attachment types pass on llvmpipe and on the assertion-enabled
64×64 OpenGPU guest described above. The latter completes all 29 guest markers
with 507 bound RTL assertions, including the existing depth, stencil and texture
resource matrices. Reproduce the GLES cases with
`OPENGPU_GL_TEXTURE_RESOURCES=1 OPENGPU_GL_TEXTURE_NEAREST=1 OPENGPU_GL_TEXTURE_REPEAT=1 OPENGPU_GL_TEXTURE_AXIS_WRAP=1 opengpu_gl_depth_stencil both`.
The independent axis pipeline and texture-unit component tests can be reproduced with
`sbt 'testOnly opengpu.graphics.TextureAxisWrapSpec opengpu.graphics.TextureUnitSpec opengpu.graphics.TexSampleNearestSpec opengpu.graphics.TextureCoordinatePipelineSpec opengpu.graphics.CommandBufferStageSpec opengpu.graphics.KernelVertIndexWidthSpec'`;
the Mesa-header callback fixture also checks capability and older-host
rejection via `python3 scripts/test_gallium_transfers.py`.

Texture coordinates preserve the V value supplied by the vertex input; the
sampler and the CPU upload path both address the first resource row at V=0.
The former unconditional V inversion also affected ordinary GL textures.
An additional bounded guest test maps a 2×2 GBM image, exports its DMA-BUF,
imports it through `EGL_EXT_image_dma_buf_import`, and samples four distinct
texels after a producer update and release of the source BO, fd and EGLImage.
It passes with both texture and renderbuffer destinations on the
assertion-enabled 64×64 Linux guest with 507 checks. Run it with
`OPENGPU_GL_DMABUF=1 opengpu_gl_depth_stencil both`. This checks a linear
OpenGPU-produced image; external producers, modifiers, general cross-context
coherence and Weston desktop orientation still require separate coverage.
Complete color clears whose logical pitched span is not a whole cache line use
the mapped clear path, preserving allocation padding and publishing the write.
Aligned full clears keep the GPU fill path. Reproduce these checks with:

```sh
OPENGPU_GL_TEXTURE_RESOURCES=1 opengpu_gl_depth_stencil both
OPENGPU_MESA_DIR=/path/to/mesa-22.3.6 OPENGPU_DRM_INCLUDE=/path/to/include \
  python3 scripts/test_gallium_transfers.py
OPENGPU_MESA_DIR=/path/to/mesa-22.3.6 OPENGPU_MESA_BUILD=/path/to/native-mesa-build \
  OPENGPU_DRM_INCLUDE=/path/to/include python3 scripts/test_gallium_vertex_formats.py
```

Fragment sampler views can select any source channel, zero or one. A
non-identity view synchronizes its source and stages the selected components
as tightly packed RGBA before a draw; identity views retain the existing fast
path. This CPU copy is per draw and is not a product-rate claim. The transfer
fixture exercises actual Gallium view creation, staging, bounds, synchronization
and failure handling with mocked GEMs. Independent byte and bilinear-filter
references cover all 1,296 selector combinations for RGBA, BGRA, RGBX and
BGRX storage. The GLES2 base-format matrix passes on Mesa llvmpipe and the
bounded assertion-enabled OpenGPU guest described above; broader sampling
and real hardware remain unqualified.

```sh
python3 scripts/test_gallium_transfers.py
CC='clang -fsanitize=address,undefined -fno-omit-frame-pointer' \
  python3 scripts/test_gallium_transfers.py
python3 scripts/test_driver.py
```

Gallium resource creation and import admit only one level, one layer and
single-sample storage. Image extents are checked before 64-byte rounding, and
resource parameter queries reject nonzero levels and layers. With the same
Mesa and DRM headers, `python3 scripts/test_gallium_resource_shapes.py` checks
the actual callbacks with mocked GEM allocation, including overflow and shape
rejection; it does not establish GPU resource or API conformance.
Combined render, depth, sampler and vertex bindings require a compatible
format for each requested use. Depth sampling, rectangle sampler views and
non-buffer shader image views remain unsupported; creation and import reject
those bindings.

Graphics constant-buffer snapshots check the screen, buffer target, logical
range and GEM capacity before reading. User-memory offsets are applied to the
snapshot, and transferred resource references are released on both success and
failure. With Mesa 22.3.6 and DRM headers, the real Gallium callback is tested
using mocked mappings and reference counts:

```sh
python3 scripts/test_gallium_graphics_constants.py
CC='clang -fsanitize=address,undefined -fno-omit-frame-pointer' \
  python3 scripts/test_gallium_graphics_constants.py
```

These tests do not exercise a GPU draw or establish API conformance.

Indexed draws bound GPU reads by both the logical Gallium buffer and its GEM
allocation, decode unaligned 8/16/32-bit indices without typed pointer access,
and release a transferred index-buffer reference after the draw callback.
Direct draws with zero instances perform no submission and still release any
transferred index-buffer reference. General instanced draws remain unsupported.
The Mesa-header fixture tests these helpers, including zero-length buffers,
negative/overflowing base-vertex values, restart segmentation and reference
ownership, triangle expansion offsets and cull state selection, with mocked
mappings:

```sh
python3 scripts/test_gallium_index_buffers.py
CC='clang -fsanitize=address,undefined -fno-omit-frame-pointer' \
  python3 scripts/test_gallium_index_buffers.py
```

The fixture itself is a CPU test; the bounded guest triangle-tail matrix above
also exercises indexed GLES draws but is not broad index-buffer conformance.
The lower-level linear image allocator checks the pixel product before
multiplying by four, rejects logical byte sizes above `0x7fffffff`, and rounds
the backing allocation up to a 64-byte cache line. The logical image rows stay
tightly packed. This permits synchronization even for a 1x1 texture; imported
images without a complete final cache line cannot use the CPU staging path.
`python3 scripts/test_linear_image_allocation.py` checks its real allocation
path with mocked storage, including an overflowing 64-bit byte product; it
does not allocate or render a large image.

The legacy unified render descriptor carries one colour address. Gallium
advertises one render target, and the Linux render ioctl rejects additional
colour handles until the native multi-attachment path owns and publishes all
targets. `python3 scripts/test_render_attachment_admission.py` exercises the
actual admission and cleanup code with mocked GEM objects; the Mesa-backed
resource fixture checks the advertised limit. These are CPU tests, not an
MRT draw or an H6 API qualification.
The fixture passes with `CC='clang -fsanitize=address,undefined
-fno-omit-frame-pointer -ftrivial-auto-var-init=pattern'`; the pre-fix
single-target admission dereferenced an uninitialized object under that check.
A configured ARM64 kernel builds the driver with `make -C "$LINUX_BUILD"
M="$PWD/driver" ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- modules`.
The resource and transfer fixtures also pass with Linux GCC and the same Mesa
headers; they still use mocked GEMs and do not execute a guest or GPU.

## Next

1. Keep the pipe path on the same ioctl + validator rules as
   `examples/triangle` / `fragment_tint` / `opengpu_drm_test`.
2. Optional: real Mesa `pipe_opengpu` that calls this winsys (or inlines it)
   only if NIR/winsys bootstrap stays smaller than growing
   `userspace/examples/`.
   The Debian desktop that this winsys has to serve, and what the shader
   sandbox still rejects, is under "Debian graphical desktop" in
   [GRAPHICS_ROADMAP.md](GRAPHICS_ROADMAP.md).
3. Gate: guest draw succeeds; `scripts/qualify_functional.sh` still green
   (userspace apps are opt-in via `GPU_USERSPACE_EXAMPLES`, not on the
   default qualify path).

## Stop / continue

- **Continue** if the spike submits through the same ioctl path as
  `examples/triangle` / DRM tests without relaxing validator or VM rules.
- **Stop** and keep Mesa out of tree if NIR or winsys bootstrap dwarfs the
  functional gate; grow `userspace/examples/` instead until a product needs GL.
