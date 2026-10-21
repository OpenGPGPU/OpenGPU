# Native coordinates to RGBA8 colors

`NativeRgba8TextureService` composes selected native coordinates, checked
resource spans, Sv32 translation, the 8 KiB banked cache, actual physical-read
accounting, RGBA8 decode and hierarchical unquantized FP32 filtering. The
[834-input qualification](performance/mobile-native-rgba8-sampling-2026-10-21.json)
passes fourteen fresh planner/composition checks and emits all 78 active SV
files. The 77 lower-level read-accounting checks carry by exact input hashes.
Mapped synthesis is running at 1000 ps, including I/O; it has no result yet.

The caller first performs the existing binding/sampler/identity checks and
retains mip interpolation, final swizzle and general-format/shadow/volume
fallback ownership. Legal unsupported or unaligned groups return an explicit
fallback with no PTE/data work. Every active supported span is validated before
translation, and every active page is admitted before physical data lookup.
Faults retain a precise failing virtual word address, never its physical alias.
Physical reads remain counted through faults and cancellation.

The outer owner pins SATP from coordinate admission through public retirement,
covering work that has not yet reached translation. A root change closes new
admission until the old epoch drains. Invalidation also closes admission, then
lets accepted numeric work finish before flushing the cache at quiescence.
It preserves held and live numeric results. Pending scoped TLB flushes coalesce
to a full idle flush, retaining every pulse while work drains. New work cannot
enter between the old epoch and its pending maintenance.

Ordered outer rows and a child-owner FIFO distinguish repeated original caller
tags. Selective cancellation still matches original identity/tag across both
planning and sampling; same-edge admission is covered. Global cancellation
drains accepted math, PTE/data offers and arithmetic before the next epoch.
Held public replies remain immutable, and primary faults retain priority.

The 128 outer rows declare 4,880 bytes of logical result/control storage before
optimization. The planner's 64 rows, the FP32 sampler's 128 rows, physical
owners, child-owner FIFO, public queue and pipeline storage are additional.
These are capacity counts, not mapped area estimates.

The composed tests verify 192 complete warm groups at input/output interval one
without warm data reads, 96 backpressured groups and 128 arbitrary wrapped,
offset, nearest/linear numeric requests. They cover malformed spans before
memory, page permissions, data faults, VM changes during coordinate planning,
deferred invalidation/flush, changed backing, repeated tags, selective cancel
and twelve pipeline cancellation cuts. The initial five-of-six run exposed a
test assumption about lowest-tap fault priority; either denied active virtual
word is a valid precise fault, and the corrected/expanded tests pass.

This is a complete coordinate-to-color component. Native shader client wiring,
two shared clusters for four CUs, native fallback/mip/swizzle ownership, whole
renderer rates/fault atomicity and render-to-texture visibility still need
integration and verification. All remaining H0-H8 gates stay open. PPA remains
synthesis only.
