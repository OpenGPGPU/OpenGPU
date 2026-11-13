# L2 MSHR definition reuse

Each L2 miss engine instantiates a cached `L2MshrTable` definition. This reuses
the elaborated description across equal configurations; every bank still has
independent physical state, four miss entries and the complete transaction
waiter namespace. Merge/refill ordering, response latency, admission rate and
backpressure are unchanged.

A four-bank elaboration with 32,850 transaction IDs, 64-byte lines and
128 sets/four ways per bank retains four physical table instances. Reusing
their definition reduced four table definitions to one and the wrapper's
recursive Chisel command count from 1,381,120 to 345,334 (75% fewer). These
counts were measured before the waiter-update rewrite below. They are
elaboration results for this memory subsystem, not a whole-host conversion,
synthesis, clock or frame-rate qualification.

Ten local RTL cases and the production miss-engine CI case pass.
The two-instance test overlaps identical transaction
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
  'testOnly opengpu.core.memory.L2MshrInstanceSpec opengpu.core.memory.L2MshrTableSpec opengpu.core.memory.L2LateSharerSpec opengpu.core.memory.L2MergeResponseGuardSpec opengpu.core.memory.L2SimultaneousFillSharerSpec opengpu.core.memory.L2MissEngineSpec'
```

The explicit elaboration command emits no RTL or simulation model. Remove
temporary build outputs, caches and logs after their active use ends.

## Waiter bitmap updates

Allocation and response retirement update each entry's own waiter bitmap
under its entry-select enable. Neither update reads a dynamically selected
full-width bitmap back into all entries. All four entries and 32,850 product
transaction IDs remain present. A held response retiring with a committed
same-entry merge ORs the new waiter before clearing the old one; response
retirement retains its original priority over allocation. The cached nonempty
predicate follows the same update, and late sharers still require directory
publication before their response.

The product table also retains all three coherent requesters: two CUs and
the graphics-shader port. The full-capacity emitter includes that third
requester's sharer bit; a two-requester table cannot substitute for it.

The RTL regression includes a 17-ID namespace with simultaneous same-entry
and different-entry merge/retirement, held replies, both boundary IDs, fault
delivery and healthy reuse without reset. Run `L2MshrTableSpec` together with
the existing suites above. Emit the full-capacity table for synthesis or
equivalence comparison, using a GPU-local target directory, with:

```sh
sbt -J-Xmx2G -J-XX:ActiveProcessorCount=2 \
  'set Test / sources := (Test / sources).value.filter(_.getName == "EmitL2MshrRtl.scala")' \
  'Test / runMain opengpu.core.memory.EmitL2MshrRtl .artifacts/mshr-rtl'
```

Changing this feedback structure does not establish a cell-count, clock or
frame-rate improvement; those require measurements of the emitted design.

All 12 directed RTL cases across the six suites above pass with a 2 GiB JVM
and one native compiler job. Both the reference and updated table elaborate
and emit with all four 32,850-bit waiter maps and three coherent requester
bits. Full-capacity Yosys equivalence is not established: candidate preparation
completed `proc`, `opt_expr` and `opt_clean`, but reached the 300-second limit
inside `check -assert` under a one-CPU/2-GiB container budget, without an OOM.
No equivalence or synthesis improvement is inferred from these results.
Reproduce that preparation from the emitted directory with:

```sh
yosys -p 'read_verilog -sv -formal L2MshrTable.sv; hierarchy -check -top L2MshrTable; proc; opt_expr; opt_clean; check -assert; stat -width'
```
