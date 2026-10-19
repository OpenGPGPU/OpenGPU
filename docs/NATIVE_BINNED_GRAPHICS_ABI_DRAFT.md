# Native binned graphics ABI draft

This development checkpoint defines records and generated interfaces only.
No native binned descriptor reader, translated memory fabric or host submission
path is implemented here. The draft is not an advertised production capability.
Existing GTJ1/GTI1/GTD1 and GFS1 formats are unchanged.

`NativeBinnedGraphicsLayout` defines GBJ1 job, GBF1 frame and GBD1 draw snapshots
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
Reader decode/validation, fault/cancel tests, native memory routing, host/MMIO
discovery, actual shared-CU rendering and production reset are still required.
