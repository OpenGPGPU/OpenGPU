# L2 MSHR definition reuse

Each L2 miss engine instantiates a cached `L2MshrTable` definition. This reuses
the elaborated description across equal configurations; every bank still has
independent physical state, four miss entries and the complete transaction
waiter namespace. Merge/refill ordering, response latency, admission rate and
backpressure are unchanged.

A four-bank elaboration with 32,850 transaction IDs, 64-byte lines and
128 sets/four ways per bank retains four physical table instances. Reusing
their definition reduces four table definitions to one and the wrapper's
recursive Chisel command count from 1,381,120 to 345,334 (75% fewer). These
are elaboration results for this memory subsystem, not a whole-host conversion,
synthesis, clock or frame-rate qualification.

Ten real RTL tests pass. The two-instance test overlaps identical transaction
IDs and victim indices with different lines/data, holds one response while its
peer faults and reuses an entry, merges low/high IDs in a non-power-of-two
namespace and reuses the held bank without reset. Existing cases cover
out-of-order refills, duplicate waiters, full-entry backpressure, victim
conflicts and last-waiter/late-sharer races, including the production miss
engine's lower-memory path.

Reproduce the full-namespace elaboration and bounded RTL tests:

```sh
sbt -J-Xmx3G -J-XX:+ExitOnOutOfMemoryError -J-XX:ActiveProcessorCount=2 \
  -Dopengpu.l2MshrDefinitionQualification=1 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'set Test / parallelExecution := false' \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "QualifyL2MshrDefinitions.scala", "L2MshrInstanceSpec.scala", "L2MshrTableSpec.scala", "L2LateSharerSpec.scala", "L2MergeResponseGuardSpec.scala", "L2SimultaneousFillSharerSpec.scala").contains(f.getName))' \
  'Test / runMain opengpu.core.memory.QualifyL2MshrDefinitions' \
  'testOnly opengpu.core.memory.L2MshrInstanceSpec opengpu.core.memory.L2MshrTableSpec opengpu.core.memory.L2LateSharerSpec opengpu.core.memory.L2MergeResponseGuardSpec opengpu.core.memory.L2SimultaneousFillSharerSpec'
```

The explicit elaboration command emits no RTL or simulation model. Remove
temporary build outputs, caches and logs after their active use ends.
