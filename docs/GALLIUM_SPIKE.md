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
cache invalidation path. Bound depth attachments are loaded on subsequent draws;
the legacy render descriptor requires equal colour and depth row strides, so
draws with mismatched strides are rejected until a staging path exists. The
single-sample attachment layout does not change with the rasterizer multisample
enable. These paths cover only the buffer and 2D resources that this driver can
allocate; they do not qualify native image transfers, compressed formats or
multisample copies. The Linux query
ioctl returns `EOPNOTSUPP` and Gallium rejects query creation until hardware
commands and result ownership are connected. Component query tests do not
establish API query support.

`python3 scripts/test_query_unsupported.py` tests the actual ioctl's rejection.
With Mesa 22.3.6 and DRM UAPI headers available, run
`OPENGPU_MESA_DIR=/path/to/mesa OPENGPU_DRM_INCLUDE=/path/to/include python3 scripts/test_gallium_transfers.py`.
The latter checks scissored colour/depth/stencil clears, aspect preservation,
depth binding and stride admission with the actual Mesa helpers and OpenGPU
callbacks, mocked GEMs, cache invalidation and small format-pack stubs. It also
checks the real winsys constructs the depth-load submission flag; it does not
execute a GPU or validate a Linux guest. The fixture also passes with
`CC='clang -fsanitize=address,undefined -fno-omit-frame-pointer
-ftrivial-auto-var-init=pattern'`.

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
The lower-level linear image allocator checks the pixel product before
multiplying by four and rejects byte sizes above `0x7fffffff`.
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
