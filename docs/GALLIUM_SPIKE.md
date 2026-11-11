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

Emit a single corpus binary without a full corpus validate:

```sh
python3 scripts/validate_shader_corpus.py --emit fragment_tint /tmp/tint.bin
```

## Gallium driver

`userspace/gallium/` is the out-of-tree pipe. `scripts/stage_mesa_opengpu.sh`
copies it into the pinned Mesa 22.3 checkout at `depends/mesa` and registers
an `opengpu` DRI driver. `draw_vbo` lowers a TGSI colour copy onto `vertex_passthrough.S` and
`fragment_color.S`, a `mat4 * vec4` position onto `vertex_mvp.S`, and a 2D `TEX` onto `fragment_sample.S` (`vtex.sample`
of the interpolated Q16.16 UV, base mip, clamp). It packs the fixed vertex,
binds the texture GEM, and submits `OPENGPU_SUBMIT_VERTEX_CORE`. Enabled
blend maps onto draw-record word 35 (GL factors 0–10, one equation for
every channel). The source alpha is the interpolated vertex colour alpha.
Constant-colour factors, a separate alpha equation, and any other shader
fail. An enabled scissor is the half-open scanout rectangle in draw-record
words 38 and 39; the rasterizer drops fragments outside it. The screen
reports one viewport, which is what lets Mesa's scissor enable stick. The driver does not call llvmpipe, softpipe, or the Gallium `draw`
module.

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
cache invalidation path. Bound depth attachments are loaded on subsequent draws.
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

`python3 scripts/test_query_unsupported.py` tests the actual ioctl's rejection.
With Mesa 22.3.6 and DRM UAPI headers available, run
`OPENGPU_MESA_DIR=/path/to/mesa OPENGPU_DRM_INCLUDE=/path/to/include python3 scripts/test_gallium_transfers.py`.
The latter checks scissored colour/depth/stencil clears, aspect preservation,
depth binding and staged copies for mismatched pitches with the actual Mesa
helpers and OpenGPU callbacks, mocked GEMs, GPU submission, cache invalidation
and small format-pack stubs. It also checks the real winsys constructs the
depth-load submission flag; it does not execute a GPU or validate a Linux guest.
The fixture also passes with
`CC='clang -fsanitize=address,undefined -fno-omit-frame-pointer
-ftrivial-auto-var-init=pattern'`.

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
an OpenGPU renderer by default. This test has not yet passed on an OpenGPU
guest or GPU and does not qualify H6.
Set `OPENGPU_GL_COMPARE_MATRIX=1` to additionally test every depth and stencil
comparison against less/equal/greater inputs and all eight stencil operations.
Set `OPENGPU_GL_COLOR_MASK_MATRIX=1` to also check all sixteen colour write
masks and two overlapping triangles blended with the destination alpha.
Set `OPENGPU_GL_VERTEX_FORMAT_MATRIX=1` to check normalized, scaled and
fixed-point position/colour attributes through GLES.
Set `OPENGPU_GL_TRIANGLE_TAILS=1` to check arrays and indexed triangle draws
with zero through five vertices: incomplete tails must not discard completed
triangles.
Set `OPENGPU_GL_SAMPLE_FORMATS=1` to sample GLES2 luminance, alpha,
luminance-alpha, RGB and RGBA textures into each attachment type.

The test oracle, comparison/operation, colour-mask, vertex-format and triangle
tail matrices passed both attachment cases on Mesa llvmpipe. To reproduce that software-only
check on Linux with EGL, GLES2 and GBM development libraries:

```sh
cc -std=c11 -O2 -Wall -Wextra -Werror userspace/examples/gl_depth_stencil.c \
  -o /tmp/opengpu_gl_depth_stencil -lEGL -lGLESv2 -lgbm
LIBGL_ALWAYS_SOFTWARE=1 OPENGPU_GL_SURFACELESS=1 OPENGPU_GL_ALLOW_SOFTWARE=1 \
  OPENGPU_GL_COMPARE_MATRIX=1 OPENGPU_GL_COLOR_MASK_MATRIX=1 \
  OPENGPU_GL_VERTEX_FORMAT_MATRIX=1 OPENGPU_GL_TRIANGLE_TAILS=1 OPENGPU_GL_SAMPLE_FORMATS=1 \
  /tmp/opengpu_gl_depth_stencil both
```

Fragment sampler views can select any source channel, zero or one. A
non-identity view synchronizes its source and stages the selected components
as tightly packed RGBA before a draw; identity views retain the existing fast
path. This CPU copy is per draw and is not a product-rate claim. The transfer
fixture exercises actual Gallium view creation, staging, bounds, synchronization
and failure handling with mocked GEMs. Independent byte and bilinear-filter
references cover all 1,296 selector combinations for RGBA, BGRA, RGBX and
BGRX storage. The GLES2 base-format matrix passes on Mesa llvmpipe as a
software oracle; it has not yet been qualified on OpenGPU hardware.

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
and release a transferred index-buffer reference after the draw callback. The
Mesa-header fixture tests these helpers, including zero-length buffers and
negative/overflowing base-vertex values, with mocked mappings:

```sh
python3 scripts/test_gallium_index_buffers.py
CC='clang -fsanitize=address,undefined -fno-omit-frame-pointer' \
  python3 scripts/test_gallium_index_buffers.py
```

It is a CPU test; indexed GLES draws on OpenGPU remain to be qualified.
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
