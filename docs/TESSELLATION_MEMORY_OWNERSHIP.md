# Tessellation memory ID ownership

The native job and shared host/tessellation memory fabrics retain one ownership
bit for every reserved transaction ID in packed registers. This avoids expanding
separate Scala update/assertion trees for each ID in nested namespaces. ID
capacities, response routing, queues and backpressure are unchanged.

The native job image port permits a response to retire and the same ID to be
accepted again on one clock; acquisition wins. The shared host/tessellation
fabric blocks occupied-ID requests through the retirement clock and preserves
release priority. Both fabrics reject responses without an accepted owner.
The single-ID native image port also rejects its representable but unreserved
tag 1 before it can alias the job descriptor namespace.

All sixteen actual-DUT tests pass. Native image cases cover every ID for 1/8-ID
namespaces and boundaries through ID 4095, held input/output offers, reordered
fault replies, flush with outstanding ownership, same-clock reuse, reset,
duplicate owners and unsolicited/late replies. Shared fabric cases cover every
8/4 local ID and boundaries in 4096/8192-ID namespaces, preserving the carry
through the global ID 12287. Existing translated job/frame and page-fault
checks also pass. These fabrics have no cancellation input: stopping new work
or flushing translation does not release accepted ownership; replies must drain
before an enclosing owner can finish cancellation.

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'set Test / sources := (Test / sources).value.filter(f => Set("TessellationHostMemoryFabricSpec.scala", "TessellationNativeJobMemoryFabricSpec.scala", "GpuSim.scala").contains(f.getName))' \
  'testOnly opengpu.system.TessellationNativeJobMemoryFabricSpec opengpu.system.TessellationHostMemoryFabricSpec'
```

The packed representation does not establish whole-host elaboration, timing,
frame rate or H6–H8 completion. Generated models, caches and logs are temporary
and are removed when their active validation use ends.
