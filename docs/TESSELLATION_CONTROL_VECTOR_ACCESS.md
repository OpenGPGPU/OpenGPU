# Tessellation control vector access

`TessellationControlVectorAccess` converts a decoded vector-issue record into
the control warp scheduler's checked patch-access request and vector-commit
record. It accepts a task-owned warp base supplied by its enclosing dispatcher.
The internal operation selector captures store values, loads or stores; it is
not an instruction encoding or a public ABI. Loads use `vs1` for the vertex
index and `vs2` for an address word: bits 0, 1 and 2 select output, per-patch
and tessellation-level storage, and bits 9:3 select the component. Higher
address bits and vertex bits above bit 5 are rejected before patch traffic.
The patch context checks the actual layout and access permissions.

Store values are captured per lane and warp slot by the parameter operation.
`kernelStart` and `warpStart` invalidate them. Stores select the execution and
side-effect masks; loads and parameter capture select the execution mask.
Inactive load lanes retain old destination data. Empty masks complete without
patch traffic. An error suppresses vector and scalar writeback and exposes a
nonzero status; earlier patch writes are not rolled back. An offered request is
held across cancellation and its result must drain before the commit. When the
context reports a terminal error, the owner asserts `terminal`; that retires a
still-unaccepted offer, while an accepted request still drains its result. The owner
must convert failed status to an attributed shader fault, retain the patch,
warp mapping and instruction until commit is accepted, and prevent that warp
from issuing a barrier or another instruction before the commit. The scheduler
releases its access ownership when this bridge accepts the patch result.

The bounded vector checks cover masks, store-value lifetime, held requests and
commits, malformed operands, partial/faulted replies, cancellation and reuse.
The composed test writes four control outputs, synchronizes at the patch
barrier, then reads them in reverse order through vector issue and commit.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.productQualification=0 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationControlVectorAccessSpec opengpu.graphics.TessellationControlVectorContextSpec opengpu.graphics.TessellationControlWarpSchedulerSpec'
```

This is an internal bridge, not yet connected to CU decode, fault retirement,
or actual shader execution. Task-scoped enablement, warp-base ownership,
native submission, TCS/TES ISA and compiler support, other tessellation
domains and physical and product qualification remain open. H8 remains open.
