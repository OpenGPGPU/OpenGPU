# Native binned graphics ABI draft

The original development checkpoint defined records and generated interfaces.
A subsequent [standalone reader](NATIVE_BINNED_GRAPHICS_READER.md) now validates
and fetches these records. Translated memory fabric and host submission are
still pending; the draft is not an advertised production capability.
Existing GTJ1/GTI1/GTD1 and GFS1 formats are unchanged.

`NativeBinnedGraphicsLayout` defines GNJ1 job, GNF1 frame and GND1 draw snapshots
with 512/2048/2048-byte fixed sizes. The first three words hold magic, byte size
and layout version. Capacity fields occupy words 3-7; words 8-15 are reserved.
Payload fields start at word 16 and multiword fields do not cross a 64-byte
line. Scalar order, widths, signedness and explicit padding are generated into
the C header and JSON schema. Layout version 1 provides 16 attributes, bindings
and varying slots, four colors and four engine views.

The frame snapshot carries `TypedVertexBinImageDescriptor`; draws use the fixed
16-slot `VertexBinDraw` representation. Capacity fitting checks precede copying
to a smaller runtime configuration. A future reader must validate complete
headers, reserved words, widened bounds, identities, resource permissions and
job/frame/draw storage aliases before exposing work. It must retain offers and
ownership through fault, cancellation, VM changes and reset.

Development validation compiled the generator, emitted all three layouts and
passed `EmitNativeBinnedGraphicsAbi --check` for 563 scalar fields. A C11 probe
checks fixed sizes, magic values and selected independently specified offsets.
Reader decode/validation and fault/cancel component checks are documented in the
reader checkpoint. Native memory routing, host/MMIO discovery, actual shared-CU
rendering and production reset are still required.

The unpublished prototype previously used the legacy bin job magic. The
current GNJ1/GNF1/GND1 namespace avoids that collision; earlier frozen evidence
retains the prototype values as historical data. No published legacy ABI changed.

## Optional vertex output reuse

GND1 word 498 is a one-bit `draw.vertex.geometry.vertex.reuseOutputs` promise;
upper padding bits must be zero. It uses the [same contract as GTD1](VERTEX_POST_TRANSFORM_REUSE.md)
and requires a nonzero GVI1 cache entry count. Existing fields and record sizes
retain their offsets. Zero preserves ordinary shader execution.

## Optional GPU-memory indirect arguments

GND1 word 499 selects direct/arrays/elements mode (0/1/2), with zero upper
padding. Words 500, 501–502, 503 and 504–505 hold parameter base, logical limit,
allocation base and allocation limit. Limits are 33-bit exclusive bounds.
Probe GVI1 word 2 bits 31:24 first. The [indirect draw contract](INDIRECT_DRAW_SUBMISSION.md)
covers argument resolution, GPU publication, alias checks and cancellation.
Mode zero requires the whole view to be zero. Existing fields and the
512-word draw size remain unchanged.

## Userspace frame metadata encoding

`driver/opengpu_native_frame.h` provides the GNF encoder and resident tile
planner. It retains all advertised extents, four color attachments, separate
depth/stencil, 1/2/4 samples, six image/resolve slots and eight bin spill
segments. Automatic tile selection chooses the largest advertised tile that
fits every attachment; callers can explicitly request 8x8 or 16x16. This is a
storage policy, not a claim that larger tiles improve every workload.

`opengpu_native_frame_encode` returns canonical 2048-byte GNF metadata and up to
27 typed GEM resource plans. All device addresses remain zero, including the
private GFS state view. Image plans describe exact byte views, including
unaligned payload starts and row pitch; their encompassing 64-byte lines must
belong to the original GEM. Read/write permissions follow load/store/resolve
use. A failed encode leaves the caller's result untouched.

Pool role index zero names the initial segment; indices 1..8 name ordered
fallback segments. A zero initial segment is allowed when fallback segments
exist. The protected kernel producer must reserve the complete private VA
envelope, patch its overall and segment bounds, and retain all backing objects.
It must independently validate original GEM bounds, physical aliases,
dependencies, actual copied state count, and every relocation before submission.
This helper does not enable a capability or implement that protected producer.

CPU validation passes all 36 color formats at each sample count, the 65536-tile
v2 count, maximum resource/spill plans, zero-initial fallback, pitched endpoints,
original-GEM overlap rejection, and failure atomicity. An independent decode
through the published Scala-generated JSON checks field values, every address
and all reserved words. These checks do not execute RTL or establish FPS.

```sh
PYTHONDONTWRITEBYTECODE=1 OPENGPU_TEST_SANITIZERS=1 \
  python3 scripts/test_native_frame.py
```
