# Visibility query hardware

Status: component,actual late-pixel integration and native typed/binned AXI
query transport are qualified on their recorded sources. API support and
composed timing remain open. PPA is synthesis only.

The [component record](performance/mobile-visibility-query-component-2026-10-21.json)
retains eight checks and one default emission on873 frozen inputs.
[NativeVisibilityQueryEngine](../src/main/scala/opengpu/graphics/NativeVisibilityQueryEngine.scala)
adds a bounded control engine around GPU-memory result records. No existing
texture production input changes.

## Semantic basis

GLES3.0 occlusion queries return whether any sample survives the depth test.
The conservative target may also return false positives, so the exact test
serves both targets. Begin resets the result, end closes the tracked command
interval, and availability follows completion. Query-object allocation, API
validation and driver integration are separate from this internal interface.
See sections2.14,4.1.6 and6.1.7 of the
[Khronos GLES3.0.6 specification](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf).

## Internal ownership contract

Begin and end wait for the caller's complete rendering barrier. The caller
must not dispatch following draws before the begin reply. Samples carry
context and VM-root identity, status and the late passing-sample count; the
query uses successful visibility independently of color exports. The caller
also supplies attributed job faults, including failures before any quad.

The default engine holds eight active context/root pairs and observes two
independent sample producers. Completed records live in GPU memory, so unread
results do not occupy these slots. A ninth simultaneous context receives an
explicit capacity error. Context admission/spill policy remains with the
enclosing scheduler. Only one64-byte memory transaction is live, using tag0.
A translated/coherent enclosing client must use the captured `memorySatp` and
retain the record allocation through acknowledgement.

Each command supplies action,64-byte-aligned address,33-bit allocation limit,
context,cookie,target kind and a reply tag. Actions are begin,end,poll,result.
Begin snapshots a zero/unavailable record; end snapshots visibility/fault
state, writes the complete record, and releases the active slot after the
write acknowledgement. Poll/result reject active objects immediately and
never block the command stream waiting for a future end command. Cookie and
root checks reject stale record reuse.

| Record word | Meaning |
| --- | --- |
| 0 | GQO1 magic `0x47514f01` |
| 1 | Record bytes:64 |
| 2 | Context |
| 3 | Reuse cookie |
| 4 | Available bit0,conservative bit1,status bits4:2 |
| 5 | Boolean result0/1 |
| 6 | VM root |
| 7–15 | Reserved zero |

Reply status is0 success,1 bounds/encoding,2 record identity,3 memory/renderer
fault,4 reset cancellation,5 active/target mismatch,6 active-context capacity.
Faulted records carry a false value. Poll may report their availability; result
returns the stored error. Ordinary and conservative targets may reuse a
completed record after a fresh begin.

Reset blocks new commands and drains every owned request/response. Held memory
offers and public replies remain stable. Active context state clears only
after rendering and memory ownership drain. The reset event invalidates
unfinished objects; the caller must discard or reinitialize their cookies.
Existing primary faults precede cancellation. This interface does not promise
CPU cache coherence. The optional native host transport below extends this
internal contract explicitly.

## Evidence and remaining integration

Six control checks cover empty/true results,render and memory barriers,held
requests,root snapshots,identity and encoding,bounds at4GiB,context capacity,
fault precedence and recovery. The default-capacity check fills all eight
contexts and observes both producers in one cycle,then reads all isolated
results after active slots have been released.

The real `TypedTilePixelBackend` integration passes four scopes with results
false,false,true,true. Seven tested samples yield three passing samples and
zero color writes. Depth-rejected,discarded,helper and masked samples do not
produce visibility; surviving MSAA samples and disabled depth tests do.
Public quad replies and query records are checked through backpressure.

The separate native host milestone below now supplies ordered transport,
discovery,typed/binned sample/fault hooks,translated records and whole-host
reset/VM evidence. API integration and composed synthesis remain open. The separate
standalone synthesis passes1000ps with core+277.509ps,I/O+423.249ps,zero
skipped instances,11241 mapped cells and library area1489.798980. Metadata
is synthesized without SRAM macros. This is a component screen only. Q01
and H6 are open.

## Optional native host transport

`visibilityQueries=true` enables the complete query path on a native typed or
native binned host. Other profiles do not expose query discovery. GQV1 has
eight read-only words at `0x240..0x25f`: magic `0x47515601`, word count8,
packed opcodes `0x0b0a0908`, GQO1 magic, record bytes64, active contexts8,
completion format and feature mask31. The generated
[ABI manifest](abi/native-visibility-query-v1.json) and
[C header](../driver/opengpu_visibility_query_abi.h) come from
`EmitNativeVisibilityQueryAbi`; `--check` rejects drift.

Unified commands8/9/10/11 are begin/end/poll/result. `SOURCE` is the record
virtual address; `DESTINATION` and `PATTERN[0]` are the33-bit allocation limit.
`PATTERN[1]` selects the conservative target. `BYTES` must be64; `WIDTH` is
the opaque query context and `HEIGHT` the reuse cookie. Higher pattern bits
and dependency/event flags are rejected before memory access. Native draw
commands carry that same opaque query context in `WIDTH`; it is independent
of the graphics descriptor identity. The host snapshots context/root at draw
admission and attributes actual passing-sample events and pre-quad job faults
to that snapshot.

The fourth opcode bit occupies completion bit16. Existing ID bits0..7, low
opcode bits8..10, status bits11..14 and success bit15 remain unchanged.
Completion data bit0 is value and bit1 is availability; other data bits and
native detail words are zero. A pending query fences following dispatch until
its owned reply enters the shared completion queue. Begin/end independently
wait for renderer and memory drain.

Query memory has one data ID and one walker in the existing native namespace:
typed38/39; binned native transaction count+6/+7. AXI widths and the texture
data/walker budgets are unchanged. A dedicated Sv32 client pins the engine's
captured root through its public reply and applies the allocation's PTE cache
policy. Root changes and deferred/scoped flushes cause a full idle flush of
this client. Cached records participate in GPU coherence; CPU-visible records
need an uncached mapping or the existing explicit cache-publication contract.
VM writes and reset completion include query ownership and memory drain.

The [frozen886-input host checkpoint](performance/mobile-visibility-query-host-2026-10-21.json)
passes35 fresh checks:seven transport/MMIO/fabric tests,21 existing command
checks,one extended-opcode rejection check,and six actual AXI tests. Both
payload modes complete26 native frames (22 healthy,four expected faults),
138 query commands and six empty scopes. Ten reset cuts cover six accepted
record stores before B and four actual vertex/fragment data reads. Aborted
queries and queued DMA are discarded; exact healthy query/image reuse passes.
All four extended query opcodes and reserved12..15 are rejected by the generic
router without aliasing old engines. Existing ID/status/success fields and
held results are preserved.

Generated headers also pass their emitter comparison and a C11 layout/decoder
check over131072 combinations. Three fresh typed,binned and full-product RTL
emissions pass. Runtime uses reduced four-CU/four-lane/two-warp profiles; the
full-capacity product result is structural emission. One test-only append
adds extended-opcode rejection while all production/AXI-fixture inputs remain
exact. The earlier eight component/pixel checks and standalone synthesis apply
only to their recorded exact dependencies. Across these scopes43 distinct
checks pass. No API conformance,full-capacity runtime,frame-rate or whole-host
timing claim follows from this milestone.
