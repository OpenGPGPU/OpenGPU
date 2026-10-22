# Concurrent native uniform capacity

`NativeUniformConcurrentCapacitySpec` passes with the actual vertex and fragment
stages, one shared pool of two CUs and the production native execution profile.
Both tasks execute 128 invocations on sixteen lanes and eight warps. Their
accepted roots map twelve logical blocks each into disjoint halves of one
immutable twenty-four-binding table. Every view admits a 16 KiB block.

The test queues both stage launches before releasing the shared pool. It
requires disjoint concurrent CU ownership and physical reads of both roots
while that ownership overlaps. All twenty-four blocks' final words reach the
external memory interface. Both stages' first/last default words are read.
Every one of 128 vertex exports and 128 fragment-lane exports contains the exact
fourteen uniform results. Independent invocation IDs also traverse shader
loads/stores, detecting lost, duplicated or unwritten warp/lane output.

One ScalaTest case runs two complete configurations with the same fixed 64 KiB
payload store and 16 KiB reservation bound:

| Vertex interface | Vertex private prefix | Vertex residence | Fragment residence |
| --- | ---: | --- | --- |
| Two attributes, four varying slots | 15,872 bytes | Local | Spill |
| Sixteen attributes, sixteen varying slots | 69,120 bytes | Spill | Spill |

Fragment tasks retain sixteen varying slots, four color slots and a 45,056-byte
private prefix. Default data stays outside each private prefix. Every vertex
default component and every fragment default component remains unchanged after
each run. Mutating live draw roots after acceptance cannot change the task.
Irregular request readiness, delayed/reordered replies, output backpressure and
held completions retain ownership. Both tasks, all memory responses and payload
reservations drain before the second configuration reuses the pool.

```sh
sbt -Dopengpu.simJobs=4 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.NativeUniformConcurrentCapacitySpec'
```

This establishes simultaneous full-thread stage ownership and the stated
binding-boundary accesses. It does not read every word through the native
stages; [complete loader sweeps](UNIFORM_BUFFER_RESOURCES.md) retain that scope.
It does not measure a throughput budget, translate these concurrent tasks,
publish descriptors from a GPU producer, qualify reset/remapping, or establish
an enclosing clock. [Compact translated host checks](NATIVE_UNIFORM_HOST_OWNERSHIP.md)
and [component timing](NATIVE_UNIFORM_BINDINGS.md) remain separate evidence.
[GUC1 discovery](NATIVE_UNIFORM_DISCOVERY.md) is implemented. Remaining
resource/rate/product gates and H0–H8 stay open.
