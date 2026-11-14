# Native framebuffer blit

The Gallium blit callback now has a checked-storage GPU implementation
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

Version 2 additionally admits same-format D24S8/D24X8 nearest transfers, with
independent selected depth/stencil planes and unselected bits retained. A depth
format mismatch or stencil request on D24X8 fails admission. Version 1 continues
to reject these formats. Version 2/3 permit same-GEM copies only after an exact
tap-set proof establishes that all source reads are disjoint from all destination
writes. Actual X/Y taps are sorted; two binary searches per destination row retain
skipped rows/columns, source reversal and repeated scaled samples. Scratch is
bounded at 256 KiB for the unchanged 16,384-axis capacity. Merely having
different object handles is not the backing proof; unknown imports remain subject
to the kernel's existing rejection.

The old CPU implementation remains available only before GPU work is admitted:
unsupported depth view combinations, overlapping same-GEM operations, views beyond the checked-storage
128 MiB window, incomplete physical line tails and devices without byte views.
It preserves the existing API capacity; it does not qualify those operations as
GPU features. Version 1 also accepts reversed destination endpoints in the direct
winsys descriptor; the existing Gallium callback requires a positive destination
box. Hardware capacities and the public kernel command ABI are unchanged.

Version 3 adds LINEAR filtering for these normalized color formats. Each axis
uses a clamped two-tap coordinate with Q16 weights. The GPU reads all four pixels
and computes the complete bilinear sum using 16-bit limbs and existing uint32
instructions, preserving the full 40-bit intermediate product and rounding only
once at the end. No extra arithmetic opcode or relaxed shader validator is used.
The coordinate weight error can change an ideal rounded 8-bit component by at
most one level; quantized results match the independent full uint64 sum exactly.
Versions 1/2 reject the LINEAR flag. Depth/stencil LINEAR and mismatched depth
formats remain invalid descriptors. The source-region clamp follows the allowed
region-clamped policy; framebuffer edges always clamp.

CPU validation passes 16,414 affine/format/mask/scissor cases against an independent
direct pixel oracle and instruction decoder, including full source/map/destination
guards and signed endpoint boundaries. Every generated program passes the real
kernel shader validator. The actual Gallium/private winsys/lib-ioctl CPU fixture
passes both filters across all 16 format pairs, read/write permission checks, held coordinate and
compute acknowledgments, rejected new work before late retirement, compute fault
and healthy reuse, with graphics bindings unchanged. Both gates use ASan/UBSan.
The caller also verifies all supported depth/stencil selections, legal same-GEM
disjoint copy and pre-submission rejection of overlapping read/write spans.
Another 4,145 random/boundary bilinear products match a full uint64 oracle,
including zero/full weights, all-ones values and low-limb carries. The image
cases also compare against ideal long-double filtering within one 8-bit level.
The same-GEM proof matches an independent per-byte/tap oracle for 4,096 bounded
rectangles with different pitches, scaling, clipping, scissor and both endpoint
directions. Directed positive cases admit destination columns and rows in actual
downsampling holes; corresponding true nearest/four-tap overlaps are rejected.
The existing transfer/depth/viewport/sampler/attachment and winsys range/unbind
CPU regressions also pass. Linux ioctls and device scheduling are modeled in the
CPU fixture; these results do not prove actual RTL execution.

The actual caller can export 37 bounded original submissions: both filters and
all 16 color pairs, four depth/stencil selections and a same-GEM copy. Each
contains the original shader, kernarg, descriptor/maps, complete GEM bytes,
exact permissions/view bounds and an independently calculated expected GEM.
Preparation pins the compiler and transitive C/Python source closure before and
after capture. Reuse requires an external manifest hash and unchanged original
sources; this CPU receipt cannot qualify a DUT. The C++ reader checks all 37
against the production emitter/maps and an independent direct affine pixel
oracle. ASan/UBSan passes these inputs and 27 malformed input rejections.
The bounded Sv32 fixture retains actual GEM identity, aliases, bytes and exact
GSB1 permissions; independent walks check every mapped page, while complete
memory checks include shader, maps, page tables, padding and image guards.

The host executor source admits these original packets before creating a model,
requires the complete 96-word profile, and stages the original bytes in that
bounded test VM. It checks held completions, last external ACK, publication
invalidate, exact retirement and page revocation before the next case without
reset. Its runtime comes from an explicit owner checkout; producer C checks are
separate from the C++ wire reader, so no duplicate cross-checkout ABI header is
selected. The actual owner's strict driver-include validator and a C++ typecheck
pass. The typecheck uses declaration-only ports and executes no RTL. This source
is not yet a qualified executable or a completed GPU test; original Linux MMIO
replay, fault/reset cancellation, full-model identity and pre/post execution
guards remain separate required gates. Reusing the frozen 96-word DUT would be
functional diagnosis only. Final H6 qualification requires the newly integrated
source profile and all six complete 640×480 frames with its whole-host clock.

```sh
python3 -B scripts/test_framebuffer_blit.py
OPENGPU_MESA_DIR=/path/to/mesa-22.3.6 \
OPENGPU_DRM_INCLUDE=/path/to/drm-headers \
  python3 -B scripts/test_gallium_compute.py --fixture framebuffer-blit
OPENGPU_MESA_DIR=/path/to/mesa-22.3.6 \
OPENGPU_DRM_INCLUDE=/path/to/drm-headers \
  python3 -B scripts/test_framebuffer_blit_inputs.py
python3 -B scripts/check_framebuffer_blit_host_source.py \
  --owner-root /path/to/gpu/.artifacts/worktrees/complete-owner-source
```

Actual two-CU/Sv32/L2 execution, fault/reset drain and completion retirement remain
required. This implementation currently launches one workgroup per destination
pixel; full-frame cost has not been measured. No blit throughput, product frame
rate or whole-host physical clock is qualified. F08 and the full H6 target remain
open, including actual native/API qualification of these paths and broader
pixel-transfer layouts and framebuffer formats.
The GLES contract is described in [OpenGL ES 3.0.6, §4.3.3](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf).
