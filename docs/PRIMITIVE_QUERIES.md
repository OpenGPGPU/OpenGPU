# Primitive query controller

`NativePrimitiveQueryEngine` counts acknowledged transform-feedback primitives.
The optional native path now connects command transport, capability registers,
translated record memory and capture events in both typed and binned hosts.
Actual composed AXI runtime and remaining query coverage are still open; this
does not complete H6/Q02.

The default controller has eight live context/full-root counters and one event
producer. Counts saturate at 64 bits; the 32-bit result view clamps to UINT32_MAX.
Completed unread objects live in GPU memory and release the active slot. Begin
and end require `renderIdle`; the caller fences following draws until the reply.
End includes events on its acceptance edge and ignores later events for that row.
Peers continue counting while another object's memory request or reply stalls.
Only trusted events after successful feedback stores may increment a query.

Records are 64-byte aligned, bounded by a 33-bit exclusive limit at most 4 GiB.
The internal GPO1 record has sixteen 32-bit words: magic 0x47504f01, size 64,
context, cookie, flags, count low/high, full root, target 0x8c88, then seven zero
words. Flags contain availability at bit 0 and fault at bits 4:2; all other bits
are reserved. Poll/result validate the entire record and identity. Faulted public
results return zero; stored counts can retain diagnostic information. The native record ABI is generated alongside the hardware constants.

Reset retains owned requests, replies and roots until they drain. Unfinished
memory faults take precedence over cancellation. Active rows clear after render
idle and drain; reuse requires fresh cookies. Scoped job faults preserve peer
counts.

## Validation

Five `NativePrimitiveQueryEngineSpec` cases pass, covering simultaneous producer
carry/saturation and result clamping; eight active slots, root isolation and 28
completed unread records; bounds and malformed records; scoped fault attribution;
and held-request reset drain with fresh-cookie reuse. Tests use two producers;
the default one-producer RTL also emits successfully.

The exact 907-input qualified source catalog matches this branch. Component
synthesis passes at 1000 ps, ASAP7 TC/SLVT, 25% I/O delay, standard ABC and no
retiming: core slack +143.195 ps, I/O slack +384.931 ps, 18,215 mapped cells,
library area 2309.734440, sequential area 643.269600, zero SRAM macros and zero
skipped instances. This is synthesis timing only, without a composed-host claim.
See [qualification evidence](performance/mobile-primitive-query-component-2026-10-21.json).

Reproduce with:

```sh
sbt 'testOnly opengpu.graphics.NativePrimitiveQueryEngineSpec'
sbt 'runMain opengpu.elaboration.EmitPrimitiveQuery generated/primitive-query-rtl'
```

## Native transport

Enable `primitiveQueries=true` on `GpuHostSystemAxi` with native transform
feedback enabled. Visibility queries can be enabled independently. The generated
[header](../driver/opengpu_primitive_query_abi.h) and
[ABI manifest](abi/native-primitive-query-v1.json) define GQP1 discovery and GPO1
records. `EmitNativePrimitiveQueryAbi --check` checks both generated artifacts.

Opcode13 uses SOURCE=record VA, DESTINATION=exclusive limit low, PATTERN bit0=
limit high and bits2:1=begin/end/poll/result, BYTES=64, WIDTH=opaque context,
HEIGHT=query cookie and FLAGS=0. PATTERN bits31:3 must be zero. SOURCE_STRIDE is
ignored. Completion DATA_LO is the clamped32-bit result and DATA_HI bit0 is
availability; its remaining bits are zero. The existing opcode-extension bit16
in completion metadata carries opcode13 without changing the older fields.

Eight read-only capability words at0x280..0x29f describe opcode/action count,
record shape, eight active contexts,64-bit counters,32-bit API results, target
0x8c88 and feature flags. Disabled profiles keep this range unmapped.

The native event source is exactly `feedback.recorded`: one increment after a
complete source primitive's successful capture stores, before clipping/binning
and tile fanout. Draw faults use the captured opaque render context/full root.
The separate auxiliary data/walker IDs are44/45 for typed hosts and native
renderer namespace+12/+13 for binned hosts; texture IDs and the existing physical
image-transfer classification retain their layouts. Reset and VM-idle ownership
include pending query records and replies. Active counters alone do not pin a VM.

[Transport qualification](performance/mobile-primitive-query-native-transport-2026-10-21.json)
passes31 distinct checks, including four primitive transport cases, two enabled/
disabled capability AXI cases and the existing feedback, visibility, router,
MMIO and auxiliary permission/root regressions. Typed, binned and full product
RTL emit successfully. The generated header passes C11/C++17 size and ten offset
assertions. These checks do not qualify composed capture-event AXI runtime or
whole-host timing.
