# Primitive query controller

`NativePrimitiveQueryEngine` is the standalone hardware controller for counting
acknowledged transform-feedback primitives. Native command transport, capability
registers, translated memory wiring and the actual capture-event connection remain
open; this component does not complete H6/Q02.

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
results return zero; stored counts can retain diagnostic information. These are
component record semantics, not a published native command ABI.

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
