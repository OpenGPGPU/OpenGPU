# Registered vertex memory replies

Vertex input, staging and texture replies pass through a two-entry registered
router before tag decoding and fault/cancellation control. This cuts the direct
external memory-response path into the vertex owner's control logic.

The existing 64-byte response interface and four-tag namespace are unchanged:

| Incoming tag | Destination | Client tag |
| --- | --- | --- |
| 0 | Vertex input | 0 |
| 1 | Staging/payload transport | 0 |
| 2–3 | Texture | 0–1 |

Both entries retain the complete 512-bit data, fault flag and routing tag.
The head is a register; input readiness depends on registered occupancy and
does not depend combinationally on client readiness. A ready stream delivers
one reply per cycle after a one-cycle handoff. Full-buffer recovery follows
the existing non-pipelined `RegisteredQueue2` admission contract. The two
515-bit records and valid bits use 1,032 bits of register state.

Accepted replies stay in arrival order until their selected client accepts
them. Client pending counts retire on that internal handshake. Scoped
cancellation does not flush the buffer: clients drain accepted replies before
completion and reuse. The vertex owner's drain condition includes the buffer,
and completion asserts that no accepted reply remains. Request limits,
descriptor layouts, completion fields and software ABI are unchanged.

## Validation

Three router tests verify 128 consecutive one-reply-per-cycle transfers,
two-entry backpressure, immutable fault/data payloads, registered readiness,
and 512 randomized arrivals across all four tags with independent client stalls.
Two actual two-CU tests cancel immediately after the stage accepts a reply,
check drained completion without exports or shader launch, then execute a
healthy draw with exact outputs in local/global payload modes. Two indexed
reuse tests retain 32 ordered vertex exports, instance/restart boundaries,
fault/cancel recovery and batch reduction. A vertex-texture test retains fault
drain and fresh sampling. All **eight directed checks** and host script tests
pass.

```sh
sbt -Dopengpu.simJobs=4 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.VertexMemoryResponseRouterSpec' \
  'testOnly opengpu.graphics.VertexShaderStageSpec -- -z "newly accepted registered response"' \
  'testOnly opengpu.graphics.VertexShaderStageSpec -- -z "reuse pure indexed"' \
  'testOnly opengpu.graphics.VertexShaderStageSpec -- -z "sample vertex textures on shared CUs"'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-vertex-memory-responses .artifacts/vertex-replies-rtl'
```

The emitted router passes 1000 ps TC/SLVT synthesis with 25% I/O, standard ABC,
no retiming/false paths and zero skipped operators: core +566.803 ps and I/O
+684.472 ps. It has no SRAM macros. Reproduce with `scripts/qualify_ppa.py`
using `--stage synthesis --period-ps 1000 --io-delay 0.25`; raw reports remain
local under `.artifacts/`.

This screen qualifies the router. The enclosing vertex stage, SRAM assumptions,
host clock, rendered workload budgets and H0–H8 require their separate gates.
