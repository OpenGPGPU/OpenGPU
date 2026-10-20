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
