# Native RGBA8 group planning

`TextureRgba8GroupPlanner` converts a selected immutable level and normalized
FP32 coordinates into one checked four-tap group per cycle. The
[831-input qualification](performance/mobile-native-rgba8-group-plan-2026-10-21.json)
passes six tests and emits the complete standalone module. The coordinate and
address stage is ready for composition with the translated FP32 service;
native shader routing and mapped synthesis remain open.

The caller first performs the existing native level owner's binding, sampler,
identity, compatibility, selected-level and swizzle checks. The planner carries
only the selected view rather than the full mip-level array. It uses two exact
FP32 coordinate pipelines with 149 fractional bits and four checked address
pipelines. Repeat, clamp, mirrored repeat, offsets, seamless cube edges and
array layers preserve the existing coordinate rules. Exact zero/negative-zero
and one weights suppress unused taps; positive subnormal weights remain active.
Inactive taps alias an active location before address checks.

Every active span and the full level allocation are checked before publishing
a group. Full-width layer and face selectors are checked before narrowing.
The key retains the complete table address and handle alongside the work
identity. Rebinding still requires the existing publication/invalidation
contract. No descriptor, PTE or data request can issue from this module.

Aligned, unshadowed RGBA8 2D, array and cube requests produce groups. Other
legal formats, volume/shadow operations and valid unaligned packed addresses
return an explicit fallback for the general native sampler. Malformed supported
requests retain primary errors; they cannot become a successful fallback.

Sixty-four owner rows retain metadata and ordered replies through math and
public retirement. Their declared logical storage is 47,872 bits (5,984 bytes)
before optimization, excluding axis/address/output queues and control registers.
Global cancellation closes admission until the epoch drains. Selective
cancellation matches work identity plus original tag, including concurrent
admission. Held public replies remain immutable, and primary faults survive.

The tests compare 264 conflict-free groups against an exact rational coordinate
oracle and prove input/output interval one. Another 2,020 groups cover all cube
faces, edges/corners and 256 array layers under backpressure using an independent
cube direction-vector oracle. Directed checks cover 22 malformed cases, five
fallback modes, actual 64-row saturation, fault/cancel drain, tag reuse and 192
healthy requests after cancellation. These are planner measurements; complete
translated sampling, shared-CU throughput and render-to-texture visibility
still require integration and verification. PPA remains synthesis only.

## Pipelined table admission

The composed shared-cluster screen found a−52.676ps path into table-status
metadata. The candidate now registers the33-bit table span, full handle and
header/level checks on admission, then validates record capacity on the next
edge. For a nonempty aligned span, the exact record-end condition is equivalent
to `handle < (limit-base)/128`. All33 span bits retain the4GiB exclusive endpoint.
The owned check drains through cancellation and keeps primary table faults.
The existing eight-stage coordinate pipeline covers this check latency, so
admission/output rates and the public latency remain unchanged.

[Fresh validation](performance/mobile-native-rgba8-table-admission-2026-10-21.json)
passes24 tests and five complete emissions. An independent unbounded record-end
oracle checks224 table-bound/large-handle cases. Numeric, Sv32, scoped reset,
peer/root isolation and default two-pair cold-window checks pass. Actual native
16-lane/eight-warp streaming still sustains one warm group per cycle with eight
contexts:448 stable groups in448 cycles, maximum admission gap1 and reply
latency90 cycles. Fresh composed-cluster and complete endpoint synthesis are
queued at1000ps TC/SLVT,25% I/O, standard ABC and no retiming. Timing closure
and main promotion remain pending; the previous failed mapping is preserved.

## Native cancellation selection timing

The owned table-admission cluster maps completely and passes 1000 ps with
core +42.104 ps and I/O +301.056 ps. The first full native vertex endpoint
maps completely and passes core timing (+88.034 ps), but its live cancel to
external group identity path fails I/O by 25.302 ps. The serial run therefore
does not launch native fragment. The terminal evidence is retained.

`NativeTextureInstructionStream` now selects cancellation identities from the
owned status register, which already captures a one-cycle cancel pulse. New
lane issue still stops immediately. Pending queued offers and real replies
retain ownership; a later primary fault still wins, and private tags are not
reused until public instruction retirement. This cuts the live cancel input
out of the context/lane priority tree without adding a detached delayed cancel
that could outlive its owner.

The [914-input checkpoint](performance/mobile-native-texture-cancel-selection-2026-10-22.json)
passes forty-seven checks and five emissions. A new pulse test holds accepted
offers, drains three contexts, preserves a primary fault and reuses exact tags.
All twenty-three native lane-stream cases pass, including 448 warm four-tap
groups in 448 cycles on the default sixteen-lane/eight-warp shared-window burst
profile and full reset drain. Other measured controls remain slower. Every
active composed-cluster RTL byte matches the earlier positive synthesis, so
that result carries exactly. Fresh complete native vertex/fragment synthesis
is running at unchanged constraints; current-main integration and broader
H4/H0-H8 gates remain open.
