# Native framebuffer blit

The Gallium nearest blit callback now has a checked-storage GPU implementation
for its four existing RGBA8/BGRA8/RGBX8/BGRX8 formats. Scaling, source flips,
destination clipping, scissor and channel masks run through a private compute
context. The CPU prepares coordinate offsets; it never reads or converts source
pixels. The emitted GPU program reads source pixels, converts channels, merges
masked destination bytes and waits for acknowledged writes.

The version-1 descriptor rejects unknown versions, lengths, flags, formats and
out-of-range image metadata. Whole images retain their actual pitch and exact
logical byte limits. Source and coordinate views are read-only; destination
storage is read/write. Kernel compute admission proves backing disjointness,
retains original GEMs and constructs the existing private Sv32 leases. Submission,
completion, cached publication and final context destruction are separate steps.
An unresolved accepted wait retains its original fence and context as a screen
orphan. A new blit cannot reuse that context or replay it through the CPU.

The old CPU implementation remains available only before GPU work is admitted:
LINEAR, depth/stencil, same-GEM operations, views beyond the checked-storage
128 MiB window, incomplete physical line tails and devices without byte views.
It preserves the existing API capacity; it does not qualify those operations as
GPU features. Version 1 also accepts reversed destination endpoints in the direct
winsys descriptor; the existing Gallium callback requires a positive destination
box. Hardware capacities and the public kernel command ABI are unchanged.

CPU validation passes 8,198 affine/format/mask/scissor cases against an independent
direct pixel oracle and instruction decoder, including full source/map/destination
guards and signed endpoint boundaries. Every generated program passes the real
kernel shader validator. The actual Gallium/private winsys/lib-ioctl CPU fixture
passes all 16 format pairs, read/write permission checks, held coordinate and
compute acknowledgments, rejected new work before late retirement, compute fault
and healthy reuse, with graphics bindings unchanged. Both gates use ASan/UBSan.
The existing transfer/depth/viewport/sampler/attachment and winsys range/unbind
CPU regressions also pass. Linux ioctls and device scheduling are modeled in the
CPU fixture; these results do not prove actual RTL execution.

```sh
python3 -B scripts/test_framebuffer_blit.py
OPENGPU_MESA_DIR=/path/to/mesa-22.3.6 \
OPENGPU_DRM_INCLUDE=/path/to/drm-headers \
  python3 -B scripts/test_gallium_compute.py --fixture framebuffer-blit
```

Actual two-CU/Sv32/L2 execution, fault/reset drain and completion retirement remain
required. This implementation currently launches one workgroup per destination
pixel; full-frame cost has not been measured. No blit throughput, product frame
rate or whole-host physical clock is qualified. F08 and the full H6 target remain
open, including GPU LINEAR/depth/stencil and legal disjoint same-GEM image views.
The GLES contract is described in [OpenGL ES 3.0.6, §4.3.3](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf).
