# Primitive query controller

`NativePrimitiveQueryEngine` counts acknowledged transform-feedback primitives.
The optional native path now connects command transport, capability registers,
translated record memory and capture events in both typed and binned hosts.
Typed/binned healthy capture AXI runtime now passes in both payload modes.
Additional query coverage and composed budgets remain open; this does not complete H6/Q02.

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

The standalone controller was qualified on 907 exact source inputs. Component
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

## Native capture AXI validation

[Four composed AXI tests](performance/mobile-primitive-query-native-axi-2026-10-21.json)
pass in typed/binned hosts with local payloads and spill. Eight successful native
frames produce exactly one recorded primitive per typed frame and two per binned
frame, including raster discard and once-before-fanout accounting. All twelve
source primitives are checked against acknowledged feedback bytes and exact
MRT/depth/resolve image oracles. Peer-context query results stay zero.

The tests exercise88 native query commands, four empty scopes, four reset cuts
on accepted query-record stores, held-completion VM protection and fresh-cookie
reuse. Feedback and visibility coexist; each adds four checkpoint/store reset
cuts. GPO1 backing records and guard bytes are exact. Eight explicitly synthetic
64-bit completed-record reads exercise32-bit clamping; they are not capture
counts. The first attempt failed before simulation because the disk filled
during compilation. The successful retry preserves the identical917 inputs.

Together with the31 transport/component checks this gives35 distinct checks,
plus three previously qualified host emissions. Paused/inactive/incomplete
capture counting, native query/capture permission and bus-fault matrices, full
product runtime and composed timing/resources remain open.

## Native capture/query recovery

[Two four-CU typed-host checks](performance/mobile-primitive-query-native-recovery-2026-10-21.json)
pass with local and spilled payloads. Twenty completed native frames include
six expected failures and fourteen successful images. Ten source primitives
are actually captured: eight in healthy peer contexts and two after resume.
Paused and absent feedback objects each produce two zero-count non-capture draws.

For each payload mode, a denied capture page reaches no physical store, and a
bus error on the final capture line follows two acknowledged prefix lines.
Neither advances the primitive count or cursor. A checkpoint permission failure
invalidates only the affected capture lease; its failed draw faults the owning
query while the peer still captures. GPO1 records, fault/availability values,
raw capture bytes, guards, image oracles and local payload release are exact.
Two accepted capture-store resets drain ownership and reject all four stale
query ends. Two additional query-record reset probes check fresh-cookie reuse. Four fresh
typed/binned healthy/discard control tests also pass on the same 979 inputs;
with 31 unchanged component/transport checks, the distinct total is 37.

These tests change no production Scala/build/ABI bytes and establish no new
PPA result. Binned fault/pause runtime, native query-record permission/bus errors,
a continuous multi-draw query, full product capacity and composed budgets remain
required; Q02/H6 stays partial.

```sh
sbt 'testOnly opengpu.graphics.NativePrimitiveQueryRecoveryHostAxiSpec'
```

## Native query-record memory and continuous lifetimes

[Four typed-host checks](performance/mobile-primitive-query-native-lifetime-2026-10-21.json)
pass on 980 frozen inputs with local and spilled payloads. Begin, end, poll and
result each encounter one denied Sv32 access and one physical bus fault per
payload mode: sixteen memory faults overall. Denied accesses never reach record
memory. Failed begin installs no slot; failed end frees its closing slot and
rejects the stale end. Fresh-cookie reuse, healthy read retry, a coexisting live
peer scope and record guard bytes all remain correct.

Each continuous-lifetime test keeps one query open across active capture,
pause, a non-capture draw, resume, another capture and an inactive draw. EndQuery
returns exactly two. A third real capture after end leaves that completed query
unchanged. Across both payload modes, ten frames capture six source primitives,
four inside the query scopes. Active main/peer polls reject partial results;
raw feedback bytes, complete GPO1 records, image oracles and payload release
checks pass. Four query-record reset probes also retain fresh-cookie ownership.

The distinct total across qualified frozen checkpoints is 41. These additions
change no production/build/ABI bytes. Equivalent binned fault/lifetime runtime,
full-capacity product execution and composed budgets remain open; Q02/H6 stays
partial.

```sh
sbt 'testOnly opengpu.graphics.NativePrimitiveQueryLifetimeHostAxiSpec'
```
