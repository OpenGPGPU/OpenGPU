# Concurrent native uniform capacity

`NativeUniformConcurrentCapacitySpec` passes with the actual vertex and fragment
stages, one shared pool of two CUs and the production native execution profile.
Both tasks execute 128 invocations on sixteen lanes and eight warps. Their
accepted roots map twelve logical blocks each into distinct twelve-binding sets in one
immutable twenty-four-binding table. Every view admits a 16 KiB block.

The test queues both stage launches before releasing the shared pool. It
requires disjoint concurrent CU ownership and physical reads of both roots
while that ownership overlaps. All twenty-four blocks' final words reach the
external memory interface. Both stages' first/last default words are read.
Every one of 128 vertex exports and 128 fragment-lane exports contains the exact
fourteen uniform results. Independent invocation IDs also traverse shader
loads/stores, detecting lost, duplicated or unwritten warp/lane output.

Each of two ScalaTest cases runs these configurations with the same fixed 64 KiB
payload store and 16 KiB reservation bound:

| Vertex interface | Vertex private prefix | Vertex residence | Fragment residence |
| --- | ---: | --- | --- |
| Two attributes, four varying slots | 15,872 bytes | Local | Spill |
| Sixteen attributes, sixteen varying slots | 69,120 bytes | Spill | Spill |

Fragment tasks retain sixteen varying slots, four color slots and a 45,056-byte
private prefix. Default data stays outside each private prefix. Every vertex
default component and every fragment default component remains unchanged after
each run. Changing unaccepted reference pins cannot change captured resources.
Root/table/data contents remain immutable from active draw admission through retirement.
Irregular request readiness, delayed/reordered replies, output backpressure and
held completions retain ownership. Both tasks, all memory responses and payload
reservations drain before the second configuration reuses the pool.

The translated case uses the production `TranslatedLineClient` and both CUs'
instruction/vector translators under Sv32 ASID 1. One nonidentity 4 MiB mapping
moves every code, descriptor, data and scratch access by 8 MiB; physical PTE
walks and the absence of escaped virtual data requests are checked.

A real CU producer writes eight words: both roots' final block mappings, four
fields that rebind binding 23 to another 16 KiB allocation, and two data words.
The CPU supplies only initial records and the producer's input packet. Both
draw envelopes queue before its first memory service. The final producer store
reply stays held while both active-stage admissions remain blocked. Five queued
64-byte physical publications wait on the producer's event generation; the
production dependency-idle boundary releases draw admission only after all five
complete. Thus the producer never mutates an active draw's immutable resources.

The stages exchange their final binding indices and consume distinct per-round
GPU values in every invocation. Their root reads overlap on disjoint CUs. The
obsolete data allocation is neither read nor changed, every default word stays
intact, held completions remain stable, and all commands, replies and payload
owners drain before reuse. This checks full stage capacity through the shared
interfaces. Separate [typed renderer checks](NATIVE_UNIFORM_RENDERER_CAPACITY.md)
cover both full-interface payload profiles at two CUs.

```sh
sbt -Dopengpu.simJobs=4 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.NativeUniformConcurrentCapacitySpec'
```

This establishes simultaneous full-thread stage ownership and the stated
binding-boundary accesses. It does not read every word through the native
stages; [complete loader sweeps](UNIFORM_BUFFER_RESOURCES.md) retain that scope.
It does not measure a throughput budget, qualify concurrent reset/remapping,
general cache/context coherence or an enclosing clock. Multi-page permission
faults, four-CU product and full-capacity binned runtime remain required.
[Compact translated host checks](NATIVE_UNIFORM_HOST_OWNERSHIP.md)
and [component timing](NATIVE_UNIFORM_BINDINGS.md) remain separate evidence.
[GUC1 discovery](NATIVE_UNIFORM_DISCOVERY.md) is implemented. Remaining
resource/rate/product gates and H0–H8 stay open.
